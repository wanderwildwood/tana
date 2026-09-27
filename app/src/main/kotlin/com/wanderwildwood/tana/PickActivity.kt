package com.wanderwildwood.tana

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.tana.ui.monochrome
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.Picking
import java.io.File

/**
 * The browser, opened by another app to choose a file (`ACTION_GET_CONTENT`): an attachment
 * in Messaging, an upload anywhere. Choosing hands the file back as a content address with
 * a read grant, the way [Opener] hands one on; a file on a server is fetched first. Back
 * past the top answers nothing, which the asking app reads as "cancelled".
 */
class PickActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picking = Picking.from(intent) ?: run { finish(); return }
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Files(this, null, {}, picking) { files -> answer(files) }
            }
        }
    }

    private fun answer(files: List<File>) {
        val uris = files.map { Opener.uri(this, it) }
        if (uris.isEmpty()) return
        val result = Intent()
            .setData(uris.first())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Several ride on ClipData, which is also where the grant for each one travels.
        result.clipData = ClipData.newRawUri(null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
