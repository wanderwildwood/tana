package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.ui.letters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SheetsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File = temp.newFile(name).apply { writeBytes(bytes) }

    private fun zip(name: String, entries: Map<String, String>): File {
        val f = temp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (path, text) ->
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test fun csvQuotesAndLinesInsideCells() {
        val sheet = Sheets.Delimited.parse("name,note\r\n\"Smith, J\",\"said \"\"hi\"\"\nthen left\"\r\n\r\nplain,x\n", ',')
        assertEquals(listOf("name", "note"), sheet.headings)
        assertEquals(listOf(listOf("Smith, J", "said \"hi\"\nthen left"), listOf("plain", "x")), sheet.body)
    }

    @Test fun csvLastLineWithoutNewline() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Sheets.Delimited.parse("a,b\n1,2", ',').rows)
    }

    @Test fun csvSemicolonsGuessed() {
        val f = file("list.csv", "﻿part;price\nbolt;1,50\nnut;0,20\n".toByteArray())
        val sheet = Sheets.read(f).single()
        assertEquals(listOf("part", "price"), sheet.headings)
        assertEquals(listOf("bolt", "1,50"), sheet.body[0])
    }

    @Test fun csvNotUtf8IsReadAsWindows() {
        val f = file("old.csv", byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte()))
        assertEquals("café", Sheets.read(f).single().rows[0][0])
    }

    @Test fun tsvByName() {
        val f = file("t.tsv", "a\tb,c\n1\t2\n".toByteArray())
        assertEquals(listOf("a", "b,c"), Sheets.read(f).single().headings)
    }

    @Test fun tooManyCellsIsCut() {
        val text = buildString { repeat(Sheets.MOST_CELLS / 2 + 10) { append("a,b\n") } }
        val sheet = Sheets.Delimited.parse(text, ',')
        assertTrue(sheet.cut)
        assertEquals(Sheets.MOST_CELLS / 2, sheet.rows.size)
    }

    @Test fun xlsx() {
        val f = zip(
            "book.xlsx",
            mapOf(
                "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><workbookPr/><sheets>
                    <sheet name="Parts" sheetId="1" r:id="rId1"/><sheet name="Secret" sheetId="2" state="hidden" r:id="rId2"/><sheet name="Notes" sheetId="3" r:id="rId3"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Target="/xl/worksheets/sheet3.xml"/></Relationships>""",
                "xl/sharedStrings.xml" to """<sst><si><t>Part</t></si><si><t>Bought</t></si><si><r><t>Bo</t></r><r><t>lt</t></r><rPh><t>x</t></rPh></si></sst>""",
                "xl/styles.xml" to """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="d\-mmm\-yy"/><numFmt numFmtId="165" formatCode="&quot;kg&quot; 0.0"/></numFmts>
                    <cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs></styleSheet>""",
                "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="inlineStr"><is><t>Weight</t></is></c></row>
                    <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" s="1"><v>45000</v></c><c r="C2" t="b"><v>1</v></c><c r="D2" s="3"><v>0.30000000000000004</v></c></row>
                    <row r="3"/>
                    <row r="5"><c r="A5" t="str"><f>A2&amp;"!"</f><v>Bolt!</v></c><c r="B5" s="2"><v>45000.5</v></c></row>
                    </sheetData></worksheet>""",
                "xl/worksheets/sheet3.xml" to """<x:worksheet xmlns:x="x"><x:sheetData><x:row><x:c t="inlineStr"><x:is><x:t>hello</x:t></x:is></x:c></x:row></x:sheetData></x:worksheet>""",
            ),
        )
        val sheets = Sheets.read(f)
        assertEquals(listOf("Parts", "Notes"), sheets.map { it.name })
        val parts = sheets[0]
        assertEquals(listOf("Part", "Bought", "", "Weight"), parts.headings)
        assertEquals(listOf("Bolt", "2023-03-15", "TRUE", "0.3"), parts.body[0])
        assertEquals(listOf("Bolt!", "2023-03-15 12:00"), parts.body[1])
        assertEquals(listOf(listOf("hello")), sheets[1].rows)
    }

    @Test fun ods() {
        val f = zip(
            "book.ods",
            mapOf(
                "content.xml" to """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x"><office:body><office:spreadsheet>
                    <table:table table:name="Birds">
                      <table:table-row><table:table-cell><text:p>Bird</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"/><table:table-cell><text:p>Seen</text:p></table:table-cell><table:table-cell table:number-columns-repeated="16380"/></table:table-row>
                      <table:table-row table:number-rows-repeated="2"><table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell><table:table-cell><text:p>a<text:s text:c="2"/>b</text:p><text:p>c</text:p><office:annotation><text:p>note</text:p></office:annotation></table:table-cell></table:table-row>
                      <table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="16384"/></table:table-row>
                    </table:table>
                    <table:table table:name="Empty"><table:table-row><table:table-cell/></table:table-row></table:table>
                    </office:spreadsheet></office:body></office:document-content>""",
            ),
        )
        val sheets = Sheets.read(f)
        assertEquals(listOf("Birds"), sheets.map { it.name })
        assertEquals(listOf("Bird", "", "", "Seen"), sheets[0].headings)
        assertEquals(listOf(listOf("3", "a  b\nc"), listOf("3", "a  b\nc")), sheets[0].body)
        assertFalse(sheets[0].cut)
    }

    @Test fun pieces() {
        assertEquals(0, Sheets.columnOf("A1"))
        assertEquals(27, Sheets.columnOf("AB12"))
        assertEquals("12", Sheets.number("12.0"))
        assertEquals("1234567", Sheets.number("1.234567E6"))
        assertTrue(Sheets.isDateFormat("yyyy-mm-dd"))
        assertTrue(Sheets.isDateFormat("[\$-409]h:mm AM/PM"))
        assertFalse(Sheets.isDateFormat("\"days\" 0"))
        assertFalse(Sheets.isDateFormat("[Red]0.00"))
        assertEquals("1900-01-01", Sheets.date("2", false))
        assertEquals("06:00", Sheets.date("0.25", false))
        assertEquals("1904-01-02", Sheets.date("1", true))
        assertEquals("AB", letters(27))
        assertEquals("cafe creme", Sheets.fold("Café CRÈME"))
    }
}
