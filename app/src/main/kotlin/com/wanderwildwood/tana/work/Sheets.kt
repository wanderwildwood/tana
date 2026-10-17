package com.wanderwildwood.tana.work

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * One sheet of a spreadsheet, as text: what each cell shows, row by row. The first row is
 * taken for the headings, which is what nearly every list kept in a spreadsheet has. Rows
 * with nothing in them are left out. [cut] is true when there was more than is kept.
 */
class Sheet(val name: String, val rows: List<List<String>>, val cut: Boolean = false) {
    val headings: List<String> get() = if (rows.size > 1) rows.first() else emptyList()
    val body: List<List<String>> get() = if (rows.size > 1) rows.subList(1, rows.size) else rows
}

/**
 * Spreadsheets read to be looked through, never written: CSV and TSV, Excel's xlsx and
 * LibreOffice's ods. The old binary xls is not among them; it is a different format, and
 * "Open with" still hands it to an app that reads it.
 *
 * Only the values are read. Formulas come as whatever they last worked out to, which is what
 * the file keeps beside them, and no formatting but a date's is followed.
 */
object Sheets {

    /** Past this many cells in a file, the rest is left unread, so a huge one cannot run the phone out of memory. */
    const val MOST_CELLS = 1_000_000

    private val EXTENSIONS = setOf("csv", "tsv", "tab", "xlsx", "xlsm", "ods")

    fun canView(name: String) = Names.extension(name) in EXTENSIONS

    /** Text as Find compares it: lower case, accents taken off. */
    fun fold(text: String): String {
        // Plain text, the usual case, is only lower-cased: taking a big sheet apart and
        // putting it together again for every search is slow on this phone.
        if (text.all { it.code < 0x80 }) return text.lowercase()
        val apart = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val out = StringBuilder(apart.length)
        for (ch in apart) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) out.append(ch)
        return out.toString().lowercase()
    }

    /** Blocks; never on the main thread. Throws when the file is not what its name says. */
    fun read(file: File): List<Sheet> {
        val sheets = when (Names.extension(file.name)) {
            "xlsx", "xlsm" -> Xlsx(file).read()
            "ods" -> Ods(file).read()
            "tsv", "tab" -> listOf(Delimited.read(file, '\t'))
            else -> listOf(Delimited.read(file, null))
        }
        // An empty sheet beside full ones is only in the way; a file of nothing is still shown, as nothing.
        return sheets.filter { it.rows.isNotEmpty() }.ifEmpty { sheets.take(1) }
    }

    /** Collects rows until there are [limit] cells, and says whether it stopped short. */
    internal class Rows(private val limit: Int = MOST_CELLS) {
        val rows = ArrayList<List<String>>()
        var cells = 0
            private set
        var cut = false
            private set

        /** False once full; the reader should stop. */
        fun add(row: List<String>): Boolean {
            if (cut) return false
            val trimmed = row.dropLastWhile { it.isBlank() }
            if (trimmed.isEmpty()) return true
            if (cells + trimmed.size > limit) {
                cut = true
                return false
            }
            cells += trimmed.size
            rows += trimmed
            return true
        }
    }

    /** Thrown inside a parser to stop it once enough has been read. */
    private object Enough : RuntimeException() {
        private fun readResolve(): Any = Enough
        override fun fillInStackTrace(): Throwable = this
    }

    // ---------------------------------------------------------------- csv and tsv

    internal object Delimited {
        private val CANDIDATES = charArrayOf(',', ';', '\t', '|')

        fun read(file: File, separator: Char?): Sheet {
            // UTF-8 first, strictly; a file that is not is most likely Excel's Windows
            // encoding, which every byte is valid in.
            val text = try {
                decode(file, Charsets.UTF_8)
            } catch (e: CharacterCodingException) {
                decode(file, Charset.forName("windows-1252"))
            }
            return parse(text, separator ?: guess(text), file.name.substringBeforeLast('.'))
        }

        private fun decode(file: File, charset: Charset): BufferedReader {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Read through once to find out whether it decodes at all, without keeping it.
            InputStreamReader(file.inputStream().buffered(), decoder).use { r ->
                val buffer = CharArray(64 * 1024)
                while (r.read(buffer) >= 0) Unit
            }
            return InputStreamReader(file.inputStream().buffered(), charset).buffered()
        }

        /**
         * Which character divides the cells: the one found the same number of times on the
         * most of the first lines. Commas, unless the file plainly uses something else, as
         * spreadsheets set to a language that writes 1,5 for one and a half do (semicolons).
         */
        fun guess(reader: BufferedReader): Char {
            reader.mark(64 * 1024)
            val head = CharArray(64 * 1024)
            val n = reader.read(head).coerceAtLeast(0)
            reader.reset()
            val lines = String(head, 0, n).lines().take(20).filter { it.isNotBlank() }
            if (lines.isEmpty()) return ','
            return CANDIDATES.maxByOrNull { c ->
                val counts = lines.map { line -> countOutsideQuotes(line, c) }
                val usual = counts.groupingBy { it }.eachCount().filterKeys { it > 0 }.maxByOrNull { it.value }
                (usual?.value ?: 0) * 1000 + (usual?.key ?: 0)
            }?.takeIf { c -> lines.any { countOutsideQuotes(it, c) > 0 } } ?: ','
        }

        private fun countOutsideQuotes(line: String, c: Char): Int {
            var quoted = false
            var count = 0
            for (ch in line) {
                if (ch == '"') quoted = !quoted else if (ch == c && !quoted) count++
            }
            return count
        }

        /** RFC 4180, leniently: quotes double to escape, and a cell may run across lines. */
        fun parse(reader: BufferedReader, separator: Char, name: String): Sheet {
            val rows = Rows()
            reader.use { r ->
                var row = ArrayList<String>()
                val cell = StringBuilder()
                var quoted = false
                var atStart = true
                var first = true
                while (true) {
                    val i = r.read()
                    if (i < 0) break
                    val ch = i.toChar()
                    // A byte-order mark at the very start is not part of the first heading.
                    if (first) {
                        first = false
                        if (ch == '﻿') continue
                    }
                    if (quoted) {
                        if (ch == '"') {
                            r.mark(1)
                            val next = r.read()
                            if (next == '"'.code) cell.append('"') else {
                                quoted = false
                                if (next >= 0) r.reset()
                            }
                        } else {
                            cell.append(ch)
                        }
                        continue
                    }
                    when (ch) {
                        '"' -> if (atStart) quoted = true else cell.append(ch)
                        separator -> {
                            row += cell.toString()
                            cell.setLength(0)
                            atStart = true
                            continue
                        }
                        '\r' -> continue
                        '\n' -> {
                            row += cell.toString()
                            cell.setLength(0)
                            if (!rows.add(row)) break
                            row = ArrayList()
                            atStart = true
                            continue
                        }
                        else -> cell.append(ch)
                    }
                    atStart = false
                }
                if (!rows.cut && (cell.isNotEmpty() || row.isNotEmpty())) {
                    row += cell.toString()
                    rows.add(row)
                }
            }
            return Sheet(name, rows.rows, rows.cut)
        }

        fun parse(text: String, separator: Char, name: String = "") = parse(text.reader().buffered(), separator, name)
    }

    // ---------------------------------------------------------------- xml, safely

    /**
     * A parser that reads only what is in the file: no DTDs, nothing fetched from outside it.
     * Prefixes are kept on names (x:row and row are the same thing here), so [local] strips them.
     */
    private fun parse(stream: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, on) -> runCatching { factory.setFeature(feature, on) } }
        try {
            stream.use { factory.newSAXParser().parse(InputSource(it), handler) }
        } catch (e: Enough) {
            // Stopped on purpose; what was read is kept.
        }
    }

    private fun local(qName: String) = qName.substringAfter(':')

    private fun Attributes.find(name: String): String? {
        for (i in 0 until length) if (local(getQName(i)) == name) return getValue(i)
        return null
    }

    // ---------------------------------------------------------------- xlsx

    private class Xlsx(private val file: File) {
        private val zip = ZipFile(file)
        private val strings = ArrayList<String>()
        private val dateStyles = HashSet<Int>()
        private var from1904 = false

        fun read(): List<Sheet> = zip.use {
            val listed = workbook()
            readStrings()
            readStyles()
            val sheets = ArrayList<Sheet>()
            var cells = 0
            for ((name, path) in listed) {
                val entry = zip.getEntry(path) ?: continue
                val sheet = sheet(name, zip.getInputStream(entry), MOST_CELLS - cells)
                cells += sheet.rows.sumOf { it.size }
                if (sheet.rows.isEmpty() && sheet.cut) break
                sheets += sheet
                if (sheet.cut) break
            }
            if (sheets.isEmpty()) throw IllegalArgumentException("No sheets in ${file.name}")
            sheets
        }

        /** The sheets in the order the workbook gives them, by name and where each is kept. */
        private fun workbook(): List<Pair<String, String>> {
            val targets = HashMap<String, String>()
            zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
                parse(zip.getInputStream(e), object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                        if (local(qName) != "Relationship") return
                        val id = a.find("Id") ?: return
                        val target = a.find("Target") ?: return
                        targets[id] = if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
                    }
                })
            }
            val sheets = ArrayList<Pair<String, String>>()
            val workbook = zip.getEntry("xl/workbook.xml") ?: throw IllegalArgumentException("Not an xlsx: ${file.name}")
            parse(zip.getInputStream(workbook), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "workbookPr" -> from1904 = a.find("date1904").let { it == "1" || it == "true" }
                        "sheet" -> {
                            // Hidden sheets are a workbook's own workings, not what it is for.
                            if (a.find("state").let { it == "hidden" || it == "veryHidden" }) return
                            val name = a.find("name") ?: return
                            val target = a.find("id")?.let(targets::get) ?: return
                            sheets += name to target
                        }
                    }
                }
            })
            return sheets
        }

        private fun readStrings() {
            val e = zip.getEntry("xl/sharedStrings.xml") ?: return
            parse(zip.getInputStream(e), object : DefaultHandler() {
                val text = StringBuilder()
                var inT = false
                var inPhonetic = false

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "si" -> text.setLength(0)
                        "t" -> inT = true
                        "rPh" -> inPhonetic = true
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (local(qName)) {
                        "si" -> {
                            strings += text.toString()
                            if (strings.size > MOST_CELLS) throw Enough
                        }
                        "t" -> inT = false
                        "rPh" -> inPhonetic = false
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT && !inPhonetic) text.append(ch, start, length)
                }
            })
        }

        /** Which cell styles are dates, so a date is shown as one and not as 45 000-odd days. */
        private fun readStyles() {
            val e = zip.getEntry("xl/styles.xml") ?: return
            val customDates = HashSet<Int>()
            var inXfs = false
            var index = 0
            parse(zip.getInputStream(e), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "numFmt" -> {
                            val id = a.find("numFmtId")?.toIntOrNull() ?: return
                            if (isDateFormat(a.find("formatCode").orEmpty())) customDates += id
                        }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) {
                            val id = a.find("numFmtId")?.toIntOrNull() ?: 0
                            if (id in 14..22 || id in 45..47 || id in customDates) dateStyles += index
                            index++
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (local(qName) == "cellXfs") inXfs = false
                }
            })
        }

        private fun sheet(name: String, stream: InputStream, room: Int): Sheet {
            val rows = Rows(room)
            parse(stream, object : DefaultHandler() {
                var row = ArrayList<String>()
                var column = 0
                var type = ""
                var style = 0
                val value = StringBuilder()
                var inValue = false
                var inInline = false

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "row" -> {
                            row = ArrayList()
                            column = 0
                        }
                        "c" -> {
                            column = a.find("r")?.let(::columnOf) ?: column
                            type = a.find("t").orEmpty()
                            style = a.find("s")?.toIntOrNull() ?: 0
                            value.setLength(0)
                        }
                        "v" -> inValue = true
                        "is" -> inInline = true
                        "t" -> if (inInline) inValue = true
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (local(qName)) {
                        "v", "t" -> inValue = false
                        "is" -> inInline = false
                        "c" -> {
                            val shown = shown(value.toString(), type, style)
                            if (shown.isNotEmpty()) {
                                while (row.size < column) row += ""
                                if (row.size == column) row += shown else row[column] = shown
                            }
                            column++
                        }
                        "row" -> if (!rows.add(row)) throw Enough
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) value.append(ch, start, length)
                }
            })
            return Sheet(name, rows.rows, rows.cut)
        }

        private fun shown(raw: String, type: String, style: Int): String = when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "str", "inlineStr", "e" -> raw
            else -> if (raw.isBlank()) "" else if (style in dateStyles) date(raw, from1904) ?: number(raw) else number(raw)
        }
    }

    /** "C5" → 2: the column a cell reference names, counting from nought. */
    internal fun columnOf(ref: String): Int? {
        var n = 0
        var any = false
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                n = n * 26 + (ch - 'A' + 1)
                any = true
            } else {
                break
            }
        }
        return if (any) n - 1 else null
    }

    /** A number as a person would write it: no float noise, no needless ".0". */
    internal fun number(raw: String): String = runCatching {
        BigDecimal(raw.trim()).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }.getOrDefault(raw)

    /**
     * A format code that shows a date or a time: one with d, m, y, h or s in it once the
     * quoted text, the escaped characters and the [colour] and [$-locale] parts are taken out.
     */
    internal fun isDateFormat(code: String): Boolean {
        val bare = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\\\."), "")
            .replace(Regex("\\[[^]]*]"), "")
            .lowercase()
        return bare.any { it in "dmyhs" } && !bare.contains("general")
    }

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** A spreadsheet's day number as a date, written the one way that cannot be misread. */
    internal fun date(raw: String, from1904: Boolean): String? {
        val serial = raw.trim().toDoubleOrNull() ?: return null
        if (serial < 0 || serial > 2_958_465) return null
        val days = serial.toLong()
        val seconds = Math.round((serial - days) * 86_400)
        val start = if (from1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val at = start.plusDays(days).plusSeconds(seconds)
        return when {
            days == 0L && !from1904 -> at.format(TIME)
            seconds == 0L -> at.toLocalDate().format(DAY)
            else -> at.format(MINUTE)
        }
    }

    // ---------------------------------------------------------------- ods

    private class Ods(private val file: File) {
        fun read(): List<Sheet> = ZipFile(file).use { zip ->
            val content = zip.getEntry("content.xml") ?: throw IllegalArgumentException("Not an ods: ${file.name}")
            val sheets = ArrayList<Sheet>()
            var budget = MOST_CELLS
            parse(zip.getInputStream(content), object : DefaultHandler() {
                var name = ""
                var rows = Rows()
                var row = ArrayList<String>()
                var pendingEmpty = 0
                var rowRepeat = 1
                var cellRepeat = 1
                var fallback = ""
                val text = StringBuilder()
                var paragraphs = 0
                var inCell = false
                var inNote = 0

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "table" -> {
                            name = a.find("name").orEmpty()
                            rows = Rows(budget)
                        }
                        "table-row" -> {
                            row = ArrayList()
                            pendingEmpty = 0
                            rowRepeat = a.find("number-rows-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        }
                        "table-cell", "covered-table-cell" -> {
                            inCell = true
                            text.setLength(0)
                            paragraphs = 0
                            cellRepeat = a.find("number-columns-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            fallback = a.find("value") ?: a.find("date-value") ?: a.find("time-value")
                                ?: a.find("boolean-value") ?: a.find("string-value") ?: ""
                        }
                        "annotation" -> inNote++
                        "p" -> if (inCell && inNote == 0) {
                            if (paragraphs > 0) text.append('\n')
                            paragraphs++
                        }
                        "s" -> if (inCell && inNote == 0) {
                            repeat(a.find("c")?.toIntOrNull()?.coerceIn(1, 100) ?: 1) { text.append(' ') }
                        }
                        "tab" -> if (inCell && inNote == 0) text.append('\t')
                        "line-break" -> if (inCell && inNote == 0) text.append('\n')
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (local(qName)) {
                        "annotation" -> inNote--
                        "table-cell", "covered-table-cell" -> {
                            inCell = false
                            val shown = text.toString().ifEmpty { fallback }
                            // A sheet ends in a cell repeated to the last column there is;
                            // empty ones are only counted, and written out if something follows.
                            if (shown.isEmpty()) {
                                pendingEmpty += cellRepeat
                            } else {
                                if (row.size + pendingEmpty + cellRepeat > budget) throw stop()
                                repeat(pendingEmpty) { row += "" }
                                pendingEmpty = 0
                                repeat(cellRepeat) { row += shown }
                            }
                        }
                        "table-row" -> if (row.isNotEmpty()) {
                            // Likewise a row repeated to the last row: only one with something
                            // in it is written out as many times as it says.
                            repeat(rowRepeat) { if (!rows.add(row)) throw stop() }
                        }
                        "table" -> {
                            sheets += Sheet(name, rows.rows, rows.cut)
                            budget -= rows.cells
                        }
                    }
                }

                /** The sheet being read, kept as far as it got, and the reading ended. */
                private fun stop(): Enough {
                    sheets += Sheet(name, rows.rows, cut = true)
                    return Enough
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inCell && inNote == 0 && paragraphs > 0) text.append(ch, start, length)
                }
            })
            if (sheets.isEmpty()) throw IllegalArgumentException("No sheets in ${file.name}")
            sheets
        }
    }
}
