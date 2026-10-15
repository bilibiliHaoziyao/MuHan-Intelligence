package com.muhan.intelligence.ui.screens.chat

import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定 0.5.0 的聊天记录合并顺序，防止「错乱」回归。 */
class MessageReconcileTest {

    private fun msg(id: String, role: Role, content: String, status: MessageStatus = MessageStatus.COMPLETE) =
        ChatMessage(
            id = id,
            conversationId = "c1",
            role = role,
            content = content,
            status = status,
        )

    @Test
    fun `冷启动：内存为空时按 DB 顺序加载历史`() {
        val db = listOf(
            msg("u1", Role.USER, "你好"),
            msg("a1", Role.ASSISTANT, "你好，有什么可以帮你？"),
        )
        val result = reconcileMessages(emptyList(), db, isGenerating = false)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `生成完成：流式占位被落库的完成态覆盖，顺序不变`() {
        val memory = listOf(
            msg("u1", Role.USER, "问题"),
            msg("a1", Role.ASSISTANT, "部分", status = MessageStatus.STREAMING),
        )
        val db = listOf(
            msg("u1", Role.USER, "问题"),
            msg("a1", Role.ASSISTANT, "完整回答"),
        )
        val result = reconcileMessages(memory, db, isGenerating = false)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
        assertEquals("完整回答", result.last().content)
    }

    @Test
    fun `图片模式回归：用户消息不会跑到回复之后`() {
        // 图片模式下用户提问此前是 DB-only，旧逻辑会把它甩到队尾 → [a1, u1]。
        val memory = listOf(
            msg("u1", Role.USER, "画只猫"),
            msg("a1", Role.ASSISTANT, "[图片]"),
        )
        val db = listOf(
            msg("u1", Role.USER, "画只猫"),
            msg("a1", Role.ASSISTANT, "[图片]"),
        )
        val result = reconcileMessages(memory, db, isGenerating = false)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `DB-only 消息按 DB 顺序追加到末尾`() {
        val memory = listOf(msg("u1", Role.USER, "第一轮"))
        val db = listOf(
            msg("u1", Role.USER, "第一轮"),
            msg("a1", Role.ASSISTANT, "回答一"),
            msg("u2", Role.USER, "第二轮"),
        )
        val result = reconcileMessages(memory, db, isGenerating = false)
        assertEquals(listOf("u1", "a1", "u2"), result.map { it.id })
    }

    @Test
    fun `流式进行中：直接以内存顺序为权威，忽略滞后的 DB`() {
        val memory = listOf(
            msg("u1", Role.USER, "问题"),
            msg("a1", Role.ASSISTANT, "实时内容", status = MessageStatus.STREAMING),
        )
        val db = listOf(msg("u1", Role.USER, "问题")) // DB 尚未包含助手占位
        val result = reconcileMessages(memory, db, isGenerating = true)
        assertEquals(listOf("u1", "a1"), result.map { it.id })
        assertEquals("实时内容", result.last().content)
    }

    @Test
    fun `流式中间态不被更短的 DB 覆盖`() {
        val memory = listOf(msg("a1", Role.ASSISTANT, "实时长内容", status = MessageStatus.STREAMING))
        val db = listOf(msg("a1", Role.ASSISTANT, "旧短", status = MessageStatus.STREAMING))
        // DB 仍是 STREAMING（未落库完成），应保留内存的实时内容。
        val result = reconcileMessages(memory, db, isGenerating = false)
        assertEquals("实时长内容", result.first().content)
    }
}
