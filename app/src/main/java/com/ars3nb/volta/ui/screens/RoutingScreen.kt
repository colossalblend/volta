package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.routing.HappRoutingParser
import com.ars3nb.volta.core.routing.Presets
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.routing.RoutingNames
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.core.routing.SitePreset
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.collectQuietState
import com.ars3nb.volta.ui.components.barColors
import com.ars3nb.volta.ui.components.CardShape
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.SmallShape
import com.ars3nb.volta.ui.components.StatusPill
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.RowFocusOutset
import com.ars3nb.volta.ui.tv.focusRing
import java.util.UUID
import com.ars3nb.volta.ui.components.shownProfileName
import com.ars3nb.volta.ui.components.shownProfileDescription
import com.ars3nb.volta.ui.components.shownSubscriptionName

/**
 * "Маршруты": what goes where.
 *
 *  - profiles decide where *sites* go (through the VPN, straight out, or nowhere);
 *  - filters (AdGuard, torrents) apply on top of whichever profile is in use;
 *  - split tunnelling decides which *apps* enter the tunnel at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(vm: VoltaViewModel, onOpenPerApp: () -> Unit) {
    val state by vm.collectQuietState()
    val adBlock by vm.adBlock.collectAsStateWithLifecycle()
    val blockTorrents by vm.blockTorrents.collectAsStateWithLifecycle()
    val perAppMode by vm.perAppMode.collectAsStateWithLifecycle()
    val perAppPackages by vm.perAppPackages.collectAsStateWithLifecycle()
    val dirty by vm.routingDirty.collectAsStateWithLifecycle()

    var editingId by remember { mutableStateOf<Long?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RoutingProfileEntity?>(null) }
    var deleting by remember { mutableStateOf<RoutingProfileEntity?>(null) }

    val profiles = state.routingProfiles
    val editing = profiles.firstOrNull { it.id == editingId }

    AnimatedContent(
        targetState = editing,
        transitionSpec = {
            if (targetState != null) {
                (slideInVertically { it / 6 } + fadeIn()) togetherWith fadeOut(tween(120))
            } else {
                fadeIn(tween(200)) togetherWith (slideOutVertically { it / 6 } + fadeOut())
            }
        },
        contentKey = { it?.id },
        label = "routingPage"
    ) { profile ->
        if (profile != null) {
            RuleEditorPage(vm = vm, profile = profile, onClose = { editingId = null })
        } else Scaffold(
            topBar = {
                TopAppBar(
                    colors = barColors(),
                    title = { Text(stringResource(R.string.routing_title), fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { showNew = true }) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.routing_new_profile))
                        }
                    }
                )
            }
        ) { padding ->
            val active = profiles.firstOrNull { it.isActive && it.isShareable }
            val shareable = profiles.filter { it.isShareable }
            val private = profiles.filterNot { it.isShareable }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (dirty && state.isConnected) {
                    item(key = "pending") {
                        PendingBanner(onApply = vm::applyRoutingNow, modifier = Modifier.animateItem())
                    }
                }

                if (active != null) {
                    item(key = "hero") {
                        ActiveProfileHero(
                            profile = active,
                            onEdit = { editingId = active.id },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                item(key = "filters-title") { SectionHeader(stringResource(R.string.routing_filters), Modifier.animateItem()) }
                item(key = "filters") {
                    FiltersCard(
                        adBlock = adBlock,
                        blockTorrents = blockTorrents,
                        onAdBlock = vm::setAdBlock,
                        onBlockTorrents = vm::setBlockTorrents,
                        modifier = Modifier.animateItem()
                    )
                }

                item(key = "apps-title") { SectionHeader(stringResource(R.string.routing_apps), Modifier.animateItem()) }
                item(key = "apps") {
                    SplitTunnelCard(
                        mode = perAppMode,
                        packages = perAppPackages,
                        onClick = onOpenPerApp,
                        modifier = Modifier.animateItem()
                    )
                }

                item(key = "profiles-title") {
                    Column(Modifier.animateItem()) {
                        SectionHeader(stringResource(R.string.routing_profiles))
                        Hint(stringResource(R.string.routing_profiles_hint))
                    }
                }
                items(shareable, key = { "profile-${it.id}" }) { p ->
                    ProfileTile(
                        profile = p,
                        selected = p.isActive,
                        ownerName = null,
                        onSelect = { vm.setActiveRouting(p.id) },
                        onEdit = { editingId = p.id },
                        onRename = { renaming = p },
                        onCopy = { vm.copyRoutingProfile(p.id) },
                        onDelete = { deleting = p },
                        onToggleProviderRules = null,
                        modifier = Modifier.animateItem()
                    )
                }

                if (private.isNotEmpty()) {
                    item(key = "private-title") {
                        Column(Modifier.animateItem()) {
                            SectionHeader(stringResource(R.string.routing_from_subscriptions))
                            Hint(stringResource(R.string.routing_from_subscriptions_hint))
                        }
                    }
                    items(private, key = { "private-${it.id}" }) { p ->
                        ProfileTile(
                            profile = p,
                            selected = false,
                            ownerName = state.subscriptions
                                .firstOrNull { it.id == p.sourceSubscriptionId }?.name,
                            onSelect = null,
                            onEdit = { editingId = p.id },
                            onRename = null,
                            onCopy = { vm.copyRoutingProfile(p.id) },
                            onDelete = null,
                            onToggleProviderRules = { vm.setRoutingProviderRules(p.id, it) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                if (state.subscriptions.isNotEmpty()) {
                    item(key = "subs-title") {
                        Column(Modifier.animateItem()) {
                            SectionHeader(stringResource(R.string.routing_subscriptions))
                            Hint(
                                stringResource(
                                    R.string.routing_subscriptions_hint,
                                    stringResource(R.string.routing_default)
                                )
                            )
                        }
                    }
                    item(key = "subs") {
                        VoltaCard(modifier = Modifier.animateItem(), contentPadding = 4) {
                            state.subscriptions.forEachIndexed { i, sub ->
                                if (i > 0) HorizontalDivider(color = VoltaTheme.palette.cardBorder)
                                SubscriptionRow(
                                    sub = sub,
                                    profiles = profiles,
                                    onBind = { id -> vm.bindSubscriptionRouting(sub.id, id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        NameDialog(
            title = stringResource(R.string.routing_new_profile),
            initial = "",
            confirm = stringResource(R.string.routing_create),
            onDismiss = { showNew = false },
            onConfirm = { name ->
                vm.createRoutingProfile(name)
                showNew = false
            }
        )
    }
    renaming?.let { p ->
        NameDialog(
            title = stringResource(R.string.routing_rename),
            initial = p.name,
            confirm = stringResource(R.string.routing_save),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                vm.renameRoutingProfile(p.id, name)
                renaming = null
            }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.routing_delete_title, shownProfileName(p))) },
            text = { Text(stringResource(R.string.routing_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRoutingProfile(p)
                    deleting = null
                }) { Text(stringResource(R.string.routing_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.routing_cancel)) }
            }
        )
    }
}

// ------------------------------------------------------------------ overview parts

@Composable
internal fun PendingBanner(onApply: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.routing_pending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onApply) { Text(stringResource(R.string.routing_apply)) }
        }
    }
}

/** How many enabled rules send traffic each way. */
private data class RuleMix(val proxy: Int, val direct: Int, val block: Int) {
    val total get() = proxy + direct + block
}

private fun mixOf(rules: List<RoutingRuleDto>): RuleMix {
    val on = rules.filter { it.enabled }
    return RuleMix(
        proxy = on.count { it.outbound == RouteOutbound.PROXY },
        direct = on.count { it.outbound == RouteOutbound.DIRECT },
        block = on.count { it.outbound == RouteOutbound.BLOCK }
    )
}

@Composable
private fun ActiveProfileHero(
    profile: RoutingProfileEntity,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val rules = remember(profile.rulesJson) {
        RoutingEngine.parseRules(profile.rulesJson)
    }
    val mix = mixOf(rules)
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                stringResource(R.string.routing_active_now),
                style = MaterialTheme.typography.labelMedium,
                color = palette.good
            )
            Spacer(Modifier.height(4.dp))
            AnimatedContent(
                targetState = shownProfileName(profile),
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "heroName"
            ) { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (profile.description.isNotBlank()) {
                Text(
                    shownProfileDescription(profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            RouteSummary(mix)
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.routing_edit_rules))
            }
        }
    }
}

/**
 * Where the profile sends traffic, as pills. Whatever no rule matches goes through the
 * VPN, so that is always the last one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteSummary(mix: RuleMix) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(RouteOutbound.DIRECT, RouteOutbound.BLOCK, RouteOutbound.PROXY).forEach { o ->
            val n = when (o) {
                RouteOutbound.PROXY -> mix.proxy
                RouteOutbound.DIRECT -> mix.direct
                RouteOutbound.BLOCK -> mix.block
            }
            if (n > 0) {
                StatusPill(
                    "${actionLabel(o)} · ${ruleCount(n)}",
                    outboundColor(o),
                    icon = outboundIcon(o)
                )
            }
        }
        StatusPill(
            stringResource(R.string.routing_rest_via_vpn),
            outboundColor(RouteOutbound.PROXY),
            icon = Icons.Rounded.VpnLock
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersCard(
    adBlock: AdBlockMode,
    blockTorrents: Boolean,
    onAdBlock: (AdBlockMode) -> Unit,
    onBlockTorrents: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    VoltaCard(modifier = modifier) {
        FilterRow(
            icon = Icons.Rounded.Shield,
            tint = palette.good,
            on = adBlock != AdBlockMode.OFF,
            title = stringResource(R.string.routing_adblock_title),
            subtitle = stringResource(R.string.routing_adblock_subtitle),
            onToggle = { onAdBlock(if (it) AdBlockMode.STANDARD else AdBlockMode.OFF) }
        )
        AnimatedVisibility(
            visible = adBlock != AdBlockMode.OFF,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(start = 48.dp, top = 4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = adBlock == AdBlockMode.STANDARD,
                        onClick = { onAdBlock(AdBlockMode.STANDARD) },
                        label = { Text(stringResource(R.string.routing_adblock_standard)) }
                    )
                    FilterChip(
                        selected = adBlock == AdBlockMode.STRICT,
                        onClick = { onAdBlock(AdBlockMode.STRICT) },
                        label = { Text(stringResource(R.string.routing_adblock_strict)) }
                    )
                    FilterChip(
                        selected = adBlock == AdBlockMode.FAMILY,
                        onClick = { onAdBlock(AdBlockMode.FAMILY) },
                        label = { Text(stringResource(R.string.routing_adblock_family)) }
                    )
                }
                // What the chosen mode does, in plain words; then what holds for all.
                Hint(
                    stringResource(
                        when (adBlock) {
                            AdBlockMode.STRICT -> R.string.routing_adblock_strict_hint
                            AdBlockMode.FAMILY -> R.string.routing_adblock_family_hint
                            else -> R.string.routing_adblock_standard_desc
                        }
                    )
                )
                Hint(
                    stringResource(R.string.routing_adblock_standard_hint),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Hint(
                    stringResource(R.string.routing_adblock_test_note),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        HorizontalDivider(
            color = palette.cardBorder,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        FilterRow(
            icon = Icons.Rounded.SwapVert,
            tint = palette.fair,
            on = blockTorrents,
            title = stringResource(R.string.routing_torrents_title),
            subtitle = stringResource(R.string.routing_torrents_subtitle),
            onToggle = onBlockTorrents
        )
    }
}

@Composable
private fun FilterRow(
    icon: ImageVector,
    tint: Color,
    on: Boolean,
    title: String,
    subtitle: String,
    onToggle: (Boolean) -> Unit
) {
    val bubble by animateColorAsState(
        if (on) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        label = "bubble"
    )
    val iconTint by animateColorAsState(
        if (on) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "iconTint"
    )
    val pop by animateFloatAsState(
        if (on) 1f else 0.88f,
        spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "pop"
    )
    Row(
        Modifier.fillMaxWidth().focusRing(SmallShape, outset = RowFocusOutset).clickable { onToggle(!on) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(bubble),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size((20 * pop).dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Hint(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        // The row toggles too: the remote stops on the row only.
        Switch(
            checked = on,
            onCheckedChange = onToggle,
            modifier = Modifier.focusProperties { canFocus = false }
        )
    }
}

@Composable
private fun SplitTunnelCard(
    mode: PerAppMode,
    packages: Set<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(
        when (mode) {
            PerAppMode.OFF -> R.string.routing_split_off
            PerAppMode.INCLUDE -> R.string.routing_split_include
            PerAppMode.EXCLUDE -> R.string.routing_split_exclude
        }
    )
    val subtitle = when {
        mode == PerAppMode.OFF -> stringResource(R.string.routing_split_off_hint)
        packages.isEmpty() -> stringResource(R.string.routing_split_empty)
        else -> pluralStringResource(R.plurals.routing_apps_count, packages.size, packages.size)
    }
    VoltaCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.routing_split_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                AnimatedContent(targetState = title, label = "splitTitle") { Hint(it) }
                Hint(subtitle)
            }
            if (mode != PerAppMode.OFF && packages.isNotEmpty()) {
                AppIconStack(packages.take(4))
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}

/** A few overlapping app icons, like avatars in a group chat. */
@Composable
private fun AppIconStack(packages: List<String>) {
    val context = LocalContext.current
    Box {
        packages.forEachIndexed { i, pkg ->
            val bitmap = remember(pkg) {
                runCatching {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .offset(x = (i * 14).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.width((24 + (packages.size - 1) * 14).dp))
    }
}

@Composable
private fun ProfileTile(
    profile: RoutingProfileEntity,
    selected: Boolean,
    ownerName: String?,
    onSelect: (() -> Unit)?,
    onEdit: () -> Unit,
    onRename: (() -> Unit)?,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?,
    onToggleProviderRules: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    var menu by remember { mutableStateOf(false) }
    val rules = remember(profile.rulesJson) {
        RoutingEngine.parseRules(profile.rulesJson)
    }
    val mix = mixOf(rules)
    VoltaCard(
        modifier = modifier.animateContentSize(),
        selected = selected,
        onClick = onSelect ?: onEdit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onSelect != null) {
                // The card picks the profile too: the remote stops on the card only.
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    modifier = Modifier.focusProperties { canFocus = false }
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Icon(
                    Icons.Rounded.Lock,
                    null,
                    tint = palette.fair,
                    modifier = Modifier.padding(start = 4.dp, end = 12.dp).size(18.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    shownProfileName(profile),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val kind = when {
                    ownerName != null -> stringResource(R.string.routing_profile_owner, ownerName)
                    profile.isBuiltIn -> stringResource(R.string.routing_profile_builtin)
                    else -> stringResource(R.string.routing_profile_custom)
                }
                Text(
                    kind + " · " + ruleCount(mix.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.routing_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routing_edit_rules)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onEdit() }
                    )
                    if (onRename != null && !profile.isBuiltIn) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.routing_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onRename() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routing_make_copy)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onCopy() }
                    )
                    if (onDelete != null && !profile.isBuiltIn) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.routing_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = selected || onSelect == null) {
            Column {
                Spacer(Modifier.height(10.dp))
                RouteSummary(mix)
            }
        }
        if (onToggleProviderRules != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.routing_provider_rules), style = MaterialTheme.typography.labelLarge)
                    Hint(
                        stringResource(
                            if (profile.useProviderRules) R.string.routing_provider_rules_on
                            else R.string.routing_provider_rules_off
                        )
                    )
                }
                Switch(checked = profile.useProviderRules, onCheckedChange = onToggleProviderRules)
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    sub: SubscriptionEntity,
    profiles: List<RoutingProfileEntity>,
    onBind: (Long) -> Unit
) {
    var expanded by remember(sub.id) { mutableStateOf(false) }
    val effective = RoutingResolver.resolve(sub, profiles)
    val choices = RoutingResolver.choicesFor(sub, profiles)
    val palette = VoltaTheme.palette
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    shownSubscriptionName(sub),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val inUse = effective?.let { shownProfileName(it) }
                    ?: stringResource(R.string.routing_sub_builtin_rules)
                Text(
                    if (sub.routingProfileId == 0L) stringResource(R.string.routing_sub_default, inUse) else inUse,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (sub.providerRoutingJson.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    StatusPill(stringResource(R.string.routing_sub_sent_rules), palette.good)
                }
            }
            Icon(Icons.Rounded.ExpandMore, stringResource(R.string.routing_choose_profile))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routing_default)) },
                leadingIcon = { RadioButton(selected = sub.routingProfileId == 0L, onClick = null) },
                onClick = { expanded = false; onBind(0L) }
            )
            choices.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (profile.isShareable) shownProfileName(profile)
                            else stringResource(R.string.routing_own_subscription, shownProfileName(profile))
                        )
                    },
                    leadingIcon = {
                        RadioButton(selected = sub.routingProfileId == profile.id, onClick = null)
                    },
                    onClick = { expanded = false; onBind(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.routing_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routing_cancel)) } }
    )
}

// ------------------------------------------------------------------ rule editor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorPage(
    vm: VoltaViewModel,
    profile: RoutingProfileEntity,
    onClose: () -> Unit
) {
    var rules by remember(profile.id) { mutableStateOf(vm.parseRules(profile.rulesJson)) }
    var changed by remember(profile.id) { mutableStateOf(false) }
    var expandedId by remember(profile.id) { mutableStateOf<String?>(null) }
    var showJson by remember { mutableStateOf(false) }

    fun update(next: List<RoutingRuleDto>) {
        rules = next
        changed = true
    }

    // Saved on the way out, whichever way that is: back, the arrow or another tab.
    val latestRules by rememberUpdatedState(rules)
    val latestChanged by rememberUpdatedState(changed)
    DisposableEffect(profile.id) {
        onDispose {
            if (latestChanged) vm.saveRules(profile.id, latestRules)
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Column {
                        Text(shownProfileName(profile), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ruleCount(rules.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.routing_done))
                    }
                },
                actions = {
                    IconButton(onClick = { showJson = true }) {
                        Icon(Icons.Rounded.Code, stringResource(R.string.routing_import_export))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "quick") {
                QuickAddCard(
                    onAdd = { entry, outbound ->
                        update(addEntry(rules, entry, outbound))
                    }
                )
            }
            item(key = "presets") {
                PresetsCard(
                    rules = rules,
                    onToggle = { preset ->
                        val existing = rules.firstOrNull { it.name == preset.title && it.source == "user" }
                        update(
                            if (existing != null) rules - existing
                            else listOf(preset.toRule()) + rules
                        )
                    }
                )
            }
            item(key = "rules-title") {
                SectionHeader(stringResource(R.string.routing_rules)) {
                    TextButton(onClick = {
                        val rule = RoutingRuleDto(
                            id = UUID.randomUUID().toString(),
                            name = RoutingNames.NEW_RULE,
                            outbound = RouteOutbound.PROXY,
                            source = "user"
                        )
                        update(rules + rule)
                        expandedId = rule.id
                    }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.routing_empty_rule))
                    }
                }
            }
            item(key = "rules-hint") {
                Hint(stringResource(R.string.routing_rules_hint))
            }
            if (rules.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.routing_rules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp).animateItem()
                    )
                }
            }
            itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
                RuleCard(
                    rule = rule,
                    expanded = expandedId == rule.id,
                    canMoveUp = index > 0,
                    canMoveDown = index < rules.lastIndex,
                    onExpand = { expandedId = if (expandedId == rule.id) null else rule.id },
                    onUpdate = { updated ->
                        update(rules.toMutableList().also { it[index] = updated })
                    },
                    onMove = { delta ->
                        val target = index + delta
                        if (target in rules.indices) {
                            update(rules.toMutableList().also {
                                val moved = it.removeAt(index)
                                it.add(target, moved)
                            })
                        }
                    },
                    onDelete = {
                        update(rules.toMutableList().also { it.removeAt(index) })
                        if (expandedId == rule.id) expandedId = null
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    if (showJson) {
        JsonDialog(
            initial = vm.serializeRules(rules),
            onImport = { text -> vm.importRules(text) },
            onApply = {
                update(it)
                showJson = false
            },
            onDismiss = { showJson = false }
        )
    }
}

/**
 * Quick-added sites gather in one rule per direction ("Свои сайты через VPN" and so on)
 * instead of a new card for every domain. The rule is found by its stored name, which is
 * why that stays the same in every language (see [RoutingNames]).
 */
private fun addEntry(
    rules: List<RoutingRuleDto>,
    entry: Presets.Entry,
    outbound: RouteOutbound
): List<RoutingRuleDto> {
    val name = when (outbound) {
        RouteOutbound.PROXY -> RoutingNames.MY_SITES_VPN
        RouteOutbound.DIRECT -> RoutingNames.MY_SITES_DIRECT
        RouteOutbound.BLOCK -> RoutingNames.BLOCKED_SITES
    }
    val index = rules.indexOfFirst { it.name == name && it.source == "user" }
    val base = if (index >= 0) rules[index] else RoutingRuleDto(
        id = UUID.randomUUID().toString(),
        name = name,
        outbound = outbound,
        source = "user"
    )
    val updated = when (entry) {
        is Presets.Entry.Domain -> base.copy(domains = (base.domains + entry.value).distinct())
        is Presets.Entry.Ip -> base.copy(ips = (base.ips + entry.value).distinct())
    }
    return if (index >= 0) {
        rules.toMutableList().also { it[index] = updated }
    } else {
        listOf(updated) + rules
    }
}

@Composable
private fun QuickAddCard(onAdd: (Presets.Entry, RouteOutbound) -> Unit) {
    var text by remember { mutableStateOf("") }
    var outbound by remember { mutableStateOf(RouteOutbound.PROXY) }
    var error by remember { mutableStateOf(false) }
    var lastAdded by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val entry = Presets.parseEntry(text)
        if (entry == null) {
            error = true
            return
        }
        onAdd(entry, outbound)
        lastAdded = entry.value.removePrefix("domain:")
        text = ""
    }

    val support = when {
        error -> stringResource(R.string.routing_not_a_site)
        lastAdded != null -> stringResource(R.string.routing_added, lastAdded.orEmpty())
        else -> null
    }
    VoltaCard {
        Text(stringResource(R.string.routing_add_site), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = false
            },
            placeholder = { Text(stringResource(R.string.routing_add_site_placeholder)) },
            singleLine = true,
            isError = error,
            supportingText = support?.let { line -> { Text(line) } },
            trailingIcon = {
                IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.routing_add))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutboundPicker(selected = outbound, onSelect = { outbound = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutboundPicker(selected: RouteOutbound, onSelect: (RouteOutbound) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteOutbound.entries.forEach { o ->
            val color = outboundColor(o)
            FilterChip(
                selected = selected == o,
                onClick = { onSelect(o) },
                label = { Text(shortLabel(o), maxLines = 1) },
                leadingIcon = { Icon(outboundIcon(o), null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.18f),
                    selectedLabelColor = color,
                    selectedLeadingIconColor = color
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsCard(rules: List<RoutingRuleDto>, onToggle: (SitePreset) -> Unit) {
    VoltaCard {
        Text(stringResource(R.string.routing_presets), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Hint(stringResource(R.string.routing_presets_hint))
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Presets.sites.forEach { preset ->
                val added = rules.firstOrNull { it.name == preset.title && it.source == "user" }
                val color = outboundColor(added?.outbound ?: preset.suggested)
                FilterChip(
                    selected = added != null,
                    onClick = { onToggle(preset) },
                    label = { Text(preset.label?.let { stringResource(it) } ?: preset.title) },
                    leadingIcon = {
                        AnimatedContent(targetState = added != null, label = "presetIcon") { on ->
                            Icon(
                                if (on) Icons.Rounded.Check else outboundIcon(preset.suggested),
                                null,
                                Modifier.size(16.dp)
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.18f),
                        selectedLabelColor = color,
                        selectedLeadingIconColor = color
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: RoutingRuleDto,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onExpand: () -> Unit,
    onUpdate: (RoutingRuleDto) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(outboundColor(rule.outbound), label = "ruleColor")
    val alpha by animateFloatAsState(if (rule.enabled) 1f else 0.45f, label = "ruleAlpha")
    VoltaCard(modifier = modifier.animateContentSize(), contentPadding = 0) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRing(CardShape)
                .clickable(onClick = onExpand)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.16f * alpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(outboundIcon(rule.outbound), null, tint = color.copy(alpha = alpha), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    shownRuleName(rule.name).ifBlank { stringResource(R.string.routing_rule_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    actionLabel(rule.outbound) + " · " + summary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onUpdate(rule.copy(enabled = it)) })
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (rule.source != "user") {
                    Hint(
                        stringResource(
                            if (rule.source == "provider") R.string.routing_rule_from_provider
                            else R.string.routing_rule_builtin
                        )
                    )
                }
                // Shows a name Volta gave in the app's language; typing makes it the user's own.
                OutlinedTextField(
                    value = shownRuleName(rule.name),
                    onValueChange = { onUpdate(rule.copy(name = it)) },
                    label = { Text(stringResource(R.string.routing_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutboundPicker(selected = rule.outbound, onSelect = { onUpdate(rule.copy(outbound = it)) })
                Text(stringResource(R.string.routing_sites_and_addresses), style = MaterialTheme.typography.labelLarge)
                EntryField(
                    rule = rule,
                    onChange = onUpdate
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.routing_move_up))
                    }
                    IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.routing_move_down))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.routing_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Sites and IPs of one rule as removable chips, plus a field to add more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryField(rule: RoutingRuleDto, onChange: (RoutingRuleDto) -> Unit) {
    var text by remember(rule.id) { mutableStateOf("") }
    var showAll by remember(rule.id) { mutableStateOf(false) }
    val entries = rule.domains.map { true to it } + rule.ips.map { false to it }
    val visible = if (showAll) entries else entries.take(12)

    fun add() {
        val parts = text.split(',', ' ', '\n').mapNotNull { Presets.parseEntry(it) }
        if (parts.isEmpty()) return
        var next = rule
        parts.forEach { entry ->
            next = when (entry) {
                is Presets.Entry.Domain -> next.copy(domains = (next.domains + entry.value).distinct())
                is Presets.Entry.Ip -> next.copy(ips = (next.ips + entry.value).distinct())
            }
        }
        onChange(next)
        text = ""
    }

    if (entries.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { (isDomain, value) ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(value.removePrefix("domain:"), maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            Icons.Rounded.Close,
                            stringResource(R.string.routing_remove),
                            Modifier.size(16.dp).clickable {
                                onChange(
                                    if (isDomain) rule.copy(domains = rule.domains - value)
                                    else rule.copy(ips = rule.ips - value)
                                )
                            }
                        )
                    }
                )
            }
            if (entries.size > 12) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(
                        if (showAll) stringResource(R.string.routing_collapse)
                        else pluralStringResource(R.plurals.routing_more_count, entries.size - 12, entries.size - 12)
                    )
                }
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.routing_add_site_or_address)) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { add() }, enabled = text.isNotBlank()) {
                Icon(Icons.Rounded.Add, stringResource(R.string.routing_add))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun JsonDialog(
    initial: String,
    onImport: (String) -> List<RoutingRuleDto>,
    onApply: (List<RoutingRuleDto>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_import_export)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                supportingText = error?.let { e -> { Text(e) } },
                isError = error != null,
                minLines = 6,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                runCatching { onImport(text) }
                    .onSuccess(onApply)
                    .onFailure { e ->
                        error = (e as? RoutingEngine.RulesFormatException)?.let { context.getString(it.reason) }
                            ?: e.message
                            ?: context.getString(R.string.routing_json_broken)
                    }
            }) { Text(stringResource(R.string.routing_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routing_close)) } }
    )
}

// ------------------------------------------------------------------ helpers

@Composable
private fun outboundColor(o: RouteOutbound): Color {
    val palette = VoltaTheme.palette
    return when (o) {
        RouteOutbound.PROXY -> MaterialTheme.colorScheme.primary
        RouteOutbound.DIRECT -> palette.good
        RouteOutbound.BLOCK -> palette.poor
    }
}

private fun outboundIcon(o: RouteOutbound): ImageVector = when (o) {
    RouteOutbound.PROXY -> Icons.Rounded.VpnLock
    RouteOutbound.DIRECT -> Icons.Rounded.Public
    RouteOutbound.BLOCK -> Icons.Rounded.Block
}

@Composable
private fun summary(rule: RoutingRuleDto): String {
    val parts = buildList {
        if (rule.domains.isNotEmpty()) add(siteCount(rule.domains.size))
        if (rule.ips.isNotEmpty()) add(addressCount(rule.ips.size))
        // Ports and protocols come only from providers; they are not spelled out here.
        if (rule.ports.isNotBlank() || rule.protocol.isNotEmpty()) add(stringResource(R.string.routing_special_conditions))
    }
    return if (parts.isEmpty()) stringResource(R.string.routing_no_conditions) else parts.joinToString(", ")
}

@Composable
private fun shortLabel(o: RouteOutbound) = when (o) {
    RouteOutbound.PROXY -> "VPN"
    RouteOutbound.DIRECT -> stringResource(R.string.routing_direct)
    RouteOutbound.BLOCK -> stringResource(R.string.routing_short_block)
}

@Composable
private fun actionLabel(o: RouteOutbound) = stringResource(
    when (o) {
        RouteOutbound.PROXY -> R.string.routing_action_vpn
        RouteOutbound.DIRECT -> R.string.routing_direct
        RouteOutbound.BLOCK -> R.string.routing_action_block
    }
)

@Composable
private fun addressCount(n: Int) = pluralStringResource(R.plurals.routing_addresses_count, n, n)

@Composable
private fun ruleCount(n: Int) = pluralStringResource(R.plurals.routing_rules_count, n, n)

@Composable
private fun siteCount(n: Int) = pluralStringResource(R.plurals.routing_sites_count, n, n)

/** A rule's name as shown, likewise. */
@Composable
private fun shownRuleName(name: String): String {
    val shown = RoutingNames.rule(name)
    return if (shown == null) name
    else stringResource(shown.first) + if (shown.second) HappRoutingParser.IP_SUFFIX else ""
}
