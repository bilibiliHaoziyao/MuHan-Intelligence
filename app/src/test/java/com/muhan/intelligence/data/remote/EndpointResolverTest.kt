/**
 * 单元测试：接口地址解析与请求体构建
 *
 * 用户粘贴的 Base URL 形态千奇百怪，这里覆盖了最常见的几种写法，
 * 确保不会因为少写 /v1 之类的问题导致「连接失败」。
 */
package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointResolverTest {

    private fun provider(
        baseUrl: String,
        model: String = "test-model",
        flavor: ApiFlavor = ApiFlavor.OPENAI,
    ) = ProviderConfig(
        id = "test",
        displayName = "test",
        baseUrl = baseUrl,
        modelName = model,
        flavor = flavor,
    )

    // ------------------------------------------------------------ OpenAI 兼容

    @Test
    fun `裸域名补全 v1 与 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointResolver.resolve(provider("https://api.deepseek.com"), true),
        )
    }

    @Test
    fun `已带 v1 时只补 chat completions`() {
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            EndpointResolver.resolve(provider("https://api.moonshot.cn/v1"), true),
        )
    }

    @Test
    fun `带 v4 的智谱地址不被二次补版本号`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            EndpointResolver.resolve(
                provider("https://open.bigmodel.cn/api/paas/v4"),
                true,
            ),
        )
    }

    @Test
    fun `已是完整路径则原样返回`() {
        val url = "https://example.com/v1/chat/completions"
        assertEquals(url, EndpointResolver.resolve(provider(url), true))
    }

    @Test
    fun `末尾斜杠会被裁掉`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointResolver.resolve(provider("https://api.deepseek.com/"), true),
        )
    }

    // ------------------------------------------------------------- Anthropic

    @Test
    fun `Anthropic 补全 v1 messages`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            EndpointResolver.resolve(
                provider("https://api.anthropic.com", flavor = ApiFlavor.ANTHROPIC),
                true,
            ),
        )
    }

    // ---------------------------------------------------------------- Gemini

    @Test
    fun `Gemini 流式补全 alt sse`() {
        val url = EndpointResolver.resolve(
            provider(
                "https://generativelanguage.googleapis.com",
                model = "gemini-2.0-flash",
                flavor = ApiFlavor.GEMINI,
            ),
            stream = true,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            url,
        )
    }

    @Test
    fun `Gemini 非流式使用 generateContent`() {
        val url = EndpointResolver.resolve(
            provider(
                "https://generativelanguage.googleapis.com/v1beta",
                model = "gemini-2.0-flash",
                flavor = ApiFlavor.GEMINI,
            ),
            stream = false,
        )
        assertTrue(url.endsWith(":generateContent"))
    }

    @Test
    fun `Gemini 模型名带 models 前缀时不会重复`() {
        val url = EndpointResolver.resolve(
            provider(
                "https://generativelanguage.googleapis.com",
                model = "models/gemini-2.0-flash",
                flavor = ApiFlavor.GEMINI,
            ),
            stream = true,
        )
        assertTrue(url.contains("/models/gemini-2.0-flash:streamGenerateContent"))
        assertTrue(!url.contains("models/models/"))
    }
}

class RequestBodyFactoryLegacyTest {

    @Test
    fun `OpenAI 请求体包含 system 与历史消息`() {
        val body = RequestBodyFactory.build(
            flavor = ApiFlavor.OPENAI,
            model = "deepseek-chat",
            messages = listOf(
                RequestBodyFactory.OutboundMessage(com.muhan.intelligence.domain.model.Role.USER, "你好"),
                RequestBodyFactory.OutboundMessage(com.muhan.intelligence.domain.model.Role.ASSISTANT, "你好呀"),
                RequestBodyFactory.OutboundMessage(com.muhan.intelligence.domain.model.Role.USER, "再见"),
            ),
            systemPrompt = "你是助手",
            temperature = 1.0f,
            topP = 1.0f,
            maxTokens = 1024,
            stream = true,
        )

        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"max_tokens\":1024"))
    }

    @Test
    fun `Anthropic 请求体将 system 提升为顶层字段`() {
        val body = RequestBodyFactory.build(
            flavor = ApiFlavor.ANTHROPIC,
            model = "claude-3-5-sonnet-latest",
            messages = listOf(RequestBodyFactory.OutboundMessage(com.muhan.intelligence.domain.model.Role.USER, "你好")),
            systemPrompt = "你是助手",
            temperature = 1.0f,
            topP = 1.0f,
            maxTokens = 1024,
            stream = true,
        )

        assertTrue(body.contains("\"system\":\"你是助手\""))
        // Anthropic 的 messages 里不允许出现 system 角色
        assertTrue(!body.contains("\"role\":\"system\""))
    }

    @Test
    fun `Gemini 请求体使用 contents 与 systemInstruction`() {
        val body = RequestBodyFactory.build(
            flavor = ApiFlavor.GEMINI,
            model = "gemini-2.0-flash",
            messages = listOf(RequestBodyFactory.OutboundMessage(com.muhan.intelligence.domain.model.Role.USER, "你好")),
            systemPrompt = "你是助手",
            temperature = 1.0f,
            topP = 1.0f,
            maxTokens = 1024,
            stream = true,
        )

        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(body.contains("\"contents\""))
        assertTrue(body.contains("\"generationConfig\""))
        assertTrue(body.contains("\"maxOutputTokens\""))
    }

    @Test
    fun `解析非流式响应文本`() {
        val openAi = """{"choices":[{"message":{"content":"答案"}}]}"""
        assertEquals("答案", RequestBodyFactory.extractNonStreamingText(ApiFlavor.OPENAI, openAi))

        val anthropic = """{"content":[{"type":"text","text":"答案"}]}"""
        assertEquals("答案", RequestBodyFactory.extractNonStreamingText(ApiFlavor.ANTHROPIC, anthropic))

        val gemini = """{"candidates":[{"content":{"parts":[{"text":"答案"}]}}]}"""
        assertEquals("答案", RequestBodyFactory.extractNonStreamingText(ApiFlavor.GEMINI, gemini))
    }
}
