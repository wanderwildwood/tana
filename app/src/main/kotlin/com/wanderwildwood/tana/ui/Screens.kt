package com.wanderwildwood.tana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.store.Server
import com.wanderwildwood.tana.work.Kind
import com.wanderwildwood.tana.work.Volumes
import kotlinx.coroutines.launch

/** Everything along the foot of a screen, in the order it stacks. */
@Composable
private fun Foot(state: UiState, work: com.wanderwildwood.tana.work.Work, vm: BrowserViewModel, onMore: () -> Unit) {
    if (state.selecting) {
        SelectionBar(
            count = state.selection.size,
            onCopy = { vm.pickUp(com.wanderwildwood.tana.work.Mode.COPY) },
            onMove = { vm.pickUp(com.wanderwildwood.tana.work.Mode.MOVE) },
            onDelete = vm::delete,
            onMore = onMore,
        )
    } else {
        state.carry?.let { CarryStrip(it, canPaste = state.folder != null, onPaste = vm::paste, onCancel = vm::putDown) }
    }
    WorkStrip(work)
    when (val n = state.notice) {
        null, is Notice.Problem -> Unit
        Notice.Busy -> NoticeStrip(stringResource(R.string.notice_busy), vm::noticeSeen)
        Notice.NothingOpens -> NoticeStrip(stringResource(R.string.notice_nothing_opens), vm::noticeSeen)
        Notice.FoldersNotShared -> NoticeStrip(stringResource(R.string.notice_folders_not_shared), vm::noticeSeen)
    }
}

/**
 * Where the app opens: the places worth going to, rather than the top of a tree. What is on
 * the phone, what the reader pinned, and the servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    work: com.wanderwildwood.tana.work.Work,
    vm: BrowserViewModel,
    onAddServer: () -> Unit,
    onEditServer: (Server) -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = {
                    BarButton(Icons.Search, stringResource(R.string.cd_search), vm::openSearch)
                    BarButton(Icons.Info, stringResource(R.string.cd_info), onAbout)
                },
            )
        },
        bottomBar = { Column { Foot(state, work, vm) {} } },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            item { PlaceRow(stringResource(R.string.home_recent), stringResource(R.string.home_recent_note), { vm.go(Place.Recent) }) }
            item { PlaceRow(stringResource(R.string.home_downloads), null, { vm.go(Place.Folder(Volumes.downloads())) }) }

            item { Heading(stringResource(R.string.home_section_phone)) }
            items(state.volumes, key = { "v:" + it.loc.path }) { volume ->
                PlaceRow(
                    volume.label,
                    if (volume.total > 0) stringResource(R.string.home_free_of, size(context, volume.free), size(context, volume.total)) else null,
                    { vm.go(Place.Folder(volume.loc)) },
                )
            }

            if (state.pins.isNotEmpty()) {
                item { Heading(stringResource(R.string.home_section_pinned)) }
                items(state.pins, key = { "p:" + it.loc.store + it.loc.path }) { pin ->
                    // A long press arms it; a tap while armed unpins, and it disarms itself.
                    var armed by remember(pin) { mutableStateOf(false) }
                    LaunchedEffect(armed) {
                        if (armed) {
                            kotlinx.coroutines.delay(4000)
                            armed = false
                        }
                    }
                    val server = state.servers.firstOrNull { it.storeId == pin.loc.store }
                    PlaceRow(
                        if (armed) stringResource(R.string.home_unpin_armed) else pin.label,
                        server?.name ?: vm.rootOf(pin.loc).second,
                        onPress = { if (armed) vm.unpin(pin) else vm.go(Place.Folder(pin.loc)) },
                        onLongPress = { armed = true },
                    )
                }
            }

            item { Heading(stringResource(R.string.home_section_servers)) }
            items(state.servers, key = { "s:" + it.id }) { server ->
                PlaceRow(
                    server.name,
                    "${server.host} / ${server.share}",
                    onPress = { vm.go(Place.Folder(com.wanderwildwood.tana.store.Loc(server.storeId, ""))) },
                    onLongPress = { onEditServer(server) },
                )
            }
            item { PlaceRow(stringResource(R.string.home_add_server), null, onAddServer) }
        }
    }
}

/** A folder, on the phone or on a server — or the list of recent files, which reads the same. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(state: UiState, work: com.wanderwildwood.tana.work.Work, vm: BrowserViewModel) {
    val context = LocalContext.current
    var more by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.wanderwildwood.tana.store.Entry?>(null) }

    val folder = state.folder
    val (root, rootLabel) = folder?.let { vm.rootOf(it) } ?: (null to "")
    val title = when {
        state.selecting -> pluralStringResource(R.plurals.selected, state.selection.size, state.selection.size)
        state.place == Place.Recent -> stringResource(R.string.home_recent)
        folder != null && root != null && folder.path == root.path -> rootLabel
        else -> folder?.name.orEmpty()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (state.selecting) {
                        BarButton(Icons.Close, stringResource(R.string.cd_clear_selection), vm::clearSelection)
                    } else {
                        BarButton(Icons.Back, stringResource(R.string.cd_back)) { vm.back() }
                    }
                },
                actions = {
                    if (state.selecting) {
                        TextMMD(
                            text = stringResource(R.string.select_all),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable(onClick = vm::selectAll).padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    } else {
                        BarButton(Icons.Search, stringResource(R.string.cd_search), vm::openSearch)
                        if (folder != null) BarButton(Icons.NewFolder, stringResource(R.string.cd_new_folder)) { naming = true }
                        if (state.place != Place.Recent) BarButton(Icons.Sort, stringResource(R.string.cd_sort)) { sorting = true }
                    }
                },
            )
        },
        bottomBar = { Column { Foot(state, work, vm) { more = true } } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (folder != null && root != null) PathLine(root, rootLabel, folder, onJump = { vm.go(Place.Folder(it)) })
            Box(Modifier.weight(1f)) {
                when {
                    state.reading == Reading.FAILED -> Column(Modifier.padding(16.dp)) {
                        TextMMD(text = describe(context, state.failure ?: Exception()), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButtonMMD(onClick = vm::refresh, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            TextMMD(text = stringResource(R.string.folder_try_again), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // A word rather than a spinner: nothing on this panel animates. And a word
                    // rather than "empty", which during the second a server takes to answer
                    // would say the reader's files were gone.
                    state.reading == Reading.NOT_YET -> TextMMD(
                        text = stringResource(R.string.folder_reading),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    state.entries.isEmpty() -> TextMMD(
                        text = stringResource(R.string.folder_empty),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> LazyColumnMMD(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        items(state.entries, key = { it.loc.path }) { entry ->
                            EntryRow(
                                entry = entry,
                                selecting = state.selecting,
                                selected = entry.loc in state.selection,
                                showFolder = if (state.place == Place.Recent) entry.loc.parent?.name else null,
                                onPress = { vm.press(entry) },
                                onLongPress = { vm.toggle(entry) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (more) {
        val chosen = state.selected()
        val one = chosen.singleOrNull()
        MoreDialog(
            chosen = chosen,
            onShare = { more = false; vm.share() },
            onOpenWith = { more = false; one?.let { vm.open(it, com.wanderwildwood.tana.work.Purpose.OPEN_WITH) }; vm.clearSelection() },
            onRename = { more = false; renaming = one },
            onInfo = { more = false; one?.let(vm::info) },
            onPin = { more = false; one?.let(vm::pin) },
            onDismiss = { more = false },
        )
    }
    if (sorting) SortDialog(state.sort, state.showHidden, vm::sortBy, vm::toggleHidden) { sorting = false }
    if (naming) {
        NameDialog(stringResource(R.string.new_folder_title), stringResource(R.string.new_folder_confirm), "", vm::newFolder) { naming = false }
    }
    renaming?.let { entry ->
        NameDialog(stringResource(R.string.rename_title), stringResource(R.string.rename_confirm), entry.name, { vm.rename(entry, it) }) { renaming = null }
    }
}

/**
 * Finding by name below where you were. The kinds are the ones a person looks for on a
 * phone; results arrive as they are found, a batch per folder, and a press opens one.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(search: SearchState, vm: BrowserViewModel) {
    var words by remember { mutableStateOf(search.words) }
    var kind by remember { mutableStateOf(search.kind) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (!search.ran) focus.requestFocus() }
    val go = {
        focusManager.clearFocus()
        vm.searchFor(words, kind)
    }
    KeyboardFirst()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.search_within, search.within), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), vm::closeSearch) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            TextFieldMMD(
                value = words,
                onValueChange = { words = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                singleLine = true,
                placeholder = { TextMMD(text = stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
            )
            // The kinds as a line of words that wraps; the chosen one in bold, like the rest
            // of this app says "chosen".
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Kind.entries.forEach { k ->
                    TextMMD(
                        text = stringResource(kindLabel(k)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (k == kind) FontWeight.Bold else null,
                        textDecoration = if (k == kind) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                        modifier = Modifier.clickable {
                            kind = k
                            if (search.ran || words.isNotBlank()) vm.searchFor(words, k)
                        }.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }
            HorizontalDividerMMD()
            Box(Modifier.weight(1f)) {
                when {
                    search.results.isEmpty() && search.running -> Status(stringResource(R.string.search_running))
                    search.results.isEmpty() && search.ran -> Status(stringResource(R.string.search_none))
                    search.results.isEmpty() -> OutlinedButtonMMD(
                        onClick = go,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
                    ) { TextMMD(text = stringResource(R.string.search_go), style = MaterialTheme.typography.bodySmall) }
                    else -> LazyColumnMMD(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        items(search.results, key = { it.loc.store + it.loc.path }) { entry ->
                            EntryRow(
                                entry = entry,
                                selecting = false,
                                selected = false,
                                showFolder = entry.loc.parent?.name?.ifEmpty { vm.rootOf(entry.loc).second },
                                onPress = { vm.pressResult(entry) },
                                onLongPress = { vm.pressResult(entry) },
                            )
                        }
                        if (search.running) item { Status(stringResource(R.string.search_running)) }
                    }
                }
            }
        }
    }
}

/**
 * Back with the keyboard up puts the keyboard away, and does nothing else. Without this it
 * closed the whole screen, and what had been typed into it went with it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeyboardFirst() {
    val focusManager = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val up = WindowInsets.isImeVisible
    androidx.activity.compose.BackHandler(enabled = up) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
}

@Composable
private fun Status(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
}

private fun kindLabel(k: Kind) = when (k) {
    Kind.ANY -> R.string.kind_any
    Kind.DOCUMENTS -> R.string.kind_documents
    Kind.IMAGES -> R.string.kind_images
    Kind.AUDIO -> R.string.kind_audio
    Kind.VIDEO -> R.string.kind_video
    Kind.ARCHIVES -> R.string.kind_archives
    Kind.APPS -> R.string.kind_apps
}

/**
 * Adding or changing a server. A screen rather than a dialog: five fields and a keyboard do
 * not fit in a dialog on a 480 by 800 panel, and a list is what this shop uses where a
 * dialog has outgrown the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(initial: Server, vm: BrowserViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var share by remember { mutableStateOf(initial.share) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val editing = initial.id.isNotEmpty()
    val (armed, pressRemove) = rememberArmed(initial.id) {
        vm.removeServer(initial)
        onDone()
    }
    val ready = host.isNotBlank() && share.isNotBlank() && !checking
    KeyboardFirst()

    val save = {
        if (ready) {
            checking = true
            problem = null
            scope.launch {
                val draft = initial.copy(
                    name = name.trim().ifEmpty { "${host.trim()} / ${share.trim()}" },
                    host = host.trim(),
                    share = share.trim().trim('/', '\\'),
                    user = user.trim(),
                    password = password,
                )
                val failed = vm.saveServer(draft)
                checking = false
                if (failed == null) onDone() else problem = describe(context, failed)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(if (editing) R.string.server_edit_title else R.string.server_add_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cancel), onDone) },
                // In the bar, not at the foot of the form: with the keyboard up the foot of
                // the form is under it, and a button you cannot see is one you cannot press.
                actions = {
                    TextMMD(
                        text = stringResource(if (checking) R.string.server_checking_short else R.string.server_save),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (ready) FontWeight.Bold else null,
                        modifier = Modifier.clickable(enabled = ready, onClick = save).padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).imePadding().background(MaterialTheme.colorScheme.surface)) {
            // First, where it will be seen: the answer to Save is read from the top of the
            // screen, next to the word that was pressed.
            problem?.let {
                item {
                    TextMMD(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDividerMMD()
                }
            }
            item { Field(stringResource(R.string.server_name), stringResource(R.string.server_name_hint), name, { name = it }) }
            item { Field(stringResource(R.string.server_host), stringResource(R.string.server_host_hint), host, { host = it }, keyboard = KeyboardType.Uri) }
            item { Field(stringResource(R.string.server_share), stringResource(R.string.server_share_hint), share, { share = it }) }
            item { Field(stringResource(R.string.server_user), stringResource(R.string.server_user_hint), user, { user = it }) }
            item { Field(stringResource(R.string.server_password), "", password, { password = it }, secret = true) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    TextMMD(
                        text = stringResource(if (checking) R.string.server_checking else R.string.server_note),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (editing) {
                item {
                    HorizontalDividerMMD(Modifier.padding(top = 10.dp))
                    PlaceRow(stringResource(if (armed) R.string.server_remove_armed else R.string.server_remove), null, pressRemove)
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        TextFieldMMD(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = if (hint.isEmpty()) null else { { TextMMD(text = hint, style = MaterialTheme.typography.labelSmall) } },
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboard,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
        )
    }
}

/** Before Android has been told this app may see storage. One page, one button. */
@Composable
fun AccessScreen(onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        TextMMD(text = stringResource(R.string.access_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        TextMMD(text = stringResource(R.string.access_body), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(20.dp))
        OutlinedButtonMMD(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.access_open), style = MaterialTheme.typography.bodySmall)
        }
    }
}
