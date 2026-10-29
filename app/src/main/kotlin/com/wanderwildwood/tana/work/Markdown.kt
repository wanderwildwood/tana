package com.wanderwildwood.tana.work

/** How a run of a Markdown row is drawn, from [start] up to [end] of its text. */
enum class MdStyle { BOLD, ITALIC, CODE }

data class MdSpan(val start: Int, val end: Int, val style: MdStyle)

enum class MdKind { BLANK, PARAGRAPH, HEADING, CODE, RULE, QUOTE, ITEM, FENCE }

/**
 * One Markdown line ready to draw: its [text] with the marks taken out, how it is set
 * ([kind]; [level] is the heading's 1 to 6, or how deep a quote or list item goes), and the
 * runs of bold, italic and code in it.
 */
data class MdRow(val text: String, val kind: MdKind, val level: Int = 0, val spans: List<MdSpan> = emptyList())

/**
 * Markdown as plain reading, a line at a time and without a library: headings, bullets and
 * numbers, quotes, rules, code, and bold, italic and code in a line. A link shows as its
 * words and an image as its description; nothing is fetched. What it does not know it shows
 * as it was written, which is how Markdown is meant to read.
 */
object Markdown {

    private val RULE = Regex("""^ {0,3}([-*_])( ?\1){2,}\s*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:\s+(.*?))?(?:\s+#+)?\s*$""")
    private val ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    /** [fence]: this line is a ``` or ~~~ line; [inCode]: it is inside one. See [TextBook]. */
    fun row(line: String, fence: Boolean = false, inCode: Boolean = false): MdRow {
        if (fence) return MdRow("", MdKind.FENCE)
        if (inCode) return MdRow(line.replace("\t", "    "), MdKind.CODE)
        if (line.isBlank()) return MdRow("", MdKind.BLANK)
        if (RULE.matches(line) || (line.contains('|') && line.contains("--") && TABLE_RULE.matches(line))) {
            return MdRow("", MdKind.RULE)
        }
        HEADING.matchEntire(line)?.let { m ->
            return inline(m.groupValues[2].trim(), MdKind.HEADING, m.groupValues[1].length)
        }
        // A quote, however deep: its marks counted and taken off.
        var rest = line.trimStart()
        if (rest.startsWith(">")) {
            var depth = 0
            while (rest.startsWith(">")) {
                depth++
                rest = rest.drop(1).trimStart()
            }
            val inner = row(rest)
            return inner.copy(kind = if (inner.kind == MdKind.PARAGRAPH || inner.kind == MdKind.BLANK) MdKind.QUOTE else inner.kind, level = depth)
        }
        ITEM.matchEntire(line)?.let { m ->
            val depth = m.groupValues[1].replace("\t", "    ").length / 2
            val marker = m.groupValues[2]
            var body = m.groupValues[3]
            var lead = if (marker[0].isDigit()) marker else "•"
            if (body.startsWith("[ ] ")) { lead = "[ ]"; body = body.drop(4) }
            else if (body.startsWith("[x] ") || body.startsWith("[X] ")) { lead = "[x]"; body = body.drop(4) }
            val inner = inline(body, MdKind.ITEM, depth)
            val shift = lead.length + 1
            return inner.copy(
                text = "$lead ${inner.text}",
                spans = inner.spans.map { it.copy(start = it.start + shift, end = it.end + shift) },
            )
        }
        return inline(line.trim(), MdKind.PARAGRAPH, 0)
    }

    private fun inline(text: String, kind: MdKind, level: Int): MdRow {
        val out = StringBuilder()
        val spans = ArrayList<MdSpan>()
        emit(text, out, spans)
        return MdRow(out.toString(), kind, level, spans)
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|<>~"

    private fun emit(s: String, out: StringBuilder, spans: MutableList<MdSpan>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                out.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i + 1) {
                    val at = out.length
                    out.append(s, i + 1, j)
                    spans += MdSpan(at, out.length, MdStyle.CODE)
                    i = j + 1
                    continue
                }
            }
            if (c == '!' && s.startsWith("![", i)) {
                val link = link(s, i + 1)
                if (link != null) {
                    val alt = link.first.ifBlank { "image" }
                    out.append(alt)
                    i = link.second
                    continue
                }
            }
            if (c == '[') {
                val link = link(s, i)
                if (link != null) {
                    emit(link.first, out, spans)
                    i = link.second
                    continue
                }
            }
            if (c == '<') {
                val j = s.indexOf('>', i + 1)
                if (j > i + 1) {
                    val inner = s.substring(i + 1, j)
                    if (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:")) {
                        out.append(inner.removePrefix("mailto:")); i = j + 1; continue
                    }
                }
            }
            if (s.startsWith("~~", i)) {
                val j = s.indexOf("~~", i + 2)
                if (j > i + 2) {
                    emit(s.substring(i + 2, j), out, spans); i = j + 2; continue
                }
            }
            if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
                val j = s.indexOf("$c$c", i + 2)
                if (j > i + 2 && !s[i + 2].isWhitespace() && !s[j - 1].isWhitespace()) {
                    wrap(s.substring(i + 2, j), MdStyle.BOLD, out, spans)
                    i = j + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                // An underscore inside a word (snake_case) is not emphasis.
                val opens = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
                if (opens) {
                    var j = i + 1
                    var found = -1
                    while (j < s.length) {
                        j = s.indexOf(c, j)
                        if (j < 0) break
                        val closes = !s[j - 1].isWhitespace() && (j + 1 >= s.length || s[j + 1] != c) &&
                            (c == '*' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit())
                        if (closes) { found = j; break }
                        j++
                    }
                    if (found > i + 1) {
                        wrap(s.substring(i + 1, found), MdStyle.ITALIC, out, spans)
                        i = found + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }

    private fun wrap(inner: String, style: MdStyle, out: StringBuilder, spans: MutableList<MdSpan>) {
        val at = out.length
        emit(inner, out, spans)
        if (out.length > at) spans += MdSpan(at, out.length, style)
    }

    /** `[words](address)` or `[words][ref]` at [from]: the words and where the link ends; null if it is not one. */
    private fun link(s: String, from: Int): Pair<String, Int>? {
        var depth = 0
        var close = -1
        var k = from
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { close = k; break } }
            }
            k++
        }
        if (close < 0 || close + 1 >= s.length) return null
        val words = s.substring(from + 1, close)
        return when (s[close + 1]) {
            '(' -> {
                var d = 0
                var m = close + 1
                while (m < s.length) {
                    if (s[m] == '(') d++
                    if (s[m] == ')') { d--; if (d == 0) break }
                    m++
                }
                if (m >= s.length) null else words to (m + 1)
            }
            '[' -> {
                val m = s.indexOf(']', close + 2)
                if (m < 0) null else words to (m + 1)
            }
            else -> null
        }
    }
}
