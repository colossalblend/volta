package com.ars3nb.volta.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Checkbox
import com.ars3nb.volta.ui.components.VoltaChip
import com.ars3nb.volta.ui.components.VoltaSearchField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.apps.AppPresets
import com.ars3nb.volta.core.apps.AppRoute
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.barColors
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.SmallShape
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.VoltaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean
)

/**
 * Split tunnelling: pick apps, then decide whether the list is what goes *through* the
 * tunnel or what stays outside it. Ready-made sets pick every installed app of a kind in
 * one tap: Russian services (banks, Госуслуги, marketplaces…) to let past the VPN, or
 * foreign ones (YouTube, messengers, AI chats…) to send through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppScreen(vm: VoltaViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val dirty by vm.routingDirty.collectAsStateWithLifecycle()
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

    val installed = remember(apps) { apps.mapTo(HashSet()) { it.packageName } }
    val visible = remember(apps, query, showSystem, selected) {
        apps.asSequence()
            .filter { showSystem || !it.system || it.packageName in selected }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
    val chosen = visible.filter { it.packageName in selected }
    val rest = visible.filterNot { it.packageName in selected }

    // Picking something while the mode is off can only mean "let these bypass the VPN".
    fun change(packages: Collection<String>, add: Boolean) {
        vm.setPerAppPackages(if (add) selected + packages else selected - packages.toSet())
        if (add && mode == PerAppMode.OFF) vm.setPerAppMode(PerAppMode.EXCLUDE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.routing_split_title), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AnimatedContent(targetState = mode, label = "perAppSubtitle") { m ->
                            Text(
                                modeTitle(m),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.routing_back))
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { vm.setPerAppPackages(emptySet()) }) {
                            Text(stringResource(R.string.routing_reset))
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
            if (dirty && state.isConnected) {
                item(key = "pending") {
                    PendingBanner(onApply = vm::applyRoutingNow, modifier = Modifier.animateItem())
                }
            }

            item(key = "mode") {
                VoltaCard(modifier = Modifier.animateItem()) {
                    PerAppModeSelector(mode = mode, onSelect = vm::setPerAppMode)
                    Spacer(Modifier.height(10.dp))
                    AnimatedContent(targetState = mode, label = "perAppHint") { m -> Hint(modeHint(m)) }
                    if (mode != PerAppMode.OFF && selected.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Hint(stringResource(R.string.routing_apps_empty_list))
                    } else if (mode == PerAppMode.OFF && selected.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Hint(stringResource(R.string.routing_apps_saved_inactive))
                    }
                }
            }

            // Only once the installed apps are known: a set shows and picks only those.
            if (!loading) {
                item(key = "presets") {
                    AppPresetsSection(
                        mode = mode,
                        installed = installed,
                        selected = selected,
                        onToggle = { packages, add -> change(packages, add) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item(key = "search") {
                VoltaSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.routing_search_app),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            item(key = "system") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VoltaChip(
                        text = stringResource(R.string.routing_system_apps),
                        selected = showSystem,
                        onClick = { showSystem = !showSystem }
                    )
                    Spacer(Modifier.size(8.dp))
                    Hint(stringResource(R.string.routing_selected_count, selected.size))
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Apps,
                        title = stringResource(R.string.routing_nothing_found),
                        subtitle = stringResource(R.string.routing_nothing_found_hint)
                    )
                }
            }

            if (chosen.isNotEmpty()) {
                item(key = "chosen-title") {
                    SectionHeader(stringResource(R.string.routing_selected_count, chosen.size), Modifier.animateItem())
                }
                items(chosen, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = true,
                        mode = mode,
                        onToggle = { change(listOf(app.packageName), add = false) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
            if (rest.isNotEmpty()) {
                item(key = "rest-title") {
                    SectionHeader(
                        stringResource(if (chosen.isEmpty()) R.string.routing_apps else R.string.routing_others),
                        Modifier.animateItem()
                    )
                }
                items(rest, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = false,
                        mode = mode,
                        onToggle = { change(listOf(app.packageName), add = true) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/** Three segments with a highlight that slides to the chosen one. */
@Composable
private fun PerAppModeSelector(mode: PerAppMode, onSelect: (PerAppMode) -> Unit) {
    val options = PerAppMode.entries
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(SmallShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(4.dp)
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(
            targetValue = segment * options.indexOf(mode),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            label = "modeIndicator"
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(shape)
                .background(primary.copy(alpha = 0.16f))
                .border(1.dp, primary.copy(alpha = 0.45f), shape)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val on = option == mode
                val tint by animateColorAsState(
                    if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "modeTint"
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable { onSelect(option) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(modeIcon(option), null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        modeLabel(option),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * The ready-made sets as chips. Which sets are offered follows the mode: Russian services
 * while the list is what bypasses the VPN, foreign ones while it is what goes through it.
 * A chip counts and picks only the apps that are on the phone, and a set with none of
 * them is left out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppPresetsSection(
    mode: PerAppMode,
    installed: Set<String>,
    selected: Set<String>,
    onToggle: (packages: List<String>, add: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val route = if (mode == PerAppMode.INCLUDE) AppRoute.VPN else AppRoute.DIRECT
    val accent = routeColor(if (route == AppRoute.VPN) PerAppMode.INCLUDE else PerAppMode.EXCLUDE)
    val sets = remember(route, installed) {
        AppPresets.forRoute(route)
            .map { preset -> preset to preset.packages.filter { it in installed } }
            .filter { (_, present) -> present.isNotEmpty() }
    }
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.routing_app_presets))
        Hint(presetsHint(route))
        Spacer(Modifier.height(10.dp))
        if (sets.isEmpty()) {
            Hint(stringResource(R.string.routing_app_presets_none))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sets.size > 1) {
                    val everything = sets.flatMap { it.second }
                    val on = everything.all { it in selected }
                    VoltaChip(
                        text = stringResource(R.string.routing_app_presets_all, everything.size),
                        selected = on,
                        onClick = { onToggle(everything, !on) },
                        accent = accent,
                        icon = if (on) Icons.Rounded.Check else Icons.Rounded.Apps
                    )
                }
                sets.forEach { (preset, present) ->
                    val on = present.all { it in selected }
                    VoltaChip(
                        text = "${stringResource(preset.title)} · ${present.size}",
                        selected = on,
                        onClick = { onToggle(present, !on) },
                        accent = accent,
                        icon = if (on) Icons.Rounded.Check else presetIcon(preset.id)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint(otherSetsHint(route))
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    mode: PerAppMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = routeColor(mode)
    val background by animateColorAsState(
        if (checked) color.copy(alpha = 0.10f) else Color.Transparent,
        label = "appRow"
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .background(background)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InstalledAppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (checked) {
                Text(
                    routeLabel(mode),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            } else {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Icons are decoded off the main thread so a long list scrolls smoothly. */
@Composable
private fun InstalledAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val icon = bitmap
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(38.dp))
    } else {
        Box(
            Modifier
                .size(38.dp)
                .clip(SmallShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
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

@Composable
private fun routeColor(mode: PerAppMode): Color = when (mode) {
    PerAppMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    PerAppMode.INCLUDE -> MaterialTheme.colorScheme.primary
    PerAppMode.EXCLUDE -> VoltaTheme.palette.home
}

private fun modeIcon(mode: PerAppMode): ImageVector = when (mode) {
    PerAppMode.OFF -> Icons.Rounded.Apps
    PerAppMode.INCLUDE -> Icons.Rounded.VpnLock
    PerAppMode.EXCLUDE -> Icons.Rounded.Public
}

@Composable
private fun modeLabel(mode: PerAppMode) = stringResource(
    when (mode) {
        PerAppMode.OFF -> R.string.routing_mode_all
        PerAppMode.INCLUDE -> R.string.routing_mode_only
        PerAppMode.EXCLUDE -> R.string.routing_mode_except
    }
)

@Composable
private fun modeTitle(mode: PerAppMode) = stringResource(
    when (mode) {
        PerAppMode.OFF -> R.string.routing_split_off
        PerAppMode.INCLUDE -> R.string.routing_mode_include_title
        PerAppMode.EXCLUDE -> R.string.routing_split_exclude
    }
)

@Composable
private fun modeHint(mode: PerAppMode) = stringResource(
    when (mode) {
        PerAppMode.OFF -> R.string.routing_mode_off_hint
        PerAppMode.INCLUDE -> R.string.routing_mode_include_hint
        PerAppMode.EXCLUDE -> R.string.routing_mode_exclude_hint
    }
)

@Composable
private fun presetsHint(route: AppRoute) = stringResource(
    when (route) {
        AppRoute.DIRECT -> R.string.routing_presets_direct_hint
        AppRoute.VPN -> R.string.routing_presets_vpn_hint
    }
)

/** Names the mode where the other sets are, in the words of the mode switch itself. */
@Composable
private fun otherSetsHint(route: AppRoute) = when (route) {
    AppRoute.DIRECT -> stringResource(R.string.routing_other_sets_direct, modeLabel(PerAppMode.INCLUDE))
    AppRoute.VPN -> stringResource(R.string.routing_other_sets_vpn, modeLabel(PerAppMode.EXCLUDE))
}

private fun presetIcon(id: String): ImageVector = when (id) {
    "banks" -> Icons.Rounded.CreditCard
    "gov" -> Icons.Rounded.AccountBalance
    "shops" -> Icons.Rounded.ShoppingBag
    "food" -> Icons.Rounded.Restaurant
    "yandex" -> Icons.Rounded.Search
    "vk" -> Icons.Rounded.Groups
    "media" -> Icons.Rounded.Movie
    "travel" -> Icons.Rounded.Map
    "health" -> Icons.Rounded.MedicalServices
    "carriers" -> Icons.Rounded.SimCard
    "youtube" -> Icons.Rounded.SmartDisplay
    "messengers" -> Icons.Rounded.Forum
    "social" -> Icons.Rounded.Tag
    "ai" -> Icons.Rounded.AutoAwesome
    "streaming" -> Icons.Rounded.Headphones
    "work" -> Icons.Rounded.Work
    "browsers" -> Icons.Rounded.Language
    else -> Icons.Rounded.Apps
}

@Composable
private fun routeLabel(mode: PerAppMode) = stringResource(
    when (mode) {
        PerAppMode.OFF -> R.string.routing_route_selected_off
        PerAppMode.INCLUDE -> R.string.routing_action_vpn
        PerAppMode.EXCLUDE -> R.string.routing_route_bypass
    }
)
