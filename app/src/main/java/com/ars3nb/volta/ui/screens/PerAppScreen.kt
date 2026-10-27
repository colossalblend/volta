package com.ars3nb.volta.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.VoltaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean
)

/**
 * Split tunnelling, the way Happ and similar clients do it: pick apps, then decide
 * whether the list is what goes *through* the tunnel or what stays outside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(vm: VoltaViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val mode by vm.perAppMode.collectAsStateWithLifecycle()
    val selected by vm.perAppPackages.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context.packageManager) }
        loading = false
    }

    val visible = remember(apps, query, showSystem, selected) {
        apps.asSequence()
            .filter { showSystem || !it.system || it.packageName in selected }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in selected }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Приложения", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { vm.setPerAppPackages(emptySet()) }) {
                            Text("Сбросить")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                VoltaCard {
                    Text("Режим", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PerAppMode.entries.forEach { option ->
                            FilterChip(
                                selected = mode == option,
                                onClick = { vm.setPerAppMode(option) },
                                label = { Text(modeLabel(option)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Hint(modeHint(mode))
                    if (mode != PerAppMode.OFF && selected.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Hint("Список пуст — режим не применится, VPN покроет все приложения.")
                    }
                }
            }

            if (mode != PerAppMode.OFF) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск приложения") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, "Очистить")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = showSystem,
                            onClick = { showSystem = !showSystem },
                            label = { Text("Системные") }
                        )
                        Spacer(Modifier.size(8.dp))
                        Hint("Выбрано: ${selected.size}")
                    }
                }

                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (visible.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Apps,
                            title = "Ничего не нашлось",
                            subtitle = "Измените запрос или включите показ системных приложений."
                        )
                    }
                }

                items(visible, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    ListItem(
                        headlineContent = {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                app.packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = { AppIcon(app.packageName) },
                        trailingContent = {
                            Checkbox(checked = checked, onCheckedChange = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.setPerAppPackages(
                                    if (checked) selected - app.packageName
                                    else selected + app.packageName
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Icon(Icons.Rounded.Apps, null, Modifier.size(36.dp))
    }
}

/** Only apps that can actually use the network are worth listing. */
private fun loadApps(pm: PackageManager): List<InstalledApp> = runCatching {
    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        .asSequence()
        .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
        .mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(info.packageName),
                system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .toList()
}.getOrDefault(emptyList())

private fun modeLabel(mode: PerAppMode) = when (mode) {
    PerAppMode.OFF -> "Все через VPN"
    PerAppMode.INCLUDE -> "Только выбранные"
    PerAppMode.EXCLUDE -> "Кроме выбранных"
}

private fun modeHint(mode: PerAppMode) = when (mode) {
    PerAppMode.OFF -> "Туннель охватывает все приложения на устройстве."
    PerAppMode.INCLUDE -> "Через VPN пойдут только отмеченные приложения, остальные — напрямую."
    PerAppMode.EXCLUDE -> "Отмеченные приложения пойдут мимо VPN, остальные — через туннель."
}
