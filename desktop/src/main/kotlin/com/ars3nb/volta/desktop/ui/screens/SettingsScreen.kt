package com.ars3nb.volta.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.ConnectMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.stringResource
import com.ars3nb.volta.desktop.ui.DesktopBridge
import com.ars3nb.volta.desktop.ui.components.UpdatePanel
import com.ars3nb.volta.desktop.update.UpdateState
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.desktop.AppPaths
import com.ars3nb.volta.desktop.VoltaController
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.desktop.data.DesignTheme
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.Strings
import com.ars3nb.volta.desktop.platform.Autostart
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.components.Hint
import com.ars3nb.volta.desktop.ui.components.LinkRow
import com.ars3nb.volta.desktop.ui.components.PageHeader
import com.ars3nb.volta.desktop.ui.components.ScrollPage
import com.ars3nb.volta.desktop.ui.components.SectionHeader
import com.ars3nb.volta.desktop.ui.components.SlidingChoice
import com.ars3nb.volta.desktop.ui.components.SwitchRow
import com.ars3nb.volta.desktop.ui.components.VoltaCard
import com.ars3nb.volta.desktop.ui.components.VoltaChip
import com.ars3nb.volta.desktop.ui.components.modeTitle
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme

/** Where the user guide lives (the docs site built from site/). */
const val HELP_URL = "https://colossalblend.github.io/volta/"

/**
 * Settings, grouped by what they are about, as on Android: every on/off row carries a
 * badge that lights up with its switch, and the colour theme is picked from coins painted
 * in each theme's own metals. Windows adds start-up, the tray, the system proxy and the
 * language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: VoltaUiState,
    controller: VoltaController,
    copyToClipboard: (String) -> Unit,
    openUrl: (String) -> Unit,
    openFolder: (java.io.File) -> Unit,
    bridge: DesktopBridge? = null,
    update: UpdateState = UpdateState.Idle
) {
    val s = LocalStrings.current
    val st = state.settings
    val palette = VoltaTheme.palette
    val primary = MaterialTheme.colorScheme.primary
    val ports = state.runtime.ports

    ScrollPage {
        item(key = "header") { PageHeader(s.navSettings) }

        item(key = "connection-title") { SectionHeader(s.secConnection) }
        item(key = "connection") {
            VoltaCard(contentPadding = 10) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                    Text(s.howToPick, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    SlidingChoice(
                        options = ConnectMode.entries.toList(),
                        selected = st.connectMode,
                        onSelect = controller::setConnectMode,
                        label = { modeTitle(it, s) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SwitchRow(
                    icon = Icons.Rounded.SettingsEthernet,
                    tint = palette.good,
                    title = s.systemProxyTitle,
                    subtitle = s.systemProxyHint + "\n" + s.localPortsLine(ports?.http ?: st.httpPort, ports?.socks ?: st.socksPort),
                    checked = st.systemProxy,
                    onChecked = controller::setSystemProxy
                )
                SwitchRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    tint = primary,
                    title = s.autoConnectTitle,
                    subtitle = s.autoConnectHint,
                    checked = st.autoConnect,
                    onChecked = controller::setAutoConnect
                )
                SwitchRow(
                    icon = Icons.Rounded.Autorenew,
                    tint = palette.good,
                    title = s.failoverTitle,
                    subtitle = s.failoverHint,
                    checked = st.autoFailover,
                    onChecked = controller::setAutoFailover
                )
                SwitchRow(
                    icon = Icons.Rounded.FilterList,
                    tint = primary,
                    title = stringResource(R.string.settings_auto_by_subscription_title),
                    subtitle = stringResource(R.string.settings_auto_by_subscription_text),
                    checked = st.autoBySubscription,
                    onChecked = controller::setAutoBySubscription
                )
            }
        }

        item(key = "windows-title") { SectionHeader(s.secWindows) }
        item(key = "windows") {
            VoltaCard(contentPadding = 10) {
                if (Autostart.supported) {
                    SwitchRow(
                        icon = Icons.Rounded.DesktopWindows,
                        tint = primary,
                        title = s.autostartTitle,
                        subtitle = s.autostartHint,
                        checked = state.runtime.autostart,
                        onChecked = controller::setAutostart
                    )
                }
                SwitchRow(
                    icon = Icons.Rounded.Minimize,
                    tint = palette.home,
                    title = s.trayTitle,
                    subtitle = s.trayHint,
                    checked = st.minimizeToTray,
                    onChecked = controller::setMinimizeToTray
                )
            }
        }

        item(key = "checks-title") { SectionHeader(s.secChecks) }
        item(key = "checks") {
            VoltaCard(contentPadding = 10) {
                SwitchRow(
                    icon = Icons.Rounded.CloudSync,
                    tint = primary,
                    title = s.refreshOnLaunchTitle,
                    subtitle = s.refreshOnLaunchHint,
                    checked = st.refreshOnLaunch,
                    onChecked = controller::setRefreshOnLaunch
                )
                SwitchRow(
                    icon = Icons.Rounded.Public,
                    tint = palette.home,
                    title = s.probeOnStartTitle,
                    subtitle = s.probeOnStartHint,
                    checked = st.pingOnStart,
                    onChecked = controller::setPingOnStart
                )
                SwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    tint = palette.good,
                    title = stringResource(R.string.gemini_settings_title),
                    subtitle = stringResource(R.string.gemini_settings_text),
                    checked = st.geminiAfterConnect,
                    onChecked = controller::setGeminiAfterConnect
                )
                Column(Modifier.padding(start = 48.dp, top = 6.dp, bottom = 8.dp, end = 4.dp)) {
                    Text(s.howToCheck, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PingType.REAL_DELAY, PingType.TCP).forEach { t ->
                            VoltaChip(
                                text = if (t == PingType.REAL_DELAY) s.pingReal else s.pingTcp,
                                selected = st.pingType == t,
                                onClick = { controller.setPingType(t) },
                                rest = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint(if (st.pingType == PingType.TCP) s.pingTcpHint else s.pingRealHint)
                }
            }
        }

        item(key = "tunnel-title") { SectionHeader(s.secTunnel) }
        item(key = "tunnel") {
            VoltaCard(contentPadding = 10) {
                SwitchRow(
                    icon = Icons.Rounded.Dns,
                    tint = primary,
                    title = s.localDnsTitle,
                    subtitle = s.localDnsHint,
                    checked = st.localDns,
                    onChecked = controller::setLocalDns
                )
            }
        }

        item(key = "look-title") { SectionHeader(s.secLook) }
        item(key = "look") {
            VoltaCard {
                Text(s.theme, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DesignTheme.entries.forEach { t ->
                        ThemeCoin(t, label = themeLabel(t, s), selected = st.designTheme == t) { controller.setTheme(t) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(s.language, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoltaChip(
                        text = s.languageSystem,
                        selected = st.language == "system",
                        onClick = { controller.setLanguage("system") },
                        rest = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Lang.entries.forEach { lang ->
                        VoltaChip(
                            text = lang.nativeName,
                            selected = st.language == lang.code,
                            onClick = { controller.setLanguage(lang.code) },
                            rest = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    icon = Icons.Rounded.Speed,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_lite_mode),
                    subtitle = stringResource(R.string.desktop_lite_mode_hint),
                    checked = st.liteMode,
                    onChecked = controller::setLiteMode
                )
            }
        }

        item(key = "device-title") { SectionHeader(s.secDevice) }
        item(key = "device") {
            VoltaCard(contentPadding = 10) {
                SwitchRow(
                    icon = Icons.Rounded.Fingerprint,
                    tint = primary,
                    title = s.hwidTitle,
                    subtitle = s.hwidHint,
                    checked = st.sendHwid,
                    onChecked = controller::setSendHwid
                )
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.deviceCode, style = MaterialTheme.typography.labelMedium)
                        Hint(controller.hwid())
                    }
                    IconButton(onClick = {
                        copyToClipboard(controller.hwid())
                        controller.message(s.msgCodeCopied)
                    }) { Icon(Icons.Rounded.ContentCopy, s.copyCode) }
                }
            }
        }

        item(key = "move-title") { SectionHeader(stringResource(R.string.desktop_section_move)) }
        item(key = "move") { BackupCard(controller, bridge) }

        item(key = "about-title") { SectionHeader(s.secAbout) }
        item(key = "about") {
            VoltaCard(contentPadding = 10) {
                // As on Android: the version and what the update is doing, then the switches.
                UpdatePanel(
                    state = update,
                    currentVersion = AppPaths.appVersion,
                    onCheck = controller::checkUpdates,
                    onInstall = controller::installUpdate
                )
                SwitchRow(
                    icon = Icons.Rounded.SystemUpdate,
                    tint = primary,
                    title = stringResource(R.string.settings_update_auto_title),
                    subtitle = stringResource(
                        if (st.updateAutoCheck) R.string.settings_update_auto_on else R.string.settings_update_auto_off
                    ),
                    checked = st.updateAutoCheck,
                    onChecked = controller::setUpdateAutoCheck
                )
                SwitchRow(
                    icon = Icons.Rounded.Science,
                    tint = palette.fair,
                    title = stringResource(R.string.settings_update_beta_title),
                    subtitle = stringResource(R.string.settings_update_beta_text),
                    checked = st.updateBeta,
                    onChecked = controller::setUpdateBeta
                )
                LinkRow(
                    icon = Icons.Rounded.Memory,
                    tint = primary,
                    title = s.version(AppPaths.appVersion),
                    subtitle = state.runtime.coreVersion.takeIf { it.isNotBlank() }?.let(s.coreVersion),
                    onClick = {},
                    trailing = {}
                )
                LinkRow(
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    tint = primary,
                    title = s.helpTitle,
                    subtitle = s.helpHint,
                    onClick = { openUrl(HELP_URL) }
                )
                LinkRow(
                    icon = Icons.Rounded.FolderOpen,
                    tint = palette.home,
                    title = s.dataFolder,
                    subtitle = s.dataFolderHint,
                    onClick = { openFolder(controller.paths.dataDir) }
                )
            }
        }
    }
}

private fun themeLabel(t: DesignTheme, s: Strings) = when (t) {
    DesignTheme.SYSTEM -> s.themeSystem
    DesignTheme.VOLTA -> s.themeVolta
    DesignTheme.LIGHT -> s.themeLight
    DesignTheme.OCEAN -> s.themeOcean
    DesignTheme.EMBER -> s.themeEmber
    // A name, as on Android: the colours come from Windows' accent colour.
    DesignTheme.DYNAMIC -> "Material"
}

/**
 * The Android app's «Перенос на новый телефон», for a computer: everything into one file
 * (the same format as on Android, so it opens on a phone too), and back from it.
 */
@Composable
private fun BackupCard(controller: VoltaController, bridge: DesktopBridge?) {
    val primary = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<BackupCodec.Contents?>(null) }
    var working by remember { mutableStateOf(false) }
    VoltaCard(contentPadding = 10) {
        LinkRow(
            icon = Icons.Rounded.SaveAlt,
            tint = primary,
            title = stringResource(R.string.settings_backup_save_title),
            subtitle = stringResource(R.string.settings_backup_save_text),
            onClick = {
                if (!working) {
                    val file = bridge?.chooseSaveFile(BackupCodec.fileName(LocalDate.now()))
                    if (file != null) controller.saveBackup(file)
                }
            }
        )
        LinkRow(
            icon = Icons.Rounded.Restore,
            tint = VoltaTheme.palette.good,
            title = stringResource(R.string.settings_backup_restore_title),
            subtitle = stringResource(R.string.desktop_backup_restore_text),
            onClick = {
                if (!working) {
                    val file = bridge?.chooseOpenFile()
                    if (file != null) {
                        scope.launch {
                            offer = withContext(Dispatchers.IO) { controller.readBackup(file) }
                        }
                    }
                }
            }
        )
        AnimatedVisibility(visible = working, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Hint(stringResource(R.string.settings_backup_working))
            }
        }
        Hint(stringResource(R.string.settings_backup_warning), Modifier.padding(start = 48.dp, top = 2.dp, bottom = 6.dp, end = 4.dp))
    }
    offer?.let { contents ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text(stringResource(R.string.settings_backup_restore_question)) },
            text = { Text(BackupCodec.describe(contents)) },
            confirmButton = {
                TextButton(onClick = {
                    offer = null
                    working = true
                    controller.restoreBackup(contents) { working = false }
                }) { Text(stringResource(R.string.settings_backup_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { offer = null }) { Text(stringResource(R.string.settings_backup_cancel)) }
            }
        )
    }
}

/**
 * One colour theme as a coin painted in its own colours: the background as the rim, the
 * surface as the face and the accent as the centre, like the power disc. "Система" is
 * half night, half day.
 */
@Composable
private fun ThemeCoin(theme: DesignTheme, label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val ring by animateColorAsState(if (selected) primary else Color.Transparent, label = "coinRing")
    val (rim, face, core) = when (theme) {
        DesignTheme.VOLTA -> Triple(Color(0xFF101316), Color(0xFF22282D), Color(0xFFD08350))
        DesignTheme.LIGHT -> Triple(Color(0xFFEFEAE3), Color(0xFFFFFFFF), Color(0xFFB05F28))
        DesignTheme.OCEAN -> Triple(Color(0xFF0B1A20), Color(0xFF193239), Color(0xFF3DBFB4))
        DesignTheme.EMBER -> Triple(Color(0xFF161210), Color(0xFF2E251E), Color(0xFFF08A3C))
        DesignTheme.SYSTEM -> Triple(Color(0xFF101316), Color(0xFFFFFFFF), Color(0xFFD08350))
        DesignTheme.DYNAMIC -> {
            val seed = com.ars3nb.volta.desktop.ui.theme.Accent.seed
            Triple(
                com.ars3nb.volta.desktop.ui.theme.Accent.tone(seed, 0.12f, 0.2f),
                com.ars3nb.volta.desktop.ui.theme.Accent.tone(seed, 0.25f, 0.3f),
                com.ars3nb.volta.desktop.ui.theme.Accent.tone(seed, 0.70f)
            )
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        Canvas(Modifier.size(56.dp).border(2.dp, ring, CircleShape).padding(5.dp)) {
            val r = size.minDimension / 2
            if (theme == DesignTheme.SYSTEM) {
                drawArc(rim, 90f, 180f, useCenter = true, size = Size(r * 2, r * 2))
                drawArc(Color(0xFFEFEAE3), 270f, 180f, useCenter = true, size = Size(r * 2, r * 2))
                drawArc(Color(0xFF22282D), 90f, 180f, useCenter = true, topLeft = Offset(r * 0.28f, r * 0.28f), size = Size(r * 1.44f, r * 1.44f))
                drawArc(face, 270f, 180f, useCenter = true, topLeft = Offset(r * 0.28f, r * 0.28f), size = Size(r * 1.44f, r * 1.44f))
            } else {
                drawCircle(rim, radius = r)
                drawCircle(face, radius = r * 0.72f)
            }
            drawCircle(core, radius = r * 0.32f)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
