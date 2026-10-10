/**
 * 单元测试：SSE 流式解析
 *
 * 这是整个应用里最容易出错、也最影响体验的部分（任何一个字符解析错都会
 * 导致回复乱码或卡住），因此单独覆盖 OpenAI / Anthropic / Gemini 三种协议。
 */
package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamParserTest {

    @Test
    fun `解析 OpenAI 增量内容`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)

        val events = parser.feed(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n",
        )

        val text = events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text }
        assertEquals("你好，世界", text)
    }

    @Test
    fun `解析 DeepSeek 思维链字段`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)

        val events = parser.feed(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想一想\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"答案是 42\"}}]}\n\n",
        )

        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text }
        val content = events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text }
        assertEquals("先想一想", reasoning)
        assertEquals("答案是 42", content)
    }

    @Test
    fun `识别 DONE 结束标记`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed("data: [DONE]\n\n")
        assertTrue(events.any { it is StreamEvent.Completed })
    }

    @Test
    fun `处理跨包分片的 JSON`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)

        // 网络分片可能在任意位置切断，解析器必须容忍。
        val first = parser.feed("data: {\"choices\":[{\"delta\":{\"con")
        assertTrue(first.isEmpty())

        val second = parser.feed("tent\":\"完整\"}}]}\n\n")
        assertEquals("完整", second.filterIsInstance<StreamEvent.ContentDelta>().first().text)
    }

    @Test
    fun `解析用量统计`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed(
            "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":25}," +
                "\"choices\":[{\"delta\":{}}]}\n\n",
        )
        val usage = events.filterIsInstance<StreamEvent.Usage>().first()
        assertEquals(10, usage.promptTokens)
        assertEquals(25, usage.completionTokens)
    }

    @Test
    fun `解析 Anthropic 文本增量`() {
        val parser = SseStreamParser(ApiFlavor.ANTHROPIC)
        val events = parser.feed(
            "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n",
        )
        assertEquals("你好", events.filterIsInstance<StreamEvent.ContentDelta>().first().text)
    }

    @Test
    fun `解析 Anthropic 思考过程`() {
        val parser = SseStreamParser(ApiFlavor.ANTHROPIC)
        val events = parser.feed(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"推理中\"}}\n\n",
        )
        assertEquals("推理中", events.filterIsInstance<StreamEvent.ReasoningDelta>().first().text)
    }

    @Test
    fun `解析 Gemini 文本增量`() {
        val parser = SseStreamParser(ApiFlavor.GEMINI)
        val events = parser.feed(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你好\"}]}}]}\n\n",
        )
        assertEquals("你好", events.filterIsInstance<StreamEvent.ContentDelta>().first().text)
    }

    @Test
    fun `流内错误对象转为失败事件`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed(
            "data: {\"error\":{\"message\":\"额度不足\"}}\n\n",
        )
        assertEquals("额度不足", events.filterIsInstance<StreamEvent.Failed>().first().message)
    }

    @Test
    fun `忽略注释行与空事件`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed(": keep-alive\n\n: ping\n\n")
        assertTrue(events.isEmpty())
    }

    // 0.2.0 Fix 回归：类型不符的字段不得抛异常（曾在 OkHttp 线程杀死进程）

    @Test
    fun `delta 为 null 时不抛异常且跳过该事件`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed(
            "data: {\"choices\":[{\"delta\":null,\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
        )
        assertEquals("ok", events.filterIsInstance<StreamEvent.ContentDelta>().first().text)
    }

    @Test
    fun `error 为字符串时不抛异常`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed("data: {\"error\":\"boom\"}\n\n")
        // 无法提取可读信息，静默跳过而不是崩溃
        assertTrue(events.isEmpty())
    }

    @Test
    fun `choices 元素非对象时不抛异常`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed("data: {\"choices\":[\"weird\"]}\n\n")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `usage 字段类型异常时不抛异常`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        // 0.2.0 Fix2：usage:null 安全忽略，同 chunk 内的内容仍然交付
        val events = parser.feed(
            "data: {\"usage\":null,\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n",
        )
        assertEquals("hi", events.filterIsInstance<StreamEvent.ContentDelta>().first().text)
    }

    @Test
    fun `DeepSeek 中途 usage null 不吞内容`() {
        // DeepSeek 真实流形态：除最后一块外每个 chunk 都带 "usage":null。
        // 0.2.0 Fix 曾因此丢弃全部内容（表现为模型不回复）。
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        val events = parser.feed(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"},\"usage\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"usage\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}\n\n" +
                "data: [DONE]\n\n",
        )
        assertEquals("你好", events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text })
        assertEquals(1, events.filterIsInstance<StreamEvent.Usage>().size)
        assertTrue(events.last() is StreamEvent.Completed)
    }

    @Test
    fun `flush 遇到畸形事件不抛异常`() {
        val parser = SseStreamParser(ApiFlavor.OPENAI)
        parser.feed("data: {\"choices\":[{\"delta\":\"string-not-object\"}]}\n\n")
        assertTrue(parser.flush().isEmpty())
    }
}
