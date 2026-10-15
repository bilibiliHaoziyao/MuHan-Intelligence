package com.muhan.intelligence.ui.screens.chat

import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 0.5.1 的消息渲染管线：数据库是顺序/持久化权威，流式覆盖层只覆盖正在生成的那条。
 * 重点防「回答消失 / 记录错乱 / 重复 id 崩溃」回归。
 */
class MessageReconcileTest {

    private fun msg(id: String, role: Role, content: String, status: MessageStatus = MessageStatus.COMPLETE) =
        ChatMessage(id = id, conversationId = "c1", role = role, content = content, status = status)

    @Test
    fun `无覆盖时直接返回数据库列表`() {
        val db = listOf(msg("u1", Role.USER, "你好"), msg("a1", Role.ASSISTANT, "你好！"))
        val result = applyStreamingOverride(db, null)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `占位尚未落库时追加到末尾`() {
        val db = listOf(msg("u1", Role.USER, "问题"))
        val live = msg("a1", Role.ASSISTANT, "", status = MessageStatus.STREAMING)
        val result = applyStreamingOverride(db, live)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `流式中用实时内容覆盖数据库里的滞后版本`() {
        val db = listOf(
            msg("u1", Role.USER, "问题"),
            msg("a1", Role.ASSISTANT, "旧", status = MessageStatus.STREAMING),
        )
        val live = msg("a1", Role.ASSISTANT, "实时内容更长了", status = MessageStatus.STREAMING)
        val result = applyStreamingOverride(db, live)
        assertEquals("实时内容更长了", result.last().content)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `生成结束且数据库追平后放下覆盖，以数据库为准`() {
        val db = listOf(msg("a1", Role.ASSISTANT, "最终回答"))
        val live = msg("a1", Role.ASSISTANT, "最终回答")
        val result = applyStreamingOverride(db, live)
        assertEquals(db, result)
    }

    @Test
    fun `顺序始终由数据库决定，绝不产生重复 id`() {
        val db = listOf(
            msg("u1", Role.USER, "一"),
            msg("a1", Role.ASSISTANT, "答一"),
            msg("u2", Role.USER, "二"),
        )
        val live = msg("a2", Role.ASSISTANT, "", status = MessageStatus.STREAMING)
        val result = applyStreamingOverride(db, live)
        assertEquals(listOf("u1", "a1", "u2", "a2"), result.map { it.id })
        // 渲染前不能有重复 id（否则 LazyColumn 崩溃）。
        assertEquals(result.size, result.map { it.id }.toSet().size)
    }

    @Test
    fun `覆盖同 id 时不增加元素个数`() {
        val db = listOf(msg("a1", Role.ASSISTANT, "旧", status = MessageStatus.STREAMING))
        val live = msg("a1", Role.ASSISTANT, "新", status = MessageStatus.STREAMING)
        val result = applyStreamingOverride(db, live)
        assertEquals(1, result.size)
        assertFalse(result.any { it.content == "旧" })
        assertTrue(result.any { it.content == "新" })
    }
}
