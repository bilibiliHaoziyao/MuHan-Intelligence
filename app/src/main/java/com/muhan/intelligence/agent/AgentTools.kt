package com.muhan.intelligence.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
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
    sharedClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // 3.1.0：三个联网工具共用一份派生客户端，只覆盖较短超时。
    private val httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val tools: List<AgentTool> = listOf(
        TimeTool(),
        CalculatorTool(),
        FetchUrlTool(httpClient),
        SaveNoteTool(context),
        ReadNoteTool(context),
        ListNotesTool(context),
        ListFilesTool(context),
        ReadFileTool(context),
        WriteFileTool(context),
        AppendFileTool(context),
        EditFileTool(context),
        DeleteFileTool(context),
        MakeDirTool(context),
        MoveFileTool(context),
        SearchFilesTool(context),
        WeatherTool(httpClient),
        HttpRequestTool(httpClient),
        ClipboardTool(context),
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
        "list_files" -> "列出文件"
        "read_file" -> "读取文件"
        "write_file" -> "创建/写入文件"
        "append_file" -> "追加内容"
        "edit_file" -> "编辑文件"
        "delete_file" -> "删除文件"
        "make_dir" -> "创建目录"
        "move_file" -> "移动/重命名"
        "search_files" -> "搜索文件"
        "get_weather" -> "查询天气"
        "http_request" -> "网络请求"
        "write_clipboard" -> "复制到剪贴板"
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

private class FetchUrlTool(private val client: OkHttpClient) : AgentTool {

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

// ------------------------------------------------------------------ 文件工作区

/**
 * Agent 文件工作区：
 * - 已授予「所有文件访问」时用公共存储 MuHan/agent（用户可用文件管理器查看/编辑）；
 * - 否则回退到 App 私有目录 agent_workspace（卸载即清除）。
 */
private fun workspaceRoot(context: Context): File {
    val publicReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        Environment.isExternalStorageManager()
    val root = if (publicReady) {
        File(Environment.getExternalStorageDirectory(), "MuHan/agent")
    } else {
        File(context.filesDir, "agent_workspace")
    }
    root.mkdirs()
    return root
}

/** 把相对路径解析为工作区内的文件，禁止 `..` 越界。 */
private fun resolveInWorkspace(context: Context, path: String): File {
    val root = workspaceRoot(context)
    val clean = path.trim().trimStart('/').replace('\\', '/')
    require(clean.split('/').none { it == ".." }) { "路径不允许包含 .." }
    val target = File(root, clean)
    val rootPath = root.canonicalPath
    val targetPath = target.canonicalPath
    require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
        "路径越界：只能访问工作区内的文件"
    }
    return target
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun isProbablyText(bytes: ByteArray): Boolean =
    bytes.take(512).none { it == 0.toByte() }

private class ListFilesTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "list_files",
        description = "列出 Agent 文件工作区中某个目录下的文件与子目录（默认根目录）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的目录路径，留空表示根目录"}},"required":[]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val dir = resolveInWorkspace(context, path)
        if (!dir.exists()) return "目录不存在：${path.ifBlank { "/" }}"
        if (!dir.isDirectory) return "不是目录：$path"
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        if (entries.isEmpty()) return "目录为空"
        val root = workspaceRoot(context)
        val lines = entries.take(100).map { f ->
            val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
            if (f.isDirectory) "[目录] $rel/" else "[文件] $rel (${humanSize(f.length())})"
        }
        return buildString {
            append(lines.joinToString("\n"))
            if (entries.size > 100) append("\n…共 ${entries.size} 项，仅显示前 100 项")
        }
    }
}

private class ReadFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "read_file",
        description = "读取工作区内文本文件的内容（最多返回 8000 字符）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的文件路径"}},"required":["path"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = resolveInWorkspace(context, path)
        if (!file.exists() || !file.isFile) return "文件不存在：$path"
        if (file.length() > 512 * 1024) return "文件过大（>512KB），请改用 search_files 检索"
        val bytes = file.readBytes()
        if (!isProbablyText(bytes)) return "二进制文件，无法作为文本读取"
        val text = String(bytes, Charsets.UTF_8)
        return if (text.length > 8000) {
            text.take(8000) + "\n…（已截断，共 ${text.length} 字符）"
        } else {
            text
        }
    }
}

private class WriteFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "write_file",
        description = "创建或覆盖工作区内的文本文件（自动创建父目录）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的文件路径，如 notes/todo.md"},"content":{"type":"string","description":"要写入的完整内容"}},"required":["path","content"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = arguments["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return "path 不能为空"
        if (content.length > 200_000) return "内容过大（上限 200000 字符）"
        val file = resolveInWorkspace(context, path)
        if (file.exists() && file.isDirectory) return "同名目录已存在：$path"
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "已写入 $path（${content.length} 字符）"
    }
}

private class AppendFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "append_file",
        description = "在文件末尾追加内容（文件不存在时自动创建）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的文件路径"},"content":{"type":"string","description":"要追加的内容"}},"required":["path","content"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = arguments["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return "path 不能为空"
        val file = resolveInWorkspace(context, path)
        if (file.exists() && file.isDirectory) return "同名目录已存在：$path"
        file.parentFile?.mkdirs()
        file.appendText(content)
        return "已追加到 $path（+${content.length} 字符）"
    }
}

private class EditFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "edit_file",
        description = "精确编辑文件：把 old_text 替换为 new_text（old_text 必须在文件中唯一出现）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的文件路径"},"old_text":{"type":"string","description":"要被替换的原文片段"},"new_text":{"type":"string","description":"替换后的新内容"}},"required":["path","old_text","new_text"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val oldText = arguments["old_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val newText = arguments["new_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (oldText.isBlank()) return "old_text 不能为空"
        val file = resolveInWorkspace(context, path)
        if (!file.exists() || !file.isFile) return "文件不存在：$path"
        if (file.length() > 512 * 1024) return "文件过大，无法编辑"
        val text = file.readText()
        val count = Regex(Regex.escape(oldText)).findAll(text).count()
        if (count == 0) return "未找到要替换的内容，请先用 read_file 确认原文"
        if (count > 1) return "old_text 出现了 $count 次，请提供更长、更精确的片段"
        file.writeText(text.replaceFirst(oldText, newText))
        return "已编辑 $path"
    }
}

private class DeleteFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "delete_file",
        description = "删除工作区内的文件或目录（目录会递归删除）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的文件或目录路径"}},"required":["path"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return "path 不能为空"
        val file = resolveInWorkspace(context, path)
        if (!file.exists()) return "文件不存在：$path"
        val count = if (file.isDirectory) file.walkBottomUp().count { it.isFile } else 1
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        return if (ok) "已删除 $path（$count 个文件）" else "删除失败：$path"
    }
}

private class MakeDirTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "make_dir",
        description = "在工作区创建目录（含多级父目录）。",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"相对工作区的目录路径"}},"required":["path"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return "path 不能为空"
        val dir = resolveInWorkspace(context, path)
        return if (dir.mkdirs() || dir.isDirectory) "已创建目录 $path" else "创建失败：$path"
    }
}

private class MoveFileTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "move_file",
        description = "移动或重命名工作区内的文件/目录。",
        parametersJson = """{"type":"object","properties":{"from":{"type":"string","description":"源路径"},"to":{"type":"string","description":"目标路径"}},"required":["from","to"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val from = arguments["from"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val to = arguments["to"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (from.isBlank() || to.isBlank()) return "from 与 to 均不能为空"
        val src = resolveInWorkspace(context, from)
        val dst = resolveInWorkspace(context, to)
        if (!src.exists()) return "源不存在：$from"
        if (dst.exists()) return "目标已存在：$to"
        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst)
        if (!ok) {
            runCatching {
                src.copyRecursively(dst, overwrite = false)
                src.deleteRecursively()
            }.getOrElse { return "移动失败：${it.message}" }
        }
        return "已移动 $from → $to"
    }
}

private class SearchFilesTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "search_files",
        description = "按关键词搜索工作区内的文件名与文本内容，返回匹配位置片段。",
        parametersJson = """{"type":"object","properties":{"keyword":{"type":"string","description":"要搜索的关键词"},"path":{"type":"string","description":"限定搜索的子目录，留空表示整个工作区"}},"required":["keyword"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val keyword = arguments["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (keyword.isBlank()) return "keyword 不能为空"
        val root = resolveInWorkspace(
            context,
            arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        if (!root.exists()) return "目录不存在"
        val rootPath = workspaceRoot(context).absolutePath
        val results = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown().forEach { f ->
            if (results.size >= 20 || scanned >= 400) return@forEach
            if (!f.isFile) return@forEach
            scanned++
            val rel = f.absolutePath.removePrefix(rootPath).trimStart('/')
            if (f.name.contains(keyword, ignoreCase = true) && results.size < 20) {
                results += "[文件名] $rel"
            }
            if (f.length() <= 512 * 1024) {
                val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                if (text.contains(keyword, ignoreCase = true)) {
                    val line = text.lineSequence().indexOfFirst {
                        it.contains(keyword, ignoreCase = true)
                    } + 1
                    val snippet = text.lineSequence().elementAtOrNull(line - 1)?.trim()?.take(120).orEmpty()
                    if (results.size < 20) results += "[内容] $rel:$line  $snippet"
                }
            }
        }
        return if (results.isEmpty()) "未找到包含「$keyword」的内容" else results.joinToString("\n")
    }
}

// ------------------------------------------------------------------ 天气 / HTTP / 剪贴板

private class WeatherTool(private val client: OkHttpClient) : AgentTool {
    private val json = Json { ignoreUnknownKeys = true }

    override val spec = ToolSpec(
        name = "get_weather",
        description = "查询城市当前天气与未来两天预报（无需 API Key）。",
        parametersJson = """{"type":"object","properties":{"city":{"type":"string","description":"城市名，如 北京、上海、Tokyo"}},"required":["city"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val city = arguments["city"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (city.isBlank()) return "city 不能为空"
        val url = "https://wttr.in/${URLEncoder.encode(city, "UTF-8")}?format=j1&lang=zh"
        val raw = client.newCall(
            Request.Builder().url(url).header("User-Agent", "curl/8.0").build(),
        ).execute().use { res ->
            if (!res.isSuccessful) return "天气服务返回 HTTP ${res.code}"
            res.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return "天气数据解析失败"
        val current = (root["current_condition"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? JsonObject
        val desc = run {
            val localized = ((current?.get("lang_zh") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull() as? JsonObject)
            val fallback = ((current?.get("weatherDesc") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull() as? JsonObject)
            (localized ?: fallback)?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        val text = buildString {
            append("$city 当前：${current?.get("temp_C")?.jsonPrimitive?.contentOrNull ?: "-"}℃，")
            append("$desc，体感 ${current?.get("FeelsLikeC")?.jsonPrimitive?.contentOrNull ?: "-"}℃，")
            append("湿度 ${current?.get("humidity")?.jsonPrimitive?.contentOrNull ?: "-"}%\n")
            val days = root["weather"] as? kotlinx.serialization.json.JsonArray
            days?.take(3)?.forEach { d ->
                val o = d as? JsonObject ?: return@forEach
                append("${o["date"]?.jsonPrimitive?.contentOrNull.orEmpty()}：")
                append("${o["mintempC"]?.jsonPrimitive?.contentOrNull.orEmpty()}~")
                append("${o["maxtempC"]?.jsonPrimitive?.contentOrNull.orEmpty()}℃\n")
            }
        }
        return text.trim()
    }
}

private class HttpRequestTool(private val client: OkHttpClient) : AgentTool {

    override val spec = ToolSpec(
        name = "http_request",
        description = "发起 HTTP 请求调用公开接口（GET/POST），返回状态码与响应正文（截断 4000 字符）。",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"http/https 地址"},"method":{"type":"string","description":"GET 或 POST，默认 GET"},"body":{"type":"string","description":"POST 的请求体（JSON 字符串，可选）"}},"required":["url"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return "url 必须以 http:// 或 https:// 开头"
        }
        val method = (arguments["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val bodyText = arguments["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val builder = Request.Builder().url(url).header("User-Agent", "MuHanAgent/3.0")
        if (method == "POST") {
            builder.post(bodyText.toRequestBody("application/json".toMediaType()))
        }
        val (code, text) = client.newCall(builder.build()).execute().use { res ->
            res.code to res.body?.string().orEmpty()
        }
        return "HTTP $code\n${text.take(4000)}"
    }
}

private class ClipboardTool(private val context: Context) : AgentTool {
    override val spec = ToolSpec(
        name = "write_clipboard",
        description = "把一段文本复制到系统剪贴板，方便用户直接粘贴。",
        parametersJson = """{"type":"object","properties":{"text":{"type":"string","description":"要复制的文本"}},"required":["text"]}""",
    )

    override suspend fun execute(arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) return "text 不能为空"
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "系统剪贴板不可用"
        cm.setPrimaryClip(ClipData.newPlainText("MuHan Intelligence", text))
        return "已复制到剪贴板（${text.length} 字）"
    }
}

/** Agent 模式系统提示词。 */
const val AGENT_SYSTEM_PROMPT: String =
    "你是 MuHan Intelligence 的 Agent 模式，运行在用户手机上，可以自主调用工具完成任务。\n" +
        "可用能力：\n" +
        "• 联网搜索 web_search（服务端执行，用于实时信息）\n" +
        "• 读取网页 fetch_url\n" +
        "• 时间 get_time、计算 calculate\n" +
        "• 文件工作区（MuHan/agent）：list_files / read_file / write_file / append_file / edit_file / delete_file / make_dir / move_file / search_files\n" +
        "• 天气 get_weather、通用接口调用 http_request、复制到剪贴板 write_clipboard\n" +
        "• 笔记：save_note / read_note / list_notes\n" +
        "工作规则：\n" +
        "1. 需要实时信息（新闻、行情、最新资料）时先使用联网搜索或读取网页，不要凭空编造。\n" +
        "2. 涉及文件操作时先 list_files 或 read_file 了解现状，再执行写入/编辑/删除；删除前须确认目标无误。\n" +
        "3. 复杂任务拆成多步，必要时连续调用多个工具，拿到足够信息后再给出最终答案。\n" +
        "4. 工具返回内容可能很长，只提炼与用户问题相关的部分。\n" +
        "5. 最终回答使用中文、简洁直接；引用网络信息时给出链接；写明创建/修改了哪个文件。\n" +
        "6. 所有文件操作限定在 Agent 工作区内，不要尝试访问其他目录。"
