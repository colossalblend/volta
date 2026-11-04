package com.ars3nb.volta.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.link.ImportDetector.Kind
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.collectQuietState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.IconBubble
import com.ars3nb.volta.ui.components.LinkRow
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.VoltaField
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.focusRing

/**
 * Adding anything: one field for whatever the VPN service gave — a subscription
 * address, an encrypted volta:// link, a server link, an AmneziaVPN key, a .conf or a
 * JSON config. The line under the field says what Volta made of it as it is typed, and
 * the button is named after what will happen, so there is no "which box was it" step.
 */
@Composable
fun ImportScreen(vm: VoltaViewModel, onBack: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val state by vm.collectQuietState()
    val clipboard = LocalClipboardManager.current
    val kind = remember(input) { ImportDetector.detect(input) }

    // A .conf from AmneziaVPN, AmneziaWG or WireGuard. Its type varies by file manager,
    // so any file is offered; the parser decides.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Read in the view model, off the main thread: the file may sit on a cloud drive.
        vm.importFile(uri) { ok -> if (ok) onBack() }
    }

    fun submit() {
        val value = input.trim()
        if (kind.isSubscription) {
            vm.addSubscription(name.trim(), value) { ok -> if (ok) onBack() }
        } else {
            vm.importLink(value) { ok -> if (ok) onBack() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
    ) {
        Spacer(Modifier.height(10.dp))
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(44.dp).focusRing(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_import_back))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.settings_import_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_import_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        VoltaField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.settings_import_placeholder, SERVER_SCHEMES),
            minLines = 4,
            maxLines = 9,
            container = MaterialTheme.colorScheme.surface,
            restingOutline = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(Modifier.height(10.dp))
        // Pasting is what nearly everyone comes here to do, so the button is always there.
        // The clipboard is read only on the tap: reading it earlier would show Android's
        // "pasted" toast for nothing, and before the window has focus it reads as empty.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldButton(
                icon = Icons.Rounded.ContentPaste,
                text = stringResource(R.string.settings_import_paste),
                filled = input.isEmpty(),
                onClick = {
                    val text = clipboard.getText()?.text?.trim()
                    if (text.isNullOrEmpty()) vm.showMessage(UiText.of(R.string.settings_import_clipboard_empty))
                    else input = text
                }
            )
            if (input.isNotEmpty()) {
                FieldButton(
                    icon = Icons.Rounded.Close,
                    text = stringResource(R.string.settings_import_clear),
                    filled = false,
                    onClick = { input = "" }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        DetectedLine(kind)

        AnimatedVisibility(
            visible = kind.isSubscription,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            VoltaField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.settings_import_name_label),
                singleLine = true,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = ::submit,
            enabled = kind.canSubmit && !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .focusRing(CircleShape, color = MaterialTheme.colorScheme.onBackground)
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                AnimatedContent(
                    targetState = kind.action,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "importAction"
                ) { Text(stringResource(it), style = MaterialTheme.typography.titleSmall) }
            }
        }

        Spacer(Modifier.height(14.dp))
        VoltaCard(contentPadding = 8) {
            LinkRow(
                icon = Icons.Rounded.FileOpen,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_import_open_file),
                subtitle = stringResource(R.string.settings_import_open_file_text),
                onClick = {
                    if (!state.busy) {
                        // Many TVs have no file picker at all.
                        try {
                            openFile.launch(arrayOf("*/*"))
                        } catch (e: ActivityNotFoundException) {
                            vm.showMessage(UiText.of(R.string.settings_import_no_file_picker))
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(R.string.settings_import_formats_title))
        Spacer(Modifier.height(6.dp))
        VoltaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FORMATS.forEach { (title, body) ->
                    Column {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Hint(stringResource(body))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** What Volta made of the field, said back in a line with a badge. */
@Composable
private fun DetectedLine(kind: Kind) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = kind,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "detected"
    ) { k ->
        val (icon, tint, title, body) = when (k) {
            Kind.Empty -> Look(
                Icons.Rounded.Link, scheme.onSurfaceVariant,
                stringResource(R.string.settings_import_empty), stringResource(R.string.settings_import_empty_hint)
            )
            is Kind.Subscription -> if (k.hidden) {
                Look(
                    Icons.Rounded.Lock, scheme.primary,
                    stringResource(R.string.settings_import_hidden_sub), stringResource(R.string.settings_import_hidden_sub_hint)
                )
            } else {
                Look(
                    Icons.Rounded.CloudSync, scheme.primary,
                    stringResource(R.string.settings_import_sub), stringResource(R.string.settings_import_sub_hint)
                )
            }
            Kind.Encrypted -> Look(
                Icons.Rounded.EnhancedEncryption, palette.good,
                stringResource(R.string.settings_import_encrypted), stringResource(R.string.settings_import_encrypted_hint)
            )
            is Kind.Server -> Look(
                Icons.Rounded.Storage, palette.home,
                stringResource(R.string.settings_import_server), stringResource(R.string.settings_import_server_hint)
            )
            is Kind.Servers -> Look(
                Icons.Rounded.Storage, palette.home,
                pluralStringResource(R.plurals.settings_import_servers, k.count, k.count),
                stringResource(R.string.settings_import_servers_hint)
            )
            Kind.AmneziaKey -> Look(
                Icons.Rounded.Key, palette.home,
                stringResource(R.string.settings_import_amnezia), stringResource(R.string.settings_import_amnezia_hint)
            )
            Kind.Conf -> Look(
                Icons.Rounded.Description, palette.home,
                stringResource(R.string.settings_import_conf), stringResource(R.string.settings_import_conf_hint)
            )
            Kind.Json -> Look(
                Icons.Rounded.DataObject, palette.home,
                stringResource(R.string.settings_import_json), stringResource(R.string.settings_import_json_hint)
            )
            Kind.Control -> Look(
                Icons.Rounded.PowerSettingsNew, scheme.primary,
                stringResource(R.string.settings_import_control), stringResource(R.string.settings_import_control_hint)
            )
            Kind.Unknown -> Look(
                Icons.Rounded.QuestionMark, palette.poor,
                stringResource(R.string.settings_import_unknown), stringResource(R.string.settings_import_unknown_hint)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, tint, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (k == Kind.Empty) scheme.onSurfaceVariant else scheme.onBackground)
                Hint(body)
            }
        }
    }
}

private data class Look(val icon: ImageVector, val tint: Color, val title: String, val body: String)

private val Kind.canSubmit: Boolean
    get() = this != Kind.Empty && this != Kind.Unknown

/** The button says what it will do. */
@get:StringRes
private val Kind.action: Int
    get() = when (this) {
        is Kind.Subscription, Kind.Encrypted -> R.string.settings_import_add_sub
        is Kind.Server -> R.string.settings_import_add_server
        is Kind.Servers -> R.string.settings_import_add_servers
        Kind.AmneziaKey, Kind.Conf, Kind.Json -> R.string.settings_import_import
        Kind.Control -> R.string.settings_import_run
        Kind.Empty, Kind.Unknown -> R.string.settings_import_title
    }

/** Server link schemes, shown as they are in every language. */
private const val SERVER_SCHEMES = "vless://, vmess://, trojan://, ss://, hysteria2://, openflux://"

/** What can be pasted: a title and a line about it. */
private val FORMATS = listOf(
    R.string.settings_import_sub to R.string.settings_import_format_sub,
    R.string.settings_import_server to R.string.settings_import_format_server,
    R.string.settings_import_amnezia to R.string.settings_import_format_amnezia,
    R.string.settings_import_format_openflux_title to R.string.settings_import_format_openflux,
    R.string.settings_import_format_volta_title to R.string.settings_import_format_volta,
    R.string.settings_import_format_file_title to R.string.settings_import_format_file
)

/** A pill under the field: filled copper for the main move, a copper wash otherwise. */
@Composable
private fun FieldButton(icon: ImageVector, text: String, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = VoltaTheme.palette.isDark
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) scheme.primary else scheme.primary.copy(alpha = if (dark) 0.18f else 0.14f),
        // On the filled copper pill a copper outline would not show.
        modifier = Modifier.focusRing(CircleShape, color = if (filled) scheme.onBackground else Color.Unspecified)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ink = if (filled) scheme.onPrimary else scheme.primary
            Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
        }
    }
}

/** A config file is a few kilobytes; anything far bigger is not one. */
