/**
 * 单元测试：Markdown 解析器
 *
 * 聊天场景里模型输出的 Markdown 经常是「边生成边解析」的中间状态
 * （例如代码块围栏还没闭合），这里覆盖这些边界情况。
 */
package com.muhan.intelligence.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `解析标题层级`() {
        val blocks = MarkdownRenderer.parse("# 一级\n## 二级\n### 三级")
        val headings = blocks.filterIsInstance<MarkdownRenderer.Block.Heading>()
        assertEquals(3, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals("一级", headings[0].text)
        assertEquals(3, headings[2].level)
    }

    @Test
    fun `解析无序与有序列表`() {
        val blocks = MarkdownRenderer.parse("- 第一项\n- 第二项\n1. 有序项")

        val bullets = blocks.filterIsInstance<MarkdownRenderer.Block.BulletItem>()
        assertEquals(3, bullets.size)
        assertTrue(!bullets[0].ordered)
        assertTrue(bullets[2].ordered)
        assertEquals(1, bullets[2].index)
    }

    @Test
    fun `闭合的代码块被标记为完整`() {
        val blocks = MarkdownRenderer.parse("```kotlin\nval a = 1\n```")
        val code = blocks.filterIsInstance<MarkdownRenderer.Block.Code>().first()
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1", code.code)
        assertTrue(code.complete)
    }

    @Test
    fun `流式过程中未闭合的代码块不计为完整`() {
        val blocks = MarkdownRenderer.parse("```python\nprint(1)")
        val code = blocks.filterIsInstance<MarkdownRenderer.Block.Code>().first()
        assertTrue(!code.complete)
    }

    @Test
    fun `解析引用块`() {
        val blocks = MarkdownRenderer.parse("> 这是一段引用\n> 第二行")
        val quote = blocks.filterIsInstance<MarkdownRenderer.Block.Quote>().first()
        assertTrue(quote.text.contains("这是一段引用"))
        assertTrue(quote.text.contains("第二行"))
    }

    @Test
    fun `解析分割线`() {
        val blocks = MarkdownRenderer.parse("上方\n\n---\n\n下方")
        assertTrue(blocks.any { it is MarkdownRenderer.Block.Divider })
    }

    @Test
    fun `普通段落与列表混合时顺序正确`() {
        val blocks = MarkdownRenderer.parse("介绍文字\n\n- 项目一\n- 项目二\n\n结尾文字")

        assertTrue(blocks[0] is MarkdownRenderer.Block.Paragraph)
        assertTrue(blocks[1] is MarkdownRenderer.Block.BulletItem)
        assertTrue(blocks[2] is MarkdownRenderer.Block.BulletItem)
        assertTrue(blocks[3] is MarkdownRenderer.Block.Paragraph)
    }

    @Test
    fun `解析表格为等宽文本`() {
        val blocks = MarkdownRenderer.parse(
            "| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |",
        )
        val table = blocks.filterIsInstance<MarkdownRenderer.Block.Code>().first()
        assertEquals("table", table.language)
        assertTrue(table.code.contains("名称"))
        assertTrue(table.code.contains("a"))
    }

    @Test
    fun `空输入返回空块列表`() {
        assertTrue(MarkdownRenderer.parse("").isEmpty())
    }

    @Test
    fun `行内粗体与行内代码被正确标注`() {
        val annotated = MarkdownRenderer.inline(
            text = "这是 **加粗** 与 `代码` 混排",
            linkColor = androidx.compose.ui.graphics.Color.Blue,
            codeBg = androidx.compose.ui.graphics.Color.Gray,
            codeFg = androidx.compose.ui.graphics.Color.Black,
        )
        // 渲染后的纯文本应保留原始字符内容
        assertEquals("这是 加粗 与  代码  混排", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun `未闭合的行内标记原样输出`() {
        val annotated = MarkdownRenderer.inline(
            text = "这是**未闭合",
            linkColor = androidx.compose.ui.graphics.Color.Blue,
            codeBg = androidx.compose.ui.graphics.Color.Gray,
            codeFg = androidx.compose.ui.graphics.Color.Black,
        )
        assertEquals("这是**未闭合", annotated.text)
    }
}
