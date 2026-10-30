package com.wanderwildwood.tana

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.tana.ui.monochrome
import com.wanderwildwood.tana.work.Ask
import com.wanderwildwood.tana.work.Opener
import com.wanderwildwood.tana.work.PathGuard
import com.wanderwildwood.tana.work.Picking
import java.io.File

/**
 * The browser, opened by another app to choose a file (`GET_CONTENT`, `OPEN_DOCUMENT`), choose
 * several, choose a folder (`OPEN_DOCUMENT_TREE`) or name a new file (`CREATE_DOCUMENT`): an
 * attachment in Messaging, an upload anywhere, a "Save as". The answer is a content address
 * with the grant the asking app wanted (see [Picking.grantFlags]); a file on a server is
 * fetched first and handed back read-only. Back past the top answers nothing, which the
 * asking app reads as "cancelled".
 */
class PickActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picking = Picking.from(intent) ?: run { finish(); return }
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                    Files(this, null, {}, picking) { files -> answer(picking, files) }
                }
            }
        }
    }

    private fun answer(picking: Picking, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { uriFor(picking, it) }
        val result = Intent().setData(uris.first()).addFlags(picking.grantFlags())
        // Several ride on ClipData, which is also where the grant for each one travels.
        result.clipData = ClipData.newRawUri(null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun uriFor(picking: Picking, file: File): Uri {
        val inStorage = PathGuard.resolve(file.absolutePath, storageRoots()) != null
        // A copy fetched from a server, or anything outside shared storage, goes by the
        // read-only file address it always has.
        if (!inStorage) return Opener.uri(this, file)
        val id = file.canonicalPath
        return if (picking.ask == Ask.TREE) DocumentsContract.buildTreeDocumentUri(authority(), id)
        else DocumentsContract.buildDocumentUri(authority(), id)
    }

    private fun authority() = "$packageName.picked"

    private fun storageRoots(): List<String> {
        val volumes = getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory?.path }
        return (listOf(Environment.getExternalStorageDirectory().path) + volumes).distinct()
    }
}
