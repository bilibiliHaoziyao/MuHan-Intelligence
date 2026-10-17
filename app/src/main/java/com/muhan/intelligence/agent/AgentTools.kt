package com.muhan.intelligence.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 工具定义（发送给模型的 JSON Schema）。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** 客户端可执行的 Agent 工具。 */
interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(arguments: JsonObject): String
}

/**
 * Agent 工具注册表（3.0.0）。
 *
 * 除本地 function 工具外，还向模型暴露服务端 `web_search` 工具（实测内置模型支持），
 * 由模型自行决定何时联网搜索。
 */
@Singleton
class AgentToolRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val tools: List<AgentTool> = listOf(
        TimeTool(),
        CalculatorTool(),
        FetchUrlTool(),
        SaveNoteTool(context),
        ReadNoteTool(context),
        ListNotesTool(context),
    )

    private val byName: Map<String, AgentTool> = tools.associateBy { it.spec.name }

    /** 发送给模型的 tools 数组（web_search + 全部本地工具）。 */
    fun toolsJson(): String = buildJsonArray {
        add(buildJsonObject { put("type", "web_search") })
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.spec.name)
                    put("description", tool.spec.description)
                    put("parameters", json.parseToJsonElement(tool.spec.parametersJson))
                }
            })
        }
    }.toString()

    suspend fun execute(name: String, arguments: JsonObject): String {
        val tool = byName[name] ?: return "未知工具：$name"
        return runCatching { tool.execute(arguments) }
            .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun displayName(name: String): String = when (name) {
        "get_time" -> "获取时间"
        "calculate" -> "计算"
        "fetch_url" -> "读取网页"
        "save_note" -> "保存笔记"
        "read_note" -> "读取笔记"
        "list_notes" -> "列出笔记"
        "web_search" -> "联网搜索"
        else -> name
    }
}

// ------------------------------------------------------------------ 时间

private class TimeTool : AgentTool {
    override val spec = ToolSpec(
        name = "get_time",
        description = "获取当前日期、时间与星期（设备本地时区）。",
        parametersJson = """{"type":"object","properties":{},"required":[]}""",
    )

    override suspend fun execute(arguments: JsonObject): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(Date())
}

// ------------------------------------------------------------------ 计算

private class CalculatorTool : AgentTool {
    override val spec = ToolSpec(
        name = "calculate",
        description = "计算数学表达式，支持 + - * / % ^ 与括号、小数。",
        parametersJson = """{"type":"object","properties":{"expression":{"type":"string","description":"要计算的表达式，如 (12+8)*3.5"}},"required":["expression"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val expr = arguments["expression"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (expr.isBlank()) return "缺少 expression 参数"
        return runCatching { "= ${Calculator.evaluate(expr)}" }
            .getOrElse { "表达式无效：${it.message}" }
    }
}

/** 极简四则运算解析器（递归下降，不依赖脚本引擎）。 */
private object Calculator {
    fun evaluate(input: String): Double {
        val p = Parser(input.replace(" ", ""))
        val v = p.expr()
        require(p.atEnd()) { "存在无法解析的字符" }
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun atEnd() = i >= s.length
        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun eat(c: Char): Boolean = if (peek() == c) { i++; true } else false

        fun expr(): Double {
            var v = term()
            while (true) {
                v = when {
                    eat('+') -> v + term()
                    eat('-') -> v - term()
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                v = when {
                    eat('*') -> v * unary()
                    eat('/') -> {
                        val d = unary()
                        require(d != 0.0) { "除数不能为 0" }
                        v / d
                    }
                    eat('%') -> {
                        val d = unary()
                        require(d != 0.0) { "除数不能为 0" }
                        v % d
                    }
                    else -> return v
                }
            }
        }

        private fun unary(): Double = when {
            eat('+') -> unary()
            eat('-') -> -unary()
            else -> power()
        }

        private fun power(): Double {
            val base = atom()
            return if (eat('^')) Math.pow(base, unary()) else base
        }

        private fun atom(): Double {
            if (eat('(')) {
                val v = expr()
                require(eat(')')) { "缺少右括号" }
                return v
            }
            val start = i
            while (peek()?.let { it.isDigit() || it == '.' } == true) i++
            val num = s.substring(start, i)
            require(num.isNotEmpty()) { "缺少数字" }
            return num.toDouble()
        }
    }
}

// ------------------------------------------------------------------ 网页读取

private class FetchUrlTool : AgentTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override val spec = ToolSpec(
        name = "fetch_url",
        description = "读取指定网页的正文内容（自动去除脚本与标签），用于获取网页详细信息。",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"以 http:// 或 https:// 开头的完整网址"}},"required":["url"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "url 必须以 http:// 或 https:// 开头"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MuHanAgent/3.0")
            .build()
        val html = client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return "HTTP ${res.code}"
            res.body?.string().orEmpty()
        }
        return cleanHtml(html).take(6000)
    }

    private fun cleanHtml(html: String): String {
        var t = html
        t = t.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        t = t.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        t = t.replace(Regex("(?is)<br\\s*/?>"), "\n")
        t = t.replace(Regex("(?is)</(p|div|li|h[1-6]|tr)>"), "\n")
        t = t.replace(Regex("(?is)<[^>]+>"), " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex("\\n\\s*\\n+"), "\n\n")
        return t.trim()
    }
}

// ------------------------------------------------------------------ 笔记

private fun notesDir(context: Context): File =
    File(context.filesDir, "agent_notes").apply { mkdirs() }

private fun safeName(raw: String): String =
    raw.trim().replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "_").take(40).ifBlank { "note" }

private class SaveNoteTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "save_note",
        description = "把一段内容保存为本地笔记，便于后续对话读取。同名笔记会被覆盖。",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"笔记名称"},"content":{"type":"string","description":"笔记内容"}},"required":["name","content"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val name = arguments["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = arguments["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name.isBlank() || content.isBlank()) return "name 与 content 均不能为空"
        val file = File(notesDir(context), "${safeName(name)}.md")
        file.writeText(content)
        return "已保存笔记「${safeName(name)}」（${content.length} 字）"
    }
}

private class ReadNoteTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "read_note",
        description = "读取本地笔记的内容。",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"笔记名称"}},"required":["name"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val name = arguments["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = File(notesDir(context), "${safeName(name)}.md")
        if (!file.exists()) return "未找到笔记「${safeName(name)}」"
        return file.readText().take(6000)
    }
}

private class ListNotesTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "list_notes",
        description = "列出已保存的本地笔记名称。",
        parametersJson = """{"type":"object","properties":{},"required":[]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val files = notesDir(context).listFiles()?.filter { it.isFile }?.map { it.nameWithoutExtension }
            .orEmpty()
        return if (files.isEmpty()) "暂无笔记" else files.joinToString("\n")
    }
}

/** Agent 模式系统提示词。 */
const val AGENT_SYSTEM_PROMPT: String =
    "你是 MuHan Intelligence 的 Agent 模式，运行在用户手机上，可以自主调用工具完成任务。\n" +
        "可用能力：联网搜索（web_search，服务端自动执行）、当前时间（get_time）、数学计算（calculate）、" +
        "读取网页（fetch_url）、本地笔记（save_note / read_note / list_notes）。\n" +
        "工作规则：\n" +
        "1. 需要实时信息（新闻、行情、最新资料）时先使用联网搜索或读取网页，不要凭空编造。\n" +
        "2. 复杂任务拆成多步，必要时连续调用多个工具，拿到足够信息后再给出最终答案。\n" +
        "3. 工具返回内容可能很长，只提炼与用户问题相关的部分。\n" +
        "4. 最终回答使用中文、简洁直接；引用网络信息时给出链接。\n" +
        "5. 不要向用户展示工具调用的内部参数或实现细节。"
