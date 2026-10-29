package com.wanderwildwood.tana

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.tana.ui.PdfScreen
import com.wanderwildwood.tana.ui.TextScreen
import com.wanderwildwood.tana.ui.monochrome
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.PdfBook
import com.wanderwildwood.tana.work.PdfSource
import com.wanderwildwood.tana.work.Pdfs
import com.wanderwildwood.tana.work.Texts
import java.io.File

/**
 * A PDF or text file another app handed over to be opened (`ACTION_VIEW`): an email's attachment, a
 * download. Read here as one tapped in a folder is, in the asking app's own task rather than
 * the file manager's, so Back goes straight back to it.
 *
 * Nothing here needs the storage access the folders do: another app's PDF comes with a grant
 * to read it, and that is all this reads.
 */
class ReadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val source = PdfSource.OfUri(uri)
        val type = intent.type
        // A PDF unless it was asked for as text: by its type, or, when the type is a general
        // one, by what it is called.
        val name = if (type == null || type == "application/octet-stream" || type == "*/*") {
            runCatching { PdfBook.nameOf(this, source) }.getOrDefault("")
        } else {
            ""
        }
        val asText = Texts.isTextMime(type) || (name.isNotEmpty() && Texts.canRead(name))
        val markdown = Texts.isMarkdownMime(type) || (name.isNotEmpty() && Texts.isMarkdown(name)) ||
            (asText && type == Texts.MIME && runCatching { Texts.isMarkdown(PdfBook.nameOf(this, source)) }.getOrDefault(false))
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                if (asText) {
                    TextScreen(source, null, markdown, onBack = ::finish, onHandOn = { handOn(uri, if (markdown) "text/markdown" else Texts.MIME) })
                } else {
                    PdfScreen(source, null, onBack = ::finish, onHandOn = { handOn(uri) })
                }
            }
        }
    }

    private fun handOn(uri: Uri, mime: String = Pdfs.MIME) {
        // A path cannot be passed to another app as it is (Android refuses to); it goes as a
        // content address, as a file from a folder does.
        val passed = if (uri.scheme == "file") uri.path?.let { Opener.uri(this, File(it)) } else uri
        if (passed != null) Opener.handOn(this, passed, mime)
        finish()
    }
}
