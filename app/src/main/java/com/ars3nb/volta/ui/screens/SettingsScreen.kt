package com.ars3nb.volta.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.AppIconPicker
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.IconBubble
import com.ars3nb.volta.ui.components.LinkRow
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.SmallShape
import com.ars3nb.volta.ui.components.SwitchRow
import com.ars3nb.volta.ui.components.LanguageRow
import com.ars3nb.volta.ui.components.UpdatePanel
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.VoltaChip
import com.ars3nb.volta.ui.components.barColors
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tile.GroupTileService
import com.ars3nb.volta.ui.tile.TileInstaller
import com.ars3nb.volta.ui.tv.LocalTv
import com.ars3nb.volta.ui.tv.TileRows
import com.ars3nb.volta.ui.tv.Tv
import com.ars3nb.volta.ui.tv.focusRing

/**
 * Settings, grouped by what they are about. Every on/off row carries a badge that lights
 * up with its switch, so the card reads as "what is on" at a glance; the colour theme is
 * picked from coins painted in each theme's own metals instead of a row of words.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: VoltaViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val autoFailover by vm.autoFailover.collectAsStateWithLifecycle()
    val pingOnStart by vm.pingOnStart.collectAsStateWithLifecycle()
    val batterySaver by vm.batterySaver.collectAsStateWithLifecycle()
    val localDns by vm.localDns.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val sendHwid by vm.sendHwid.collectAsStateWithLifecycle()
    val pingType by vm.defaultPingType.collectAsStateWithLifecycle()
    val refreshOnLaunch by vm.refreshOnLaunch.collectAsStateWithLifecycle()
    val followNetwork by vm.followNetwork.collectAsStateWithLifecycle()
    val autoRecheck by vm.autoRecheck.collectAsStateWithLifecycle()
    val simpleMode by vm.simpleMode.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val updateAutoCheck by vm.updateAutoCheck.collectAsStateWithLifecycle()
    val updateBeta by vm.updateBeta.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val palette = VoltaTheme.palette
    val primary = MaterialTheme.colorScheme.primary
    // A TV has no shade for tiles and shows a banner instead of the app icon.
    val tv = LocalTv.current
    val rows = remember(tv) { Tv.settingsRows(tv, Build.VERSION.SDK_INT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки", fontWeight = FontWeight.SemiBold) }, colors = barColors()) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.TouchApp,
                        tint = primary,
                        title = "Простой режим",
                        subtitle = "Одна большая кнопка «Включить / Выключить», сервер выбирается сам. " +
                            "Удобно для родителей и всех, кому не нужны настройки.",
                        checked = simpleMode == true,
                        onChecked = vm::setSimpleMode
                    )
                }
            }

            item { SectionHeader("Подключение") }
            item {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.SyncAlt,
                        tint = palette.home,
                        title = "Следить за сменой сети",
                        subtitle = "При переходе между Wi-Fi и мобильным интернетом сервер проверяется и меняется сам.",
                        checked = followNetwork,
                        onChecked = vm::setFollowNetwork
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Insights,
                        tint = palette.good,
                        title = "Улучшать выбор сервера",
                        subtitle = "Раз в 15 минут при включённом экране сравнивает серверы и переходит " +
                            "на заметно более быстрый. Неотвечающий сервер заменяется всегда.",
                        checked = autoRecheck,
                        onChecked = vm::setAutoRecheck
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Autorenew,
                        tint = palette.good,
                        title = "Менять сервер при обрыве",
                        subtitle = "Если сервер перестал отвечать, Volta перейдёт на другой.",
                        checked = autoFailover,
                        onChecked = vm::setAutoFailover
                    )
                    SwitchRow(
                        icon = Icons.Rounded.PowerSettingsNew,
                        tint = primary,
                        title = "Подключаться при запуске",
                        subtitle = "Включать VPN сразу после старта системы.",
                        checked = autoConnect,
                        onChecked = vm::setAutoConnect
                    )
                    LinkRow(
                        icon = Icons.Rounded.VpnLock,
                        tint = if (alwaysOn?.lockdown == true) palette.good else primary,
                        title = "Не пускать интернет без VPN",
                        subtitle = killSwitchHint(alwaysOn),
                        onClick = {
                            if (!Tv.tryStart(context, Intent(Settings.ACTION_VPN_SETTINGS))) {
                                vm.showMessage("На этом устройстве нет настроек VPN")
                            }
                        }
                    )
                }
            }

            if (rows.tiles != TileRows.NONE) {
                item { SectionHeader("Плитки в шторке") }
                item {
                    VoltaCard {
                        Hint("Нажатие на плитку включает VPN в её режиме, повторное — выключает.")
                        Spacer(Modifier.height(12.dp))
                        if (rows.tiles == TileRows.ADD_BUTTONS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(ConnectMode.AUTO_HOME_LTE, ConnectMode.AUTO_HOME, ConnectMode.AUTO_LTE)
                                    .forEach { mode ->
                                        TilePad(mode, Modifier.weight(1f)) {
                                            TileInstaller.request(context, mode) { text ->
                                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            }
                        } else {
                            Hint(TileInstaller.MANUAL_HINT)
                        }
                    }
                }
            }

            item { SectionHeader("Подписки и проверки") }
            item {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.CloudSync,
                        tint = primary,
                        title = "Обновлять подписки при запуске",
                        subtitle = "Серверы и правила обновляются при открытии приложения.",
                        checked = refreshOnLaunch,
                        onChecked = vm::setRefreshOnLaunch
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Public,
                        tint = palette.home,
                        title = "Проверять сеть при запуске",
                        subtitle = "Сразу видно, какая сейчас сеть: Home или LTE.",
                        checked = pingOnStart,
                        onChecked = vm::setPingOnStart
                    )
                    Column(Modifier.padding(start = 48.dp, top = 6.dp, bottom = 8.dp, end = 4.dp)) {
                        Text("Как проверять серверы", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PingType.entries.forEach { t ->
                                VoltaChip(
                                    text = pingLabel(t),
                                    selected = pingType == t,
                                    onClick = { vm.setDefaultPingType(t) },
                                    rest = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Hint(pingHint(pingType))
                    }
                }
            }

            item { SectionHeader("Туннель и батарея") }
            item {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.Dns,
                        tint = primary,
                        title = "Открывать сайты через VPN",
                        subtitle = "Адреса сайтов тоже узнаются через VPN, так меньше блокировок.",
                        checked = localDns,
                        onChecked = vm::setLocalDns
                    )
                    SwitchRow(
                        icon = Icons.Rounded.BatterySaver,
                        tint = palette.good,
                        title = "Беречь батарею",
                        subtitle = "Короткие проверки и меньше фоновой работы.",
                        checked = batterySaver,
                        onChecked = vm::setBatterySaver
                    )
                }
            }

            item { SectionHeader("Оформление") }
            item {
                VoltaCard {
                    Text("Тема", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        DesignTheme.entries.forEach { t ->
                            ThemeCoin(t, selected = state.designTheme == t) { vm.setDesignTheme(t) }
                        }
                    }
                    if (rows.appIcon) {
                        Spacer(Modifier.height(18.dp))
                        Text("Иконка приложения", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        AppIconPicker()
                    }
                }
            }
            item {
                VoltaCard(contentPadding = 10) {
                    LanguageRow()
                }
            }

            item { SectionHeader("Устройство") }
            item {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.Fingerprint,
                        tint = primary,
                        title = "Привязка к устройству",
                        subtitle = "Включите, если VPN-сервис ограничивает число устройств на одну подписку.",
                        checked = sendHwid,
                        onChecked = vm::setSendHwid
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 48.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Код этого устройства", style = MaterialTheme.typography.labelMedium)
                            Hint(vm.hwidFull())
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(vm.hwidFull()))
                            vm.showMessage("Код скопирован")
                        }) {
                            Icon(Icons.Rounded.ContentCopy, "Скопировать код")
                        }
                    }
                }
            }

            item { SectionHeader("Перенос на новый телефон") }
            item { BackupCard(vm) }

            item { SectionHeader("О приложении") }
            item {
                VoltaCard(contentPadding = 10) {
                    UpdatePanel(
                        state = update,
                        currentVersion = BuildConfig.VERSION_NAME,
                        onCheck = vm::checkForUpdate,
                        onInstall = vm::installUpdate,
                        storeNote = if (vm.updaterBuilt) null else "Обновления приходят через Google Play"
                    )
                    // The Google Play build has no updater of its own: nothing to switch.
                    if (vm.updaterBuilt) {
                        SwitchRow(
                            icon = Icons.Rounded.Autorenew,
                            tint = primary,
                            title = "Проверка обновлений",
                            subtitle = if (updateAutoCheck) {
                                "При запуске и раз в несколько часов. О новой версии придёт уведомление"
                            } else {
                                "Выключена. Проверить можно кнопкой «Проверить»"
                            },
                            checked = updateAutoCheck,
                            onChecked = vm::setUpdateAutoCheck
                        )
                        SwitchRow(
                            icon = Icons.Rounded.Science,
                            tint = palette.lte,
                            title = "Бета-версии",
                            subtitle = "Получать пробные сборки раньше всех. В них могут быть ошибки",
                            checked = updateBeta,
                            onChecked = vm::setUpdateBeta
                        )
                    }
                    LinkRow(
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        tint = primary,
                        title = "Помощь и инструкции",
                        subtitle = "Как добавить подписку, выбрать режим и настроить приложения",
                        onClick = {
                            if (!Tv.tryStart(context, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(HELP_URL)))) {
                                vm.showMessage("Нет браузера. Инструкции на сайте ${HELP_URL.removePrefix("https://")}")
                            }
                        }
                    )
                }
            }
        }
    }

}

/** Where the user guide lives (the docs site built from site/). */
private const val HELP_URL = "https://colossalblend.github.io/volta/"


/** A Quick Settings tile as it will look in the shade; a tap asks Android to add it. */
@Composable
private fun TilePad(mode: ConnectMode, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceVariant,
        modifier = modifier.focusRing(RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                painterResource(GroupTileService.icon(mode)),
                null,
                tint = scheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(10.dp))
            // "Home+LTE" must fit a third of the row on any phone: shrink, don't cut.
            BasicText(
                GroupTileService.label(mode),
                style = MaterialTheme.typography.titleSmall.copy(color = scheme.onSurface),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 10.sp,
                    maxFontSize = MaterialTheme.typography.titleSmall.fontSize
                )
            )
            Text("добавить", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        }
    }
}

/**
 * One colour theme as a coin painted in its own colours: the background as the rim,
 * the surface as the face and the accent as the centre, like the power disc. "Система"
 * is half night, half day; "Material" shows the wallpaper colours where Android has them.
 */
@Composable
private fun ThemeCoin(theme: DesignTheme, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val ring by animateColorAsState(if (selected) primary else Color.Transparent, label = "coinRing")
    val (rim, face, core) = when (theme) {
        DesignTheme.VOLTA -> Triple(Color(0xFF101316), Color(0xFF22282D), Color(0xFFD08350))
        DesignTheme.LIGHT -> Triple(Color(0xFFEFEAE3), Color(0xFFFFFFFF), Color(0xFFB05F28))
        DesignTheme.OCEAN -> Triple(Color(0xFF0B1A20), Color(0xFF193239), Color(0xFF3DBFB4))
        DesignTheme.EMBER -> Triple(Color(0xFF161210), Color(0xFF2E251E), Color(0xFFF08A3C))
        DesignTheme.SYSTEM -> Triple(Color(0xFF101316), Color(0xFFFFFFFF), Color(0xFFD08350))
        DesignTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val d = remember(context) { dynamicDarkColorScheme(context) }
            Triple(d.background, d.surfaceVariant, d.primary)
        } else {
            Triple(Color(0xFF2A2D31), Color(0xFF3A3F45), Color(0xFF9AA4AE))
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .focusRing(SmallShape)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        Canvas(
            Modifier
                .size(56.dp)
                .border(2.dp, ring, CircleShape)
                .padding(5.dp)
        ) {
            val r = size.minDimension / 2
            if (theme == DesignTheme.SYSTEM) {
                // Night on the left, day on the right.
                drawArc(rim, 90f, 180f, useCenter = true, size = Size(r * 2, r * 2))
                drawArc(Color(0xFFEFEAE3), 270f, 180f, useCenter = true, size = Size(r * 2, r * 2))
                drawArc(Color(0xFF22282D), 90f, 180f, useCenter = true,
                    topLeft = Offset(r * 0.28f, r * 0.28f), size = Size(r * 1.44f, r * 1.44f))
                drawArc(face, 270f, 180f, useCenter = true,
                    topLeft = Offset(r * 0.28f, r * 0.28f), size = Size(r * 1.44f, r * 1.44f))
            } else {
                drawCircle(rim, radius = r)
                drawCircle(face, radius = r * 0.72f)
            }
            drawCircle(core, radius = r * 0.32f)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            themeLabel(theme),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Android's own kill switch: Always-on VPN with «Блокировать соединения без VPN». Volta
 * can't switch it on itself, only say whether it is on and open the screen where it is.
 */
private fun killSwitchHint(state: VoltaVpnService.AlwaysOn?): String = when {
    state == null ->
        "Android может держать Volta всегда включённой и закрыть интернет, пока VPN не подключён. " +
            "Нажмите, затем шестерёнку рядом с Volta: «Постоянная VPN» и «Блокировать соединения без VPN»."
    state.on && state.lockdown ->
        "Включено: Volta всегда включена, а без VPN интернет закрыт. Выключите VPN — интернет пропадёт, так и задумано."
    state.on ->
        "Volta включается сама, но пока VPN подключается, приложения выходят в интернет напрямую. " +
            "Включите «Блокировать соединения без VPN» в шестерёнке рядом с Volta."
    else ->
        "Выключено. Нажмите, затем шестерёнку рядом с Volta: «Постоянная VPN» и «Блокировать соединения без VPN»."
}

private fun pingLabel(t: PingType) = when (t) {
    PingType.REAL_DELAY -> "Через сервер"
    PingType.HTTP_GET -> "HTTP GET"
    PingType.TCP -> "TCP"
    PingType.ICMP -> "ICMP"
}

private fun pingHint(t: PingType) = when (t) {
    PingType.REAL_DELAY -> "Проверяет настоящую скорость через сервер. Рекомендуем."
    PingType.HTTP_GET -> "Для серверов, которые плохо отвечают на быструю проверку."
    PingType.TCP -> "Быстрее, но может показать сервер живым, когда он не работает."
    PingType.ICMP -> "Самая простая проверка. У многих мобильных операторов не работает."
}

private fun themeLabel(t: DesignTheme) = when (t) {
    DesignTheme.SYSTEM -> "Система"
    DesignTheme.VOLTA -> "Volta"
    DesignTheme.LIGHT -> "Светлая"
    DesignTheme.OCEAN -> "Океан"
    DesignTheme.EMBER -> "Уголь"
    DesignTheme.DYNAMIC -> "Material"
}
