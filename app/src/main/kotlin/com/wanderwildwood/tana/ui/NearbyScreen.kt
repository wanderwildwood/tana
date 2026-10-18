package com.wanderwildwood.tana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.tana.R
import com.wanderwildwood.tana.nearby.Away
import com.wanderwildwood.tana.nearby.AwayWhy
import com.wanderwildwood.tana.nearby.Errand
import com.wanderwildwood.tana.nearby.Incoming
import com.wanderwildwood.tana.nearby.Nearby
import com.wanderwildwood.tana.nearby.Tap
import com.wanderwildwood.tana.nearby.Why
import com.wanderwildwood.tana.store.Entry
import com.wanderwildwood.tana.work.Volumes
import kotlinx.coroutines.delay

/**
 * Other devices on the same Wi-Fi running LocalSend. While this page is open the phone can be
 * found and sent to, and every offer asks first; closing it stops all of that. Opened with files
 * chosen, a press on a device sends them there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(sending: List<Entry>, vm: BrowserViewModel) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        Nearby.start(context, vm.nearbyName())
        onDispose { Nearby.stop() }
    }
    val state by Nearby.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    // A few seconds of "Looking" before saying none answered: answers take a moment to come.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(state.peers.isEmpty(), state.address) {
        waited = false
        delay(6000)
        waited = true
    }
    val count = sending.size
    val activity = context as? android.app.Activity
    val nfc = remember { android.nfc.NfcAdapter.getDefaultAdapter(context) }
    val hosting = state.away is Away.Hosting || state.away is Away.Starting
    // Joining by a tap: while this page is open and this phone is not the one with a network, it
    // listens for another held against it. A phone that listens cannot be read itself, so the
    // one with the network does not.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, hosting) {
        fun listen() {
            if (activity == null || nfc == null || hosting) return
            runCatching {
                nfc.enableReaderMode(
                    activity,
                    { tag -> Nearby.held(tag, Tap.readFrom(tag)) },
                    android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        fun quiet() {
            if (activity != null && nfc != null) runCatching { nfc.disableReaderMode(activity) }
        }
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> listen()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> quiet()
                else -> Unit
            }
        }
        if (lifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) listen()
        lifecycle.lifecycle.addObserver(watcher)
        onDispose {
            lifecycle.lifecycle.removeObserver(watcher)
            quiet()
        }
    }
    val locationGranted = {
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val askToStart = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { if (locationGranted()) Nearby.startNetwork() }
    val askToJoin = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { Nearby.permissionAnswered(locationGranted()) }
    val location = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    LaunchedEffect(state.wantsPermission) { if (state.wantsPermission) askToJoin.launch(location) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = if (count > 0) pluralStringResource(R.plurals.nearby_send_title, count, count) else stringResource(R.string.nearby_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), vm::closeNearby) },
            )
        },
        bottomBar = {
            Column {
                state.errand?.let { ErrandStrip(it, onOpenDownloads = { vm.closeNearby(); vm.go(Place.Folder(Volumes.downloads())) }) }
            }
        },
    ) { padding ->
        PagedColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)) {
            item {
                val line = when {
                    state.address == null && state.away is Away.Hosting -> stringResource(R.string.nearby_visible_as, state.alias)
                    state.address == null && state.away is Away.Joined -> stringResource(R.string.nearby_visible_as, state.alias)
                    state.address == null -> stringResource(R.string.nearby_no_wifi)
                    state.portTaken -> stringResource(R.string.nearby_port_taken, state.alias)
                    else -> stringResource(R.string.nearby_visible_as, state.alias)
                }
                Column(Modifier.fillMaxWidth().clickable { renaming = true }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextMMD(text = line, style = MaterialTheme.typography.bodySmall)
                    TextMMD(text = stringResource(R.string.nearby_visible_note), style = MaterialTheme.typography.labelSmall)
                }
            }
            item { Heading(stringResource(if (count > 0) R.string.nearby_section_send_to else R.string.nearby_section_devices)) }
            if (state.peers.isEmpty()) {
                item {
                    TextMMD(
                        text = stringResource(if (!waited) R.string.nearby_looking else R.string.nearby_none),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            items(state.peers, key = { it.fingerprint }) { peer ->
                PlaceRow(peer.alias, peer.note, onPress = { if (count > 0) Nearby.send(peer.fingerprint, sending) })
            }
            item { PlaceRow(stringResource(R.string.nearby_look_again), null, Nearby::lookAgain) }
            item {
                TextMMD(
                    text = stringResource(if (count > 0) R.string.nearby_send_note else R.string.nearby_receive_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            // Away from Wi-Fi: a network of the phone's own, joined by holding the phones together.
            item { Heading(stringResource(R.string.nearby_section_away)) }
            item {
                when (val away = state.away) {
                    Away.Starting -> Note(stringResource(R.string.nearby_away_starting))
                    is Away.Hosting -> {
                        Note(stringResource(if (away.up) R.string.nearby_away_hosting else R.string.nearby_away_restarting, away.network, away.passphrase))
                        PlaceRow(stringResource(R.string.nearby_away_stop), null, Nearby::stopNetwork)
                    }
                    is Away.Joining -> Note(away.to?.let { stringResource(R.string.nearby_away_joining, it) } ?: stringResource(R.string.nearby_away_waiting))
                    is Away.Joined -> {
                        Note(stringResource(R.string.nearby_away_joined, away.to))
                        PlaceRow(stringResource(R.string.nearby_away_leave), null, Nearby::stopNetwork)
                    }
                    else -> {
                        if (away is Away.Failed) {
                            Note(
                                stringResource(
                                    when (away.why) {
                                        AwayWhy.WIFI_OFF -> R.string.nearby_away_wifi_off
                                        AwayWhy.LOCATION_OFF -> R.string.nearby_away_location_off
                                        AwayWhy.UNSUPPORTED -> R.string.nearby_away_unsupported
                                        AwayWhy.BUSY -> R.string.nearby_away_busy
                                        AwayWhy.NO_ANSWER -> R.string.nearby_away_no_answer
                                        AwayWhy.OTHER -> R.string.nearby_away_other
                                    },
                                ),
                                bold = true,
                            )
                        }
                        PlaceRow(
                            stringResource(R.string.nearby_away_start),
                            stringResource(R.string.nearby_away_start_note),
                            { if (locationGranted()) Nearby.startNetwork() else askToStart.launch(location) },
                        )
                        Note(
                            stringResource(
                                when {
                                    nfc == null -> R.string.nearby_away_join_no_nfc
                                    !nfc.isEnabled -> R.string.nearby_away_join_nfc_off
                                    else -> R.string.nearby_away_join
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    state.incoming?.let { IncomingDialog(it) }
    (state.errand as? Errand.NeedsPin)?.let { PinDialog(it) }
    if (renaming) {
        NameDialog(stringResource(R.string.nearby_rename_title), stringResource(R.string.rename_confirm), state.alias, { typed ->
            val name = typed.trim().take(40)
            if (name.isNotEmpty()) {
                vm.setNearbyName(name)
                Nearby.rename(name)
            }
        }) { renaming = false }
    }
}

@Composable
private fun Note(text: String, bold: Boolean = false) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** How the send or receive is going, along the foot, in words and a percentage. */
@Composable
private fun ErrandStrip(errand: Errand, onOpenDownloads: () -> Unit) {
    val text = when (errand) {
        is Errand.Asking -> stringResource(R.string.nearby_asking, errand.to)
        is Errand.NeedsPin -> stringResource(R.string.nearby_asking, errand.to)
        is Errand.Sending -> stringResource(R.string.nearby_sending, minOf(errand.done + 1, errand.total), errand.total, errand.to, errand.percent)
        is Errand.Sent -> pluralStringResource(R.plurals.nearby_sent, errand.files, errand.files, errand.to)
        is Errand.Declined -> stringResource(R.string.nearby_declined, errand.by)
        is Errand.Receiving -> stringResource(R.string.nearby_receiving, minOf(errand.done + 1, errand.total), errand.total, errand.from, errand.percent)
        is Errand.Received -> pluralStringResource(R.plurals.nearby_received, errand.files, errand.files, errand.from) +
            if (errand.failed > 0) " " + pluralStringResource(R.plurals.nearby_not_whole, errand.failed, errand.failed) else ""
        is Errand.Failed -> stringResource(
            when (errand.why) {
                Why.UNREACHABLE -> R.string.nearby_failed_unreachable
                Why.BUSY -> R.string.nearby_failed_busy
                Why.STOPPED_THERE -> R.string.nearby_failed_stopped
                Why.NOT_READABLE -> R.string.nearby_failed_unreadable
                Why.OTHER -> R.string.nearby_failed_other
            },
            errand.who,
        )
    }
    // An ending stays until it is read and pressed away, or the next one starts; one that sent
    // nothing anywhere is not worth leaving up long.
    if (errand is Errand.Sent || errand is Errand.Declined) {
        LaunchedEffect(errand) {
            delay(8000)
            Nearby.errandSeen()
        }
    }
    HorizontalDividerMMD()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextMMD(text = text, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            if (errand.underWay) {
                FootButton(stringResource(R.string.work_stop), Modifier.weight(1f), Nearby::stopErrand)
            } else {
                FootButton(stringResource(R.string.close), Modifier.weight(1f), Nearby::errandSeen)
                if (errand is Errand.Received && errand.files > 0) {
                    Spacer(Modifier.width(10.dp))
                    FootButton(stringResource(R.string.nearby_open_downloads), Modifier.weight(1f), onOpenDownloads)
                }
            }
        }
    }
}

/** Someone offering files. Nothing arrives until Accept; Back or Decline is a no. */
@Composable
private fun IncomingDialog(incoming: Incoming) {
    val context = LocalContext.current
    EInkDialog(onDismiss = { Nearby.answer(false) }) {
        TextMMD(
            text = pluralStringResource(R.plurals.nearby_incoming_title, incoming.files, incoming.from, incoming.files, size(context, incoming.bytes)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = incoming.names.joinToString("\n") + if (incoming.files > incoming.names.size) "\n…" else "",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        TextMMD(
            text = stringResource(if (incoming.fits) R.string.nearby_incoming_where else R.string.nearby_incoming_no_room),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (incoming.fits) null else FontWeight.Bold,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = { Nearby.answer(false) }, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.nearby_decline), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButtonMMD(onClick = { Nearby.answer(true) }, enabled = incoming.fits, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.nearby_accept), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The other side wants its PIN before it will take anything. */
@Composable
private fun PinDialog(asked: Errand.NeedsPin) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val go = { if (pin.isNotBlank()) Nearby.pin(pin.trim()) }
    EInkDialog(onDismiss = Nearby::stopErrand) {
        TextMMD(
            text = stringResource(if (asked.wrong) R.string.nearby_pin_wrong else R.string.nearby_pin_title, asked.to),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(14.dp))
        TextFieldMMD(
            value = pin,
            onValueChange = { pin = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { go() }),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButtonMMD(onClick = Nearby::stopErrand, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButtonMMD(onClick = go, enabled = pin.isNotBlank(), modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.nearby_pin_send), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
