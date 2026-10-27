package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.StatusPill
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.VoltaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: VoltaViewModel, onOpenPerApp: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val perAppMode by vm.perAppMode.collectAsStateWithLifecycle()
    val perAppPackages by vm.perAppPackages.collectAsStateWithLifecycle()
    val autoFailover by vm.autoFailover.collectAsStateWithLifecycle()
    val pingOnStart by vm.pingOnStart.collectAsStateWithLifecycle()
    val batterySaver by vm.batterySaver.collectAsStateWithLifecycle()
    val localDns by vm.localDns.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val sendHwid by vm.sendHwid.collectAsStateWithLifecycle()
    val pingType by vm.defaultPingType.collectAsStateWithLifecycle()
    val refreshOnLaunch by vm.refreshOnLaunch.collectAsStateWithLifecycle()
    val followNetwork by vm.followNetwork.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val palette = VoltaTheme.palette

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки", fontWeight = FontWeight.SemiBold) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionHeader("Приложения") }
            item {
                VoltaCard(onClick = onOpenPerApp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Apps,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Раздельное туннелирование",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Hint(
                                when (perAppMode) {
                                    PerAppMode.OFF -> "Все приложения через VPN"
                                    PerAppMode.INCLUDE ->
                                        "Через VPN: выбрано ${perAppPackages.size}"

                                    PerAppMode.EXCLUDE ->
                                        "Мимо VPN: выбрано ${perAppPackages.size}"
                                }
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }

            item { SectionHeader("Подключение") }
            item {
                VoltaCard {
                    ToggleRow(
                        title = "Следить за сменой сети",
                        subtitle = "В режиме «Авто Home+LTE» при переходе Wi-Fi ↔ мобильный " +
                            "интернет сеть проверяется заново и сервер меняется на нужную группу.",
                        checked = followNetwork,
                        onChecked = vm::setFollowNetwork
                    )
                    ToggleRow(
                        title = "Обновлять подписки при запуске",
                        subtitle = "Как в Happ: серверы, правила маршрутизации и geoip/geosite " +
                            "каждой подписки подтягиваются при открытии приложения.",
                        checked = refreshOnLaunch,
                        onChecked = vm::setRefreshOnLaunch
                    )
                }
            }

            item { SectionHeader("Проверка серверов") }
            item {
                VoltaCard {
                    Text("Чем мерить отклик", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PingType.entries.forEach { t ->
                            FilterChip(
                                selected = pingType == t,
                                onClick = { vm.setDefaultPingType(t) },
                                label = { Text(pingLabel(t)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint(pingHint(pingType))
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Проверять сеть при запуске",
                        subtitle = "Сразу видно, какая сейчас сеть: Home (открытый интернет) " +
                            "или LTE (только белый список).",
                        checked = pingOnStart,
                        onChecked = vm::setPingOnStart
                    )
                }
            }

            item { SectionHeader("Сеть и батарея") }
            item {
                VoltaCard {
                    ToggleRow(
                        title = "Менять сервер при обрыве",
                        subtitle = "Если узел перестал отвечать, приложение молча перейдёт " +
                            "на следующий лучший.",
                        checked = autoFailover,
                        onChecked = vm::setAutoFailover
                    )
                    ToggleRow(
                        title = "Свой DNS в туннеле",
                        subtitle = "Запросы DNS уходят через VPN — меньше утечек и блокировок.",
                        checked = localDns,
                        onChecked = vm::setLocalDns
                    )
                    ToggleRow(
                        title = "Беречь батарею",
                        subtitle = "Короткие проверки и меньше фоновой работы.",
                        checked = batterySaver,
                        onChecked = vm::setBatterySaver
                    )
                    ToggleRow(
                        title = "Подключаться при запуске",
                        subtitle = "Включать VPN сразу после старта системы.",
                        checked = autoConnect,
                        onChecked = vm::setAutoConnect
                    )
                }
            }

            item { SectionHeader("Внешний вид") }
            item {
                VoltaCard {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DesignTheme.entries.forEach { t ->
                            FilterChip(
                                selected = state.designTheme == t,
                                onClick = { vm.setDesignTheme(t) },
                                label = { Text(themeLabel(t)) }
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Устройство") }
            item {
                VoltaCard {
                    ToggleRow(
                        title = "Передавать ID устройства подписке",
                        subtitle = "Нужно сервисам, которые привязывают подписку к устройству.",
                        checked = sendHwid,
                        onChecked = vm::setSendHwid
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ID устройства", style = MaterialTheme.typography.titleSmall)
                            Hint(vm.hwidFull())
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(vm.hwidFull()))
                            vm.showMessage("ID скопирован")
                        }) {
                            Icon(Icons.Rounded.ContentCopy, "Скопировать")
                        }
                    }
                }
            }

            item { SectionHeader("О приложении") }
            item {
                VoltaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Volta 1.0.0", style = MaterialTheme.typography.titleSmall)
                            Hint(
                                if (vm.coreAvailable()) {
                                    "Ядро: ${vm.coreVersion().ifBlank { "Xray" }}"
                                } else {
                                    "Ядро VPN не установлено — работает только интерфейс"
                                }
                            )
                        }
                        StatusPill(
                            if (vm.coreAvailable()) "Ядро на месте" else "Нет ядра",
                            if (vm.coreAvailable()) palette.good else palette.poor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(subtitle)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun pingLabel(t: PingType) = when (t) {
    PingType.REAL_DELAY -> "Через сервер"
    PingType.HTTP_GET -> "HTTP GET"
    PingType.TCP -> "TCP"
    PingType.ICMP -> "ICMP"
}

private fun pingHint(t: PingType) = when (t) {
    PingType.REAL_DELAY -> "Точнее всего: реальный запрос через сервер силами ядра. " +
        "Работает даже при включённом VPN."
    PingType.HTTP_GET -> "GET-запрос прямо на адрес сервера. Хорошо ловит узлы за CDN, " +
        "которым мало простого TCP-рукопожатия."
    PingType.TCP -> "Быстро и дёшево, но показывает только доступность адреса."
    PingType.ICMP -> "Обычный ping. Многие операторы его режут."
}

private fun themeLabel(t: DesignTheme) = when (t) {
    DesignTheme.SYSTEM -> "Система"
    DesignTheme.VOLTA -> "Volta"
    DesignTheme.LIGHT -> "Светлая"
    DesignTheme.OCEAN -> "Океан"
    DesignTheme.EMBER -> "Уголь"
    DesignTheme.DYNAMIC -> "Обои (Material You)"
}
