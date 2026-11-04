package com.ars3nb.volta.ui.share

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.link.ImportDetector.Kind
import com.ars3nb.volta.core.share.ShareFailure
import com.ars3nb.volta.core.share.ShareHandshake
import com.ars3nb.volta.core.share.SharePayload
import com.ars3nb.volta.core.share.SharePeer
import com.ars3nb.volta.core.share.ShareState
import com.ars3nb.volta.core.share.ble.BleReceiver
import com.ars3nb.volta.core.share.ble.BleSender
import com.ars3nb.volta.core.share.ble.BleSession
import com.ars3nb.volta.core.share.ble.BleSupport
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.IconBubble
import com.ars3nb.volta.ui.components.LinkRow
import com.ars3nb.volta.ui.components.RowGap
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.LocalTv
import com.ars3nb.volta.ui.tv.Tv
import com.ars3nb.volta.ui.tv.focusRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Sending a link to a device nearby over Bluetooth, and receiving one (see
 * core/share/ShareProtocol). One bottom sheet for both sides: first whatever is in the
 * way (permission, Bluetooth off, location off on old Android), then the session's own
 * states. Every button takes the remote's focus as its state comes up, so on a TV the
 * whole thing is a few presses of OK.
 */

/** The device has Bluetooth LE; entries to the feature are shown only then. */
@Composable
fun rememberBleShareAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { BleSupport.hasBle(context) }
}

/** «Отправить по Bluetooth»: [payload] is the text the receiver will import. */
@Composable
fun BleSendSheet(payload: String, onDismiss: () -> Unit) =
    BleShareSheet(sender = true, payload = payload, onAdd = {}, onDismiss = onDismiss)

/** «Получить по Bluetooth»: [onAdd] gets the received text, for the ordinary import. */
@Composable
fun BleReceiveSheet(onAdd: (String) -> Unit, onDismiss: () -> Unit) =
    BleShareSheet(sender = false, payload = null, onAdd = onAdd, onDismiss = onDismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleShareSheet(
    sender: Boolean,
    payload: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // What is in the way, looked at again whenever something may have changed it.
    var tick by remember { mutableIntStateOf(0) }
    var askedPermission by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Bluetooth switched from the quick settings while the sheet is open.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                tick++
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val scans = remember(tick) { BleSupport.scans(context, sender) }
    val permissions = remember(tick) { BleSupport.runtimePermissions(Build.VERSION.SDK_INT, scans) }
    val gate = remember(tick) {
        BleSupport.gate(
            sdk = Build.VERSION.SDK_INT,
            hasBle = BleSupport.hasBle(context),
            missingPermissions = BleSupport.missing(context, scans).isNotEmpty(),
            bluetoothOn = BleSupport.isOn(context),
            scans = scans,
            locationOn = BleSupport.locationOn(context)
        )
    }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedPermission = true
        tick++
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // A session exists only while nothing is in the way; «Ещё раз» makes a new one.
    var attempt by remember { mutableIntStateOf(0) }
    val ready = gate == BleSupport.Gate.READY
    val session: BleSession? = remember(ready, attempt) {
        when {
            !ready -> null
            sender -> BleSender(context, payload.orEmpty())
            else -> BleReceiver(context)
        }
    }
    DisposableEffect(session) {
        session?.start()
        onDispose { session?.close() }
    }
    val flow: StateFlow<ShareState> = remember(session) { session?.state ?: MutableStateFlow(ShareState.Starting) }
    val state by flow.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Bluetooth, scheme.primary, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (sender) R.string.share_send_action else R.string.share_receive_action),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Hint(stringResource(R.string.share_sheet_note))
                }
            }
            Spacer(Modifier.height(20.dp))

            val screen: Any = if (ready) state else gate
            AnimatedContent(
                targetState = screen,
                contentKey = { if (it is Enum<*>) it else it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "bleShare"
            ) { s ->
                when (s) {
                    BleSupport.Gate.NO_BLE -> Message(
                        Icons.Rounded.BluetoothDisabled, scheme.onSurfaceVariant,
                        stringResource(R.string.share_no_ble), null,
                        stringResource(R.string.share_close), onAction = onDismiss
                    )
                    BleSupport.Gate.PERMISSION -> {
                        val activity = context.findActivity()
                        val blocked = askedPermission && activity != null &&
                            permissions.none { activity.shouldShowRequestPermissionRationale(it) }
                        Message(
                            Icons.Rounded.VerifiedUser, scheme.primary,
                            stringResource(R.string.share_perm_title),
                            stringResource(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.share_perm_text
                                else R.string.share_perm_location_text
                            ),
                            stringResource(if (blocked) R.string.share_perm_settings else R.string.share_perm_allow)
                        ) {
                            if (blocked) {
                                Tv.tryStart(
                                    context,
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                )
                            } else {
                                askPermissions.launch(permissions.toTypedArray())
                            }
                        }
                    }
                    BleSupport.Gate.BLUETOOTH_OFF -> Message(
                        Icons.Rounded.BluetoothDisabled, scheme.primary,
                        stringResource(R.string.share_bt_off_title),
                        stringResource(R.string.share_bt_off_text),
                        stringResource(R.string.share_bt_turn_on)
                    ) {
                        val asked = runCatching {
                            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }.isSuccess
                        // Some TV boxes have no such dialog: their Bluetooth settings instead.
                        if (!asked) Tv.tryStart(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    BleSupport.Gate.LOCATION_OFF -> Message(
                        Icons.Rounded.LocationOff, scheme.primary,
                        stringResource(R.string.share_location_off_title),
                        stringResource(R.string.share_location_off_text),
                        stringResource(R.string.share_location_open)
                    ) { Tv.tryStart(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    BleSupport.Gate.READY, ShareState.Starting -> Searching(sender, null, onRescan = {})
                    is ShareState.Searching -> SearchingBody(
                        s,
                        onSelect = { peer ->
                            (session as? BleSender)?.select(peer)
                            (session as? BleReceiver)?.select(peer)
                        },
                        onRescan = {
                            (session as? BleSender)?.rescan()
                            (session as? BleReceiver)?.rescan()
                        }
                    )
                    is ShareState.Waiting -> CodeBody(
                        // No code until a sender has done the exchange: it depends on both sides.
                        code = s.code,
                        title = stringResource(R.string.share_waiting_title),
                        text = if (s.peer == null) stringResource(R.string.share_waiting_text)
                        else stringResource(R.string.share_code_connected, label(s.peer)),
                        hint = stringResource(R.string.share_code_hint),
                        pulse = true
                    )
                    is ShareState.Connecting -> Progress(stringResource(R.string.share_connecting, label(s.peer)), null)
                    is ShareState.Confirm -> ConfirmBody(
                        s,
                        onAnswer = { match -> (session as? BleSender)?.confirm(match) }
                    )
                    is ShareState.Transferring -> Progress(
                        stringResource(if (s.sending) R.string.share_sending else R.string.share_receiving),
                        s.progress
                    )
                    is ShareState.Sent -> Message(
                        Icons.Rounded.CheckCircle, VoltaTheme.palette.good,
                        stringResource(R.string.share_sent_title),
                        stringResource(R.string.share_sent_text, label(s.peer)),
                        stringResource(R.string.share_close), onAction = onDismiss
                    )
                    is ShareState.Received -> ReceivedBody(
                        s,
                        onAdd = {
                            onAdd(s.text)
                            onDismiss()
                        },
                        onDiscard = onDismiss
                    )
                    is ShareState.Failed -> FailedBody(
                        s.reason,
                        onRetry = { attempt++ },
                        onClose = onDismiss
                    )
                    else -> Unit
                }
            }
        }
    }
}

// ------------------------------------------------------------------- bodies

/** A state with one thing to do: badge, title, text, one button (focused on a TV). */
@Composable
private fun Message(
    icon: ImageVector,
    tint: Color,
    title: String,
    text: String?,
    action: String,
    secondary: Pair<String, () -> Unit>? = null,
    onAction: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(icon, tint, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(action, onAction)
        if (secondary != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(secondary.first, secondary.second)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val focus = remember { FocusRequester() }
    TvFocus(focus)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(focus)
            .focusRing(CircleShape, color = MaterialTheme.colorScheme.onBackground)
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRing(CircleShape)
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

/** On a TV, puts the remote's focus on [requester] when it comes on screen. */
@Composable
private fun TvFocus(requester: FocusRequester) {
    if (!LocalTv.current) return
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** The Bluetooth badge with a ring going out from it: looking, or waiting to be found. */
@Composable
private fun PulsingBluetooth() {
    val primary = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "btPulse")
    val scale by t.animateFloat(1f, 1.9f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "btScale")
    val alpha by t.animateFloat(0.35f, 0f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "btAlpha")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .background(primary)
        )
        IconBubble(Icons.Rounded.Bluetooth, primary, size = 56.dp)
    }
}

@Composable
private fun Searching(sender: Boolean, finishedEmpty: Boolean?, onRescan: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (finishedEmpty == true) {
            IconBubble(Icons.Rounded.Bluetooth, MaterialTheme.colorScheme.onSurfaceVariant, size = 56.dp, active = false)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.share_nothing_found), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.share_nothing_found_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.share_search_again), onRescan)
        } else {
            PulsingBluetooth()
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.share_searching), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (sender) R.string.share_searching_send_hint else R.string.share_searching_receive_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SearchingBody(s: ShareState.Searching, onSelect: (SharePeer) -> Unit, onRescan: () -> Unit) {
    val sender = !s.asReceiver
    if (s.peers.isEmpty()) {
        Searching(sender, finishedEmpty = s.finished, onRescan = onRescan)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (s.finished) R.string.share_found else R.string.share_searching),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Hint(stringResource(if (sender) R.string.share_pick_receiver else R.string.share_pick_sender))
        Spacer(Modifier.height(12.dp))
        val first = remember { FocusRequester() }
        TvFocus(first)
        VoltaCard(contentPadding = 8) {
            Column(verticalArrangement = RowGap) {
                s.peers.forEachIndexed { i, peer ->
                    LinkRow(
                        icon = Icons.Rounded.Devices,
                        tint = MaterialTheme.colorScheme.primary,
                        title = label(peer),
                        subtitle = null,
                        onClick = { onSelect(peer) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
        }
        if (s.finished) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.share_search_again), onRescan)
        }
    }
}

/** The 4-digit code, big, in tabular figures so both screens look the same. */
@Composable
private fun Code(code: String) {
    Text(
        // Six digits in two groups of three ("123 456"), as Bluetooth pairing shows them.
        ShareHandshake.grouped(code),
        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TABULAR),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 2.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CodeBody(code: String?, title: String, text: String, hint: String, pulse: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pulse) PulsingBluetooth()
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (code != null) {
            Spacer(Modifier.height(18.dp))
            VoltaCard(Modifier, contentPadding = 18) {
                Hint(stringResource(R.string.share_code_label), Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Code(code)
            }
            Spacer(Modifier.height(10.dp))
            Hint(hint)
        }
    }
}

@Composable
private fun ConfirmBody(s: ShareState.Confirm, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(Icons.Rounded.BluetoothConnected, MaterialTheme.colorScheme.primary, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.share_confirm_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.share_confirm_text, label(s.peer)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        VoltaCard(contentPadding = 18) { Code(s.code) }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.share_match), { onAnswer(true) })
        Spacer(Modifier.height(8.dp))
        SecondaryButton(stringResource(R.string.share_mismatch)) { onAnswer(false) }
    }
}

@Composable
private fun Progress(text: String, progress: Float?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PulsingBluetooth()
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        val bar = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        if (progress == null) {
            LinearProgressIndicator(bar, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = bar,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

/** What arrived, said in the words of the «Добавить» screen; a hidden address is never shown. */
@Composable
private fun ReceivedBody(s: ShareState.Received, onAdd: () -> Unit, onDiscard: () -> Unit) {
    val preview = remember(s) { SharePayload.preview(s.text) }
    val (icon, title) = kindLook(preview.kind, preview.hidden)
    val addable = preview.kind != Kind.Empty && preview.kind != Kind.Unknown && preview.kind != Kind.Control
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.share_received_title, label(s.peer)),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        VoltaCard(contentPadding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon, if (addable) MaterialTheme.colorScheme.primary else VoltaTheme.palette.poor)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(preview.name.ifBlank { title }, style = MaterialTheme.typography.titleSmall)
                    if (preview.name.isNotBlank()) Hint(title)
                }
            }
            val detail = when {
                !addable -> stringResource(R.string.share_received_cannot)
                preview.hidden -> stringResource(R.string.share_received_hidden)
                else -> preview.excerpt
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (preview.hidden) {
                        Icon(
                            Icons.Rounded.Lock, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Hint(detail, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (addable) {
            PrimaryButton(stringResource(R.string.share_received_add), onAdd)
            Spacer(Modifier.height(8.dp))
            SecondaryButton(stringResource(R.string.share_received_discard), onDiscard)
        } else {
            PrimaryButton(stringResource(R.string.share_close), onDiscard)
        }
    }
}

@Composable
private fun kindLook(kind: Kind, hidden: Boolean): Pair<ImageVector, String> = when (kind) {
    is Kind.Subscription -> if (hidden || kind.hidden) {
        Icons.Rounded.Lock to stringResource(R.string.settings_import_hidden_sub)
    } else {
        Icons.Rounded.CloudSync to stringResource(R.string.settings_import_sub)
    }
    Kind.Encrypted -> Icons.Rounded.EnhancedEncryption to stringResource(R.string.settings_import_encrypted)
    is Kind.Server -> Icons.Rounded.Storage to stringResource(R.string.settings_import_server)
    is Kind.Servers -> Icons.Rounded.Storage to pluralStringResource(R.plurals.settings_import_servers, kind.count, kind.count)
    Kind.AmneziaKey -> Icons.Rounded.Key to stringResource(R.string.settings_import_amnezia)
    Kind.Conf -> Icons.Rounded.Description to stringResource(R.string.settings_import_conf)
    Kind.Json -> Icons.Rounded.DataObject to stringResource(R.string.settings_import_json)
    Kind.Control, Kind.Unknown, Kind.Empty -> Icons.Rounded.QuestionMark to stringResource(R.string.settings_import_unknown)
}

@Composable
private fun FailedBody(reason: ShareFailure, onRetry: () -> Unit, onClose: () -> Unit) {
    val title = when (reason) {
        ShareFailure.MISMATCH, ShareFailure.COMMITMENT -> R.string.share_mismatch_title
        ShareFailure.EXPIRED -> R.string.share_expired_title
        else -> R.string.share_error_title
    }
    val text = when (reason) {
        ShareFailure.BLUETOOTH_OFF -> R.string.share_error_bt_off
        ShareFailure.PERMISSION -> R.string.share_error_permission
        ShareFailure.ADVERTISE -> R.string.share_error_advertise
        ShareFailure.SCAN -> R.string.share_error_scan
        ShareFailure.CONNECT -> R.string.share_error_connect
        ShareFailure.NOT_VOLTA -> R.string.share_error_not_volta
        ShareFailure.MISMATCH -> R.string.share_error_mismatch
        ShareFailure.COMMITMENT -> R.string.share_error_commitment
        ShareFailure.DAMAGED -> R.string.share_error_damaged
        ShareFailure.TOO_LARGE -> R.string.share_error_too_large
        ShareFailure.REJECTED -> R.string.share_error_rejected
        ShareFailure.TIMEOUT -> R.string.share_error_timeout
        ShareFailure.EXPIRED -> R.string.share_error_expired
    }
    val retry = reason != ShareFailure.TOO_LARGE
    Message(
        icon = if (reason == ShareFailure.MISMATCH) Icons.Rounded.VerifiedUser else Icons.Rounded.ErrorOutline,
        tint = if (reason == ShareFailure.MISMATCH) MaterialTheme.colorScheme.primary else VoltaTheme.palette.poor,
        title = stringResource(title),
        text = stringResource(text),
        action = stringResource(if (retry) R.string.share_try_again else R.string.share_close),
        secondary = if (retry) stringResource(R.string.share_close) to onClose else null,
        onAction = if (retry) onRetry else onClose
    )
}

@Composable
private fun label(peer: SharePeer): String = peer.name ?: stringResource(R.string.share_device_unnamed, peer.tag)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
