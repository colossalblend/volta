package com.ars3nb.volta.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.link.ImportDetector.Kind
import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.desktop.VoltaController
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.stringResource
import com.ars3nb.volta.desktop.core.WindowsCompat
import com.ars3nb.volta.desktop.i18n.Strings
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.components.Hint
import com.ars3nb.volta.desktop.ui.components.IconBubble
import com.ars3nb.volta.desktop.ui.components.LinkRow
import com.ars3nb.volta.desktop.ui.components.PageHeader
import com.ars3nb.volta.desktop.ui.components.ScrollPage
import com.ars3nb.volta.desktop.ui.components.SectionHeader
import com.ars3nb.volta.desktop.ui.components.VoltaCard
import com.ars3nb.volta.desktop.ui.components.VoltaField
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme

/** What the field holds between visits of the page. */
class ImportViewState {
    var input by mutableStateOf("")
    var name by mutableStateOf("")
}

/**
 * Adding anything, as on Android: one field for whatever the VPN service gave. The line
 * under it says what Volta made of the text as it is typed (and, on Windows, when it is a
 * kind of server only the Android app can open), and the button is named after what will
 * happen.
 */
@Composable
fun ImportScreen(
    state: VoltaUiState,
    controller: VoltaController,
    view: ImportViewState,
    readClipboard: () -> String?,
    pickFile: () -> String?,
    onDone: () -> Unit
) {
    val s = LocalStrings.current
    val kind = remember(view.input) { ImportDetector.detect(view.input) }
    val androidOnly = remember(view.input, kind) { androidOnlyProtocol(view.input.trim(), kind) }

    fun submit() {
        controller.importText(view.input.trim(), if (kind.isSubscription) view.name.trim() else "") { ok ->
            if (ok) {
                view.input = ""
                view.name = ""
                onDone()
            }
        }
    }

    ScrollPage(spacing = 0.dp) {
        item(key = "header") { PageHeader(s.addTitle) }
        item(key = "body") {
            Column {
                Text(s.addSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                VoltaField(
                    value = view.input,
                    onValueChange = { view.input = it },
                    placeholder = s.addPlaceholder,
                    minLines = 4,
                    maxLines = 9,
                    container = MaterialTheme.colorScheme.surface,
                    restingOutline = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldButton(
                        icon = Icons.Rounded.ContentPaste,
                        text = s.pasteFromClipboard,
                        filled = view.input.isEmpty(),
                        onClick = {
                            val text = readClipboard()?.trim()
                            if (text.isNullOrEmpty()) controller.message(s.clipboardEmpty) else view.input = text
                        }
                    )
                    if (view.input.isNotEmpty()) {
                        FieldButton(icon = Icons.Rounded.Close, text = s.clear, filled = false, onClick = { view.input = "" })
                    }
                }
                Spacer(Modifier.height(14.dp))
                DetectedLine(kind, androidOnly)
                AnimatedVisibility(visible = kind.isSubscription, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    VoltaField(
                        value = view.name,
                        onValueChange = { view.name = it },
                        label = s.nameOptional,
                        singleLine = true,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = ::submit,
                    enabled = kind.canSubmit && androidOnly == null && !state.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        AnimatedContent(targetState = actionOf(kind, s), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "importAction") {
                            Text(it, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                VoltaCard(contentPadding = 8) {
                    LinkRow(
                        icon = Icons.Rounded.FileOpen,
                        tint = MaterialTheme.colorScheme.primary,
                        title = s.openFile,
                        subtitle = s.openFileHint,
                        onClick = {
                            if (!state.busy) pickFile()?.takeIf { it.isNotBlank() }?.let { view.input = it.trim() }
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
                SectionHeader(s.whatCanPaste)
                Spacer(Modifier.height(6.dp))
                VoltaCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            s.fmtSubTitle to s.fmtSubBody,
                            s.fmtServerTitle to s.fmtServerBody,
                            s.fmtVoltaTitle to s.fmtVoltaBody,
                            s.fmtFileTitle to s.fmtFileBody,
                            // As on Android: these run in Volta's helpers, on Windows too.
                            s.detAmneziaTitle to stringResource(R.string.settings_import_format_amnezia),
                            stringResource(R.string.settings_import_format_openflux_title) to
                                stringResource(R.string.settings_import_format_openflux)
                        ).forEach { (title, body) ->
                            Column {
                                Text(title, style = MaterialTheme.typography.titleSmall)
                                Hint(body)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The protocol's name when everything pasted needs an Android-only helper (AmneziaWG,
 * Hysteria v1, OpenFlux); null when Volta for Windows can open it.
 */
private fun androidOnlyProtocol(text: String, kind: Kind): String? {
    if (text.isEmpty()) return null
    val servers: List<ServerEntity> = runCatching {
        when (kind) {
            is Kind.Server, is Kind.Servers -> ConfigParser.parseSubscriptionBody(text).servers
            Kind.Conf -> listOfNotNull(AmneziaConfigs.parseConf(text))
            Kind.AmneziaKey -> AmneziaConfigs.parseAmneziaKey(text)
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).map(WindowsCompat::adapt)
    // Only when this build lacks the helper they need (see ProxyHelper, OpenFluxRunner).
    if (servers.isEmpty() || !servers.all { WindowsCompat.needsHelper(it) && ConfigBuilder.dialProblem(it) != null }) return null
    return servers.first().protocol.label
}

@Composable
private fun DetectedLine(kind: Kind, androidOnly: String?) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val s = LocalStrings.current
    AnimatedContent(targetState = kind to androidOnly, contentKey = { it.first::class to it.second }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "detected") { (k, only) ->
        val look = if (only != null) {
            Look(Icons.Rounded.Block, palette.poor, only, s.detNotOnWindows(only))
        } else when (k) {
            Kind.Empty -> Look(Icons.Rounded.Link, scheme.onSurfaceVariant, s.detEmptyTitle, s.detEmptyBody)
            is Kind.Subscription -> if (k.hidden) {
                Look(Icons.Rounded.Lock, scheme.primary, s.detSubHiddenTitle, s.detSubHiddenBody)
            } else {
                Look(Icons.Rounded.CloudSync, scheme.primary, s.detSubTitle, s.detSubBody)
            }
            Kind.Encrypted -> Look(Icons.Rounded.EnhancedEncryption, palette.good, s.detEncTitle, s.detEncBody)
            is Kind.Server -> Look(Icons.Rounded.Storage, palette.home, s.detServerTitle(k.protocol), s.detServerBody)
            is Kind.Servers -> Look(Icons.Rounded.Storage, palette.home, s.detServersTitle(k.count), s.detServersBody)
            Kind.AmneziaKey -> Look(Icons.Rounded.Key, palette.home, s.detAmneziaTitle, s.detServersBody)
            Kind.Conf -> Look(Icons.Rounded.Description, palette.home, s.detConfTitle, s.detConfBody)
            Kind.Json -> Look(Icons.Rounded.DataObject, palette.home, s.detJsonTitle, s.detJsonBody)
            Kind.Control -> Look(Icons.Rounded.PowerSettingsNew, scheme.primary, s.detControlTitle, s.detControlBody)
            Kind.Unknown -> Look(Icons.Rounded.QuestionMark, palette.poor, s.detUnknownTitle, s.detUnknownBody)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(look.icon, look.tint, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    look.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (k == Kind.Empty && only == null) scheme.onSurfaceVariant else scheme.onBackground
                )
                Hint(look.body)
            }
        }
    }
}

private data class Look(val icon: ImageVector, val tint: Color, val title: String, val body: String)

private val Kind.canSubmit: Boolean get() = this != Kind.Empty && this != Kind.Unknown

/** The button says what it will do. */
private fun actionOf(kind: Kind, s: Strings): String = when (kind) {
    is Kind.Subscription, Kind.Encrypted -> s.actAddSubscription
    is Kind.Server -> s.actAddServer
    is Kind.Servers -> s.actAddServers
    Kind.AmneziaKey, Kind.Conf, Kind.Json -> s.actImport
    Kind.Control -> s.actRun
    Kind.Empty, Kind.Unknown -> s.add
}

/** A pill under the field: filled copper for the main move, a copper wash otherwise. */
@Composable
private fun FieldButton(icon: ImageVector, text: String, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = VoltaTheme.palette.isDark
    Surface(onClick = onClick, shape = CircleShape, color = if (filled) scheme.primary else scheme.primary.copy(alpha = if (dark) 0.18f else 0.14f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = if (filled) scheme.onPrimary else scheme.primary
            Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
        }
    }
}
