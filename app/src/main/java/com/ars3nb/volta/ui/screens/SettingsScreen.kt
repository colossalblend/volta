package com.ars3nb.volta.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.core.update.UpdateInfo
import com.ars3nb.volta.core.update.UpdateState
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.ui.components.AppIconPicker
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.StatusPill
import com.ars3nb.volta.ui.components.UpdateAction
import com.ars3nb.volta.ui.components.UpdateProgress
import com.ars3nb.volta.ui.components.updateHint
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.modeIcon
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tile.GroupTileService
import com.ars3nb.volta.ui.tile.TileInstaller

@OptIn(ExperimentalMaterial3Api::class)
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
    val updateToken by vm.updateToken.collectAsStateWithLifecycle()
    var editingToken by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val palette = VoltaTheme.palette

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки", fontWeight = FontWeight.SemiBold) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                VoltaCard {
                    ToggleRow(
                        title = "Простой режим",
                        subtitle = "Для пожилых и для всех, кому нужна одна кнопка: на экране " +
                            "только огромная кнопка «Включить / Выключить». Сервер выбирается " +
                            "сам (Авто Home + LTE).",
                        checked = simpleMode == true,
                        onChecked = vm::setSimpleMode
                    )
                }
            }
            item { SectionHeader("Подключение") }
            item {
                VoltaCard {
                    ToggleRow(
                        title = "Следить за сменой сети",
                        subtitle = "При переходе между Wi-Fi и мобильным интернетом сервер " +
                            "меняется сам.",
                        checked = followNetwork,
                        onChecked = vm::setFollowNetwork
                    )
                    ToggleRow(
                        title = "Улучшать выбор сервера",
                        subtitle = "Раз в 15 минут при включённом экране сравнивает серверы " +
                            "и переходит на заметно более быстрый. Неотвечающий сервер " +
                            "заменяется всегда.",
                        checked = autoRecheck,
                        onChecked = vm::setAutoRecheck
                    )
                    ToggleRow(
                        title = "Обновлять подписки при запуске",
                        subtitle = "Серверы и правила обновляются при открытии приложения.",
                        checked = refreshOnLaunch,
                        onChecked = vm::setRefreshOnLaunch
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_VPN_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Постоянный VPN",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Hint(
                                "Android будет сам включать Volta. Откройте шестерёнку " +
                                    "рядом с Volta."
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }

            item { SectionHeader("Плитки в шторке") }
            item {
                VoltaCard {
                    Text("Home+LTE, Home и LTE", style = MaterialTheme.typography.titleSmall)
                    Hint("Нажатие включает VPN в этом режиме, повторное — выключает.")
                    Spacer(Modifier.height(10.dp))
                    if (TileInstaller.supported) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ConnectMode.AUTO_HOME_LTE,
                                ConnectMode.AUTO_HOME,
                                ConnectMode.AUTO_LTE
                            ).forEach { mode ->
                                FilledTonalButton(
                                    onClick = {
                                        TileInstaller.request(context, mode) { text ->
                                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(modeIcon(mode), null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Добавить плитку «${GroupTileService.label(mode)}»")
                                }
                            }
                        }
                    } else {
                        Hint(TileInstaller.MANUAL_HINT)
                    }
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
                        subtitle = "Сразу видно, какая сейчас сеть: Home или LTE.",
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
                        subtitle = "Если сервер перестал отвечать, Volta перейдёт на другой.",
                        checked = autoFailover,
                        onChecked = vm::setAutoFailover
                    )
                    ToggleRow(
                        title = "Свой DNS в туннеле",
                        subtitle = "Меньше блокировок сайтов.",
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
            item {
                VoltaCard {
                    Text(
                        "Иконка приложения",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    AppIconPicker()
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

            item { SectionHeader("Обновления") }
            item {
                VoltaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Volta ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Hint(
                                when (val s = update) {
                                    is UpdateState.Available,
                                    is UpdateState.Downloading,
                                    is UpdateState.Ready ->
                                        "Новая версия ${updateInfo(s)?.versionName}. ${updateHint(s)}"

                                    else -> updateHint(s)
                                }
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        UpdateAction(update, onCheck = vm::checkForUpdate, onInstall = vm::installUpdate)
                    }
                    UpdateProgress(update)
                    Spacer(Modifier.height(6.dp))
                    ToggleRow(
                        title = "Проверять автоматически",
                        subtitle = "При запуске и раз в несколько часов в фоне. О новой версии " +
                            "придёт уведомление.",
                        checked = updateAutoCheck,
                        onChecked = vm::setUpdateAutoCheck
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingToken = true }
                            .padding(vertical = 10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Токен GitHub", style = MaterialTheme.typography.titleSmall)
                            Hint(
                                if (updateToken.isBlank()) {
                                    "Нужен, только если сборки лежат в закрытом репозитории."
                                } else {
                                    "Задан: …${updateToken.takeLast(4)}"
                                }
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }

            item { SectionHeader("О приложении") }
            item {
                VoltaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Volta ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Hint(
                                if (vm.coreAvailable()) {
                                    "Ядро: ${vm.coreVersion().ifBlank { "Xray" }}"
                                } else {
                                    "Ядро VPN не установлено — работает только интерфейс"
                                }
                            )
                            Hint(
                                if (vm.openFluxAvailable()) {
                                    "OpenFlux: github.com/p1neappleXpress/OpenFlux (GPL-3.0)"
                                } else {
                                    "OpenFlux не входит в эту сборку"
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

    if (editingToken) {
        TokenDialog(
            current = updateToken,
            onSave = {
                vm.setUpdateToken(it)
                editingToken = false
            },
            onDismiss = { editingToken = false }
        )
    }
}

/** A read-only GitHub token, for updates from the private repository. */
@Composable
private fun TokenDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Токен GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint(
                    "Токен с доступом «Contents: Read-only» только к репозиторию Volta. " +
                        "Хранится на этом телефоне и уходит только на GitHub. Если сборки " +
                        "лежат в публичном volta-releases, токен не нужен."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    placeholder = { Text("github_pat_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("Сохранить") }
        },
        dismissButton = {
            if (current.isNotBlank()) {
                TextButton(onClick = { onSave("") }) { Text("Удалить") }
            } else {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

private fun updateInfo(state: UpdateState): UpdateInfo? = when (state) {
    is UpdateState.Available -> state.info
    is UpdateState.Downloading -> state.info
    is UpdateState.Ready -> state.info
    is UpdateState.Failed -> state.info
    else -> null
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
    PingType.REAL_DELAY -> "Точнее всего, рекомендуем."
    PingType.HTTP_GET -> "Подходит для серверов за CDN."
    PingType.TCP -> "Быстро, но менее точно."
    PingType.ICMP -> "Обычный ping, у многих операторов не работает."
}

private fun themeLabel(t: DesignTheme) = when (t) {
    DesignTheme.SYSTEM -> "Система"
    DesignTheme.VOLTA -> "Volta"
    DesignTheme.LIGHT -> "Светлая"
    DesignTheme.OCEAN -> "Океан"
    DesignTheme.EMBER -> "Уголь"
    DesignTheme.DYNAMIC -> "Обои (Material You)"
}
