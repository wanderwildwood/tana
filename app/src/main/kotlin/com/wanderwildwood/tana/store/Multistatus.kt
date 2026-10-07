package com.wanderwildwood.tana.store

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One file or folder in a WebDAV listing. [path] is decoded, and a folder's ends in "/" whether
 * or not the server's href did. [modified] is 0 and [length] -1 where the server does not say.
 */
data class DavItem(
    val path: String,
    val isFolder: Boolean,
    val length: Long = -1,
    val modified: Long = 0,
    val etag: String? = null,
)

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, each naming itself by an
 * href and carrying the properties asked for. Read with the platform's own XML parser, which
 * also runs on a computer, so this is tested there.
 */
fun parseMultistatus(body: InputStream): List<DavItem> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<DavItem>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first("href")?.textContent?.trim() ?: continue
        // Only the properties the server actually has: a 404 propstat lists the ones it lacks.
        var etag: String? = null
        var folder = false
        var modified = 0L
        var length = -1L
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            val status = propstat.first("status")?.textContent ?: ""
            if (!status.contains(" 200 ")) continue
            propstat.first("getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { etag = it }
            propstat.first("getlastmodified")?.textContent?.let { parseHttpDate(it) }?.let { modified = it }
            propstat.first("getcontentlength")?.textContent?.trim()?.toLongOrNull()?.let { length = it }
            if (propstat.first("resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        var path = decodeHref(href)
        if (folder && !path.endsWith("/")) path += "/"
        out += DavItem(path, folder, length, modified, etag)
    }
    return out
}

/** An HTTP date, "Tue, 06 Oct 2026 07:56:40 GMT", as milliseconds; null if it is not one. */
fun parseHttpDate(text: String): Long? =
    runCatching {
        java.time.ZonedDateTime.parse(text.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

/**
 * The path an href names, decoded. Some servers give a full URL here and some only the path;
 * both come out as the path. A literal "+" is a plus, not a space: hrefs are percent-encoded
 * paths, not form fields.
 */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(Regex("/{2,}"), "/")
}

/** Each segment of a path, percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

private const val DAV = "DAV:"

private fun Element.first(name: String): Element? =
    getElementsByTagNameNS(DAV, name).let { if (it.length > 0) it.item(0) as Element else null }
