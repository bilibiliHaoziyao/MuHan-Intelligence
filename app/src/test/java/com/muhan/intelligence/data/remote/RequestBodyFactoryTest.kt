package com.muhan.intelligence.data.remote

import com.muhan.intelligence.data.remote.RequestBodyFactory.OutboundMessage
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBodyFactoryTest {

    private fun build(
        flavor: ApiFlavor = ApiFlavor.OPENAI,
        messages: List<OutboundMessage> = listOf(OutboundMessage(Role.USER, "你好")),
        thinking: Boolean = false,
        webSearch: Boolean = false,
    ): String = RequestBodyFactory.build(
        flavor = flavor,
        model = "test-model",
        messages = messages,
        systemPrompt = "",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 1024,
        stream = false,
        thinkingEnabled = thinking,
        webSearchEnabled = webSearch,
    )

    // ------------------------------------------------------- plain text turns

    @Test
    fun `openai plain message uses string content`() {
        val body = Json.parseToJsonElement(build()).jsonObject
        val message = body["messages"]!!.jsonArray.first().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("你好", message["content"]!!.jsonPrimitive.content)
    }

    // --------------------------------------------------------- vision shapes

    @Test
    fun `openai image message uses multipart content with image_url parts`() {
        val body = Json.parseToJsonElement(
            build(
                messages = listOf(
                    OutboundMessage(
                        Role.USER,
                        "看看这张图",
                        listOf("data:image/png;base64,AAAA"),
                    ),
                ),
            ),
        ).jsonObject
        val content = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic image message uses base64 source block`() {
        val body = Json.parseToJsonElement(
            build(
                flavor = ApiFlavor.ANTHROPIC,
                messages = listOf(
                    OutboundMessage(Role.USER, "看图", listOf("data:image/jpeg;base64,BBBB")),
                ),
            ),
        ).jsonObject
        val content = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("base64", image["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("BBBB", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini image message uses inline_data part`() {
        val body = Json.parseToJsonElement(
            build(
                flavor = ApiFlavor.GEMINI,
                messages = listOf(
                    OutboundMessage(Role.USER, "看图", listOf("data:image/webp;base64,CCCC")),
                ),
            ),
        ).jsonObject
        val parts = body["contents"]!!.jsonArray.first().jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/webp", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals("CCCC", inline["data"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------- thinking / web search

    @Test
    fun `openai thinking off sends enable_thinking false`() {
        val body = Json.parseToJsonElement(build(thinking = false)).jsonObject
        assertEquals("false", body["enable_thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai thinking on omits enable_thinking`() {
        val body = Json.parseToJsonElement(build(thinking = true)).jsonObject
        assertNull(body["enable_thinking"])
    }

    @Test
    fun `openai web search sends web_search_options`() {
        val body = Json.parseToJsonElement(build(webSearch = true)).jsonObject
        assertNotNull(body["web_search_options"])
    }

    @Test
    fun `anthropic thinking enabled omits temperature and adds thinking block`() {
        val body = Json.parseToJsonElement(build(flavor = ApiFlavor.ANTHROPIC, thinking = true)).jsonObject
        assertNotNull(body["thinking"])
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
        assertNull(body["top_p"])
    }

    @Test
    fun `anthropic thinking disabled keeps temperature and omits thinking`() {
        val body = Json.parseToJsonElement(build(flavor = ApiFlavor.ANTHROPIC, thinking = false)).jsonObject
        assertNull(body["thinking"])
        assertNotNull(body["temperature"])
    }

    @Test
    fun `anthropic web search adds web_search tool`() {
        val body = Json.parseToJsonElement(build(flavor = ApiFlavor.ANTHROPIC, webSearch = true)).jsonObject
        val tool = body["tools"]!!.jsonArray.first().jsonObject
        assertEquals("web_search_20250305", tool["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini thinking budget reflects toggle`() {
        val off = Json.parseToJsonElement(build(flavor = ApiFlavor.GEMINI, thinking = false))
            .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(0, off["thinkingBudget"]!!.jsonPrimitive.content.toInt())

        val on = Json.parseToJsonElement(build(flavor = ApiFlavor.GEMINI, thinking = true))
            .jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals(-1, on["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `gemini web search adds google_search tool`() {
        val body = Json.parseToJsonElement(build(flavor = ApiFlavor.GEMINI, webSearch = true)).jsonObject
        assertNotNull(body["tools"]!!.jsonArray.first().jsonObject["google_search"])
    }

    // ------------------------------------------------------ system filtering

    @Test
    fun `anthropic and gemini drop system messages from contents`() {
        val messages = listOf(
            OutboundMessage(Role.SYSTEM, "sys"),
            OutboundMessage(Role.USER, "hi"),
        )
        val anthropic = Json.parseToJsonElement(build(ApiFlavor.ANTHROPIC, messages)).jsonObject
        assertEquals(1, anthropic["messages"]!!.jsonArray.size)

        val gemini = Json.parseToJsonElement(build(ApiFlavor.GEMINI, messages)).jsonObject
        assertEquals(1, gemini["contents"]!!.jsonArray.size)
    }

    // ------------------------------------------------------------ image gen

    @Test
    fun `image request has b64 response format`() {
        val body = Json.parseToJsonElement(
            RequestBodyFactory.buildImageRequest("dall-e-3", "一只猫"),
        ).jsonObject
        assertEquals("dall-e-3", body["model"]!!.jsonPrimitive.content)
        assertEquals("一只猫", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("b64_json", body["response_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extractImageReply handles b64 and url replies`() {
        val b64 = RequestBodyFactory.extractImageReply(
            """{"data":[{"b64_json":"QQ==","revised_prompt":"a cat"}]}""",
        )
        assertTrue(b64!!.isBase64)
        assertEquals("QQ==", b64.source)
        assertEquals("a cat", b64.revisedPrompt)

        val url = RequestBodyFactory.extractImageReply(
            """{"data":[{"url":"https://example.com/x.png"}]}""",
        )
        assertFalse(url!!.isBase64)
        assertEquals("https://example.com/x.png", url.source)
    }

    @Test
    fun `extractImageReply returns null on garbage`() {
        assertNull(RequestBodyFactory.extractImageReply("not json"))
        assertNull(RequestBodyFactory.extractImageReply("""{"data":[]}"""))
    }
}
