package com.wanderwildwood.tana.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.tana.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which app answers when another app asks to show a folder or the downloads. */
sealed interface Opener {
    data object Us : Opener

    /** No app is set to always: Android asks, and this is the question to put to it. */
    data class Asks(val ask: Intent) : Opener

    /**
     * Another app was chosen with Always. Undoing it is on that app's "Open by default" page,
     * which Mudita's settings leave out but Android still opens when asked.
     */
    data class Other(val label: String, val pkg: String) : Opener
}

/**
 * The requests a file manager is chosen for, each remembered by Android on its own: a folder
 * (Downloads, by the address Android's picker gives it), the downloads, and "the files app".
 */
private fun asks(): List<Intent> = listOf(
    Intent(Intent.ACTION_VIEW).setDataAndType(
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
        "vnd.android.document/directory",
    ),
    Intent("android.intent.action.VIEW_DOWNLOADS"),
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES),
)

/** Android's "Open by default" page for [pkg], where "Clear default preferences" is. */
private fun openByDefault(pkg: String) =
    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg"))

/**
 * Who answers each request sent without a chooser. Android hands back its own "which app?"
 * page when none is set; that page is not among the apps that can answer, which is how it is
 * told apart on any version, whatever the page's package is called.
 */
private fun opener(context: Context): Opener {
    val pm = context.packageManager
    var asking: Intent? = null
    for (intent in asks()) {
        val chosen = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: continue
        val able = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
        when {
            chosen.packageName == context.packageName -> Unit
            able.none { it.packageName == chosen.packageName && it.name == chosen.name } -> if (asking == null) asking = intent
            else -> return Opener.Other(chosen.loadLabel(pm).toString(), chosen.packageName)
        }
    }
    return asking?.let { Opener.Asks(it) } ?: Opener.Us
}

/**
 * "Opens folders and downloads", read again each time the app comes back — the answer is
 * given on Android's page, and returning from it is when it may have changed.
 */
@Composable
internal fun OpensRow() {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val watcher = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) round++ }
        lifecycleOwner.lifecycle.addObserver(watcher)
        onDispose { lifecycleOwner.lifecycle.removeObserver(watcher) }
    }
    val opener by produceState<Opener?>(null, round) {
        value = withContext(Dispatchers.IO) { opener(context) }
    }
    var explain by remember { mutableStateOf<Opener.Other?>(null) }

    val shown = opener ?: return
    Column {
        Heading(stringResource(R.string.home_section_opens))
        when (shown) {
            Opener.Us -> PlaceRow(stringResource(R.string.app_name), null, {})
            is Opener.Asks -> PlaceRow(stringResource(R.string.opens_asks), stringResource(R.string.opens_asks_note), {
                runCatching { context.startActivity(shown.ask) }
            })
            is Opener.Other -> PlaceRow(shown.label, stringResource(R.string.opens_other_note_short), { explain = shown })
        }
    }

    explain?.let { other ->
        EInkDialog(onDismiss = { explain = null }) {
            TextMMD(text = stringResource(R.string.home_section_opens), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.opens_other_note, other.label), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            OutlinedButtonMMD(
                onClick = {
                    explain = null
                    runCatching { context.startActivity(openByDefault(other.pkg)) }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                TextMMD(text = stringResource(R.string.opens_other_open), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButtonMMD(onClick = { explain = null }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.close), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
