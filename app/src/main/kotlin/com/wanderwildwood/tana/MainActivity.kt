package com.wanderwildwood.tana

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.ui.AboutDialog
import com.wanderwildwood.tana.ui.AccessScreen
import com.wanderwildwood.tana.ui.BrowserViewModel
import com.wanderwildwood.tana.ui.ClashDialog
import com.wanderwildwood.tana.ui.FolderScreen
import com.wanderwildwood.tana.ui.HomeScreen
import com.wanderwildwood.tana.ui.InfoDialog
import com.wanderwildwood.tana.ui.Notice
import com.wanderwildwood.tana.ui.Place
import com.wanderwildwood.tana.ui.SearchScreen
import com.wanderwildwood.tana.ui.ServerScreen
import com.wanderwildwood.tana.ui.TroubleDialog
import com.wanderwildwood.tana.ui.describe
import com.wanderwildwood.tana.ui.monochrome
import com.wanderwildwood.tana.work.Transfers
import com.wanderwildwood.tana.work.Work

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Files(this)
            }
        }
    }
}

@Composable
private fun Files(activity: ComponentActivity) {
    // Asked again every time the app comes back, because the answer is given on another
    // app's page, and coming back from it is the moment it may have changed.
    var access by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = Environment.isExternalStorageManager()
        }
        lifecycleOwner.lifecycle.addObserver(watcher)
        onDispose { lifecycleOwner.lifecycle.removeObserver(watcher) }
    }

    if (!access) {
        AccessScreen {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.packageName),
            )
            runCatching { activity.startActivity(intent) }
                .onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        return
    }

    Browser(activity)
}

@Composable
private fun Browser(activity: ComponentActivity, vm: BrowserViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val work by Transfers.work.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Server?>(null) }
    var about by remember { mutableStateOf(false) }

    // Coming back to the app is when a file may have arrived from elsewhere: a download, a
    // sync, a card put in. The folder on screen is read again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (!first) vm.refresh()
                first = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(watcher)
        onDispose { lifecycleOwner.lifecycle.removeObserver(watcher) }
    }

    BackHandler {
        when {
            editing != null -> editing = null
            !vm.back() -> activity.finish()
        }
    }

    val server = editing
    val search = state.search
    when {
        server != null -> ServerScreen(server, vm) { editing = null }
        search != null -> SearchScreen(search, vm)
        state.place == Place.Home -> HomeScreen(
            state = state,
            work = work,
            vm = vm,
            onAddServer = { editing = Server(id = "", name = "", host = "", share = "") },
            onEditServer = { editing = it },
            onAbout = { about = true },
        )
        else -> FolderScreen(state, work, vm)
    }

    state.info?.let { InfoDialog(it, vm::hash, vm::closeInfo) }
    state.clashing?.let { ClashDialog(it, vm::answerClash) }
    (state.notice as? Notice.Problem)?.let { TroubleDialog(describe(context, it.problem), vm::noticeSeen) }
    (work as? Work.Failed)?.let { TroubleDialog(describe(context, it.problem), Transfers::seen) }
    if (about) AboutDialog { about = false }
}
