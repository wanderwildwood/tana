package com.wanderwildwood.tana

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.tana.ui.PdfScreen
import com.wanderwildwood.tana.ui.monochrome
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.PdfSource
import java.io.File

/**
 * A PDF another app handed over to be opened (`ACTION_VIEW`): an email's attachment, a
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
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                PdfScreen(PdfSource.OfUri(uri), null, onBack = ::finish, onHandOn = { handOn(uri) })
            }
        }
    }

    private fun handOn(uri: Uri) {
        // A path cannot be passed to another app as it is (Android refuses to); it goes as a
        // content address, as a file from a folder does.
        val passed = if (uri.scheme == "file") uri.path?.let { Opener.uri(this, File(it)) } else uri
        if (passed != null) Opener.handOn(this, passed)
        finish()
    }
}
