package com.wanderwildwood.tana.work

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    private fun spans(r: MdRow) = r.spans.map { Triple(r.text.substring(it.start, it.end), it.style, 0) }.map { it.first to it.second }

    @Test fun headingsHaveTheirLevel() {
        val h = Markdown.row("## A title ##")
        assertEquals(MdKind.HEADING, h.kind)
        assertEquals(2, h.level)
        assertEquals("A title", h.text)
        assertEquals(MdKind.PARAGRAPH, Markdown.row("#hashtag").kind)
    }

    @Test fun bulletsAndNumbers() {
        assertEquals("• apples", Markdown.row("- apples").text)
        assertEquals(MdKind.ITEM, Markdown.row("* apples").kind)
        assertEquals("2. pears", Markdown.row("2. pears").text)
        val nested = Markdown.row("    - deeper")
        assertEquals(2, nested.level)
        assertEquals("[x] done", Markdown.row("- [x] done").text)
        assertEquals("[ ] todo", Markdown.row("- [ ] todo").text)
    }

    @Test fun inlineMarks() {
        val r = Markdown.row("Some **bold**, *slanted* and `code` here")
        assertEquals("Some bold, slanted and code here", r.text)
        assertEquals(
            listOf("bold" to MdStyle.BOLD, "slanted" to MdStyle.ITALIC, "code" to MdStyle.CODE),
            spans(r),
        )
    }

    @Test fun linksShowTheirWordsAndImagesTheirDescription() {
        assertEquals("see the docs now", Markdown.row("see [the docs](https://example.org/a_b) now").text)
        assertEquals("a cat", Markdown.row("![a cat](cat.png)").text)
        assertEquals("example.org", Markdown.row("<https://example.org>").text.removePrefix("https://"))
        assertEquals("words", Markdown.row("[words][ref]").text)
        assertEquals("[plain] brackets", Markdown.row("[plain] brackets").text)
    }

    @Test fun snakeCaseAndSumsAreLeftAlone() {
        assertEquals("a snake_case_name", Markdown.row("a snake_case_name").text)
        assertEquals("2 * 3 * 4", Markdown.row("2 * 3 * 4").text)
        assertEquals("*", Markdown.row("\\*").text)
    }

    @Test fun quotesRulesAndCode() {
        val q = Markdown.row("> > quiet **words**")
        assertEquals(MdKind.QUOTE, q.kind)
        assertEquals(2, q.level)
        assertEquals("quiet words", q.text)
        assertEquals(MdKind.RULE, Markdown.row("---").kind)
        assertEquals(MdKind.RULE, Markdown.row("* * *").kind)
        assertEquals(MdKind.RULE, Markdown.row("|---|:--:|").kind)
        assertEquals(MdKind.BLANK, Markdown.row("   ").kind)
        val c = Markdown.row("  **not bold**", inCode = true)
        assertEquals(MdKind.CODE, c.kind)
        assertEquals("  **not bold**", c.text)
        assertEquals(MdKind.FENCE, Markdown.row("```kotlin", fence = true).kind)
    }
}
