package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.StatusPill
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.VoltaTheme
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(vm: VoltaViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutingProfileEntity?>(null) }
    var rules by remember { mutableStateOf<List<RoutingRuleDto>>(emptyList()) }
    var showNew by remember { mutableStateOf(false) }

    val profiles = state.routingProfiles

    // System back leaves the editor instead of the screen, so edits are not lost silently.
    androidx.activity.compose.BackHandler(enabled = editing != null) { editing = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editing == null) "Правила" else "Изменить правила",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (editing != null) {
                        IconButton(onClick = { editing = null }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                        }
                    }
                },
                actions = {
                    if (editing == null) {
                        IconButton(onClick = { showNew = true }) {
                            Icon(Icons.Rounded.Add, "Новый набор")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val editingProfile = editing
        if (editingProfile != null) {
            RuleEditor(
                modifier = Modifier.padding(padding),
                rules = rules,
                onChange = { rules = it },
                onImportJson = vm::importRules,
                onExportJson = vm::serializeRules,
                onSave = {
                    vm.saveRules(editingProfile.id, rules)
                    editing = null
                },
                onAdd = {
                    rules = rules + RoutingRuleDto(
                        id = UUID.randomUUID().toString(),
                        name = "Новое правило",
                        outbound = RouteOutbound.PROXY,
                        source = "user"
                    )
                }
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Hint(
                    "Каждая подписка работает со своим набором правил. Набор, пришедший от " +
                        "подписки, доступен только ей — другие подписки его не видят и " +
                        "не могут применить."
                )
            }

            if (state.subscriptions.isNotEmpty()) {
                item { SectionHeader("Правила для подписок") }
                items(state.subscriptions, key = { "bind-${it.id}" }) { sub ->
                    SubscriptionRoutingCard(
                        sub = sub,
                        profiles = profiles,
                        onBind = { id -> vm.bindSubscriptionRouting(sub.id, id) }
                    )
                }
            }

            item { SectionHeader("Набор по умолчанию") }
            item {
                Hint("Используется серверами, добавленными вручную, и подписками без своего выбора.")
            }

            val shareable = profiles.filter { it.isShareable }
            items(shareable, key = { "profile-${it.id}" }) { profile ->
                ProfileCard(
                    profile = profile,
                    isDefault = profile.isActive,
                    ownerName = null,
                    onMakeDefault = { vm.setActiveRouting(profile.id) },
                    onEdit = {
                        rules = vm.parseRules(profile.rulesJson)
                        editing = profile
                    },
                    onCopy = { vm.copyRoutingProfile(profile.id) },
                    onDelete = { vm.deleteRoutingProfile(profile) },
                    onToggleProviderRules = { vm.setRoutingProviderRules(profile.id, it) }
                )
            }

            val private = profiles.filterNot { it.isShareable }
            if (private.isNotEmpty()) {
                item { SectionHeader("Наборы из подписок") }
                item {
                    Hint(
                        "Изолированы: применяются только к своей подписке. " +
                            "Чтобы использовать такие правила где-то ещё, сделайте копию."
                    )
                }
                items(private, key = { "private-${it.id}" }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isDefault = false,
                        ownerName = state.subscriptions
                            .firstOrNull { it.id == profile.sourceSubscriptionId }?.name,
                        onMakeDefault = null,
                        onEdit = {
                            rules = vm.parseRules(profile.rulesJson)
                            editing = profile
                        },
                        onCopy = { vm.copyRoutingProfile(profile.id) },
                        onDelete = null,
                        onToggleProviderRules = { vm.setRoutingProviderRules(profile.id, it) }
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showNew) {
        NewProfileDialog(
            onDismiss = { showNew = false },
            onCreate = { name ->
                vm.createRoutingProfile(name)
                showNew = false
            }
        )
    }
}

@Composable
private fun SubscriptionRoutingCard(
    sub: SubscriptionEntity,
    profiles: List<RoutingProfileEntity>,
    onBind: (Long) -> Unit
) {
    var expanded by remember(sub.id) { mutableStateOf(false) }
    val effective = RoutingResolver.resolve(sub, profiles)
    val choices = RoutingResolver.choicesFor(sub, profiles)
    val palette = VoltaTheme.palette

    VoltaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    sub.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    effective?.name ?: "Встроенные правила",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Rounded.ExpandMore, "Выбрать правила")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("По умолчанию") },
                        leadingIcon = {
                            RadioButton(selected = sub.routingProfileId == 0L, onClick = null)
                        },
                        onClick = { expanded = false; onBind(0L) }
                    )
                    choices.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    profile.name +
                                        if (!profile.isShareable) " · этой подписки" else ""
                                )
                            },
                            leadingIcon = {
                                RadioButton(
                                    selected = sub.routingProfileId == profile.id,
                                    onClick = null
                                )
                            },
                            onClick = { expanded = false; onBind(profile.id) }
                        )
                    }
                }
            }
        }
        if (sub.providerRoutingJson.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            StatusPill("Сервис прислал свои правила", palette.good)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: RoutingProfileEntity,
    isDefault: Boolean,
    ownerName: String?,
    onMakeDefault: (() -> Unit)?,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?,
    onToggleProviderRules: (Boolean) -> Unit
) {
    val palette = VoltaTheme.palette
    VoltaCard(selected = isDefault) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!profile.isShareable) {
                        Icon(
                            Icons.Rounded.Lock,
                            null,
                            tint = palette.fair,
                            modifier = Modifier.padding(end = 5.dp).size(14.dp)
                        )
                    }
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (profile.description.isNotBlank()) {
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onMakeDefault != null) {
                Switch(
                    checked = isDefault,
                    onCheckedChange = { if (it) onMakeDefault() }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                profile.isBuiltIn -> StatusPill("Встроенный", MaterialTheme.colorScheme.primary)
                ownerName != null -> StatusPill("Только «$ownerName»", palette.fair)
                else -> StatusPill("Свой", palette.good)
            }
        }

        if (!profile.isShareable) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Правила провайдера", style = MaterialTheme.typography.labelLarge)
                    Hint(
                        if (profile.useProviderRules) "Применяются как есть"
                        else "Используются только ваши изменения"
                    )
                }
                Switch(
                    checked = profile.useProviderRules,
                    onCheckedChange = onToggleProviderRules
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row {
            TextButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Изменить")
            }
            TextButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Копия")
            }
            Spacer(Modifier.weight(1f))
            if (onDelete != null && !profile.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        "Удалить набор",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun NewProfileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый набор правил") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) }
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun RuleEditor(
    modifier: Modifier = Modifier,
    rules: List<RoutingRuleDto>,
    onChange: (List<RoutingRuleDto>) -> Unit,
    onImportJson: (String) -> List<RoutingRuleDto>,
    onExportJson: (List<RoutingRuleDto>) -> String,
    onSave: () -> Unit,
    onAdd: () -> Unit
) {
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var jsonError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Hint("Правила применяются сверху вниз. Домены и IP — через запятую.")
        }
        item {
            TextButton(onClick = {
                showJson = !showJson
                if (showJson) {
                    jsonText = onExportJson(rules)
                    jsonError = null
                }
            }) {
                Text(if (showJson) "Скрыть JSON" else "Импорт / экспорт JSON")
            }
            AnimatedVisibility(showJson) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it; jsonError = null },
                        label = { Text("Правила JSON") },
                        supportingText = jsonError?.let { error -> { Text(error) } },
                        isError = jsonError != null,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6
                    )
                    Button(onClick = {
                        runCatching { onImportJson(jsonText) }
                            .onSuccess {
                                onChange(it)
                                jsonText = onExportJson(it)
                                jsonError = null
                            }
                            .onFailure { jsonError = it.message ?: "Некорректный JSON" }
                    }) { Text("Применить JSON") }
                }
            }
        }
        itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
            RuleCard(
                rule = rule,
                onUpdate = { updated ->
                    onChange(rules.toMutableList().also { it[index] = updated })
                },
                onDelete = { onChange(rules.toMutableList().also { it.removeAt(index) }) }
            )
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Добавить правило")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }
}

@Composable
private fun RuleCard(
    rule: RoutingRuleDto,
    onUpdate: (RoutingRuleDto) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(rule.id) { mutableStateOf(false) }
    VoltaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name.ifBlank { "Правило" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    actionLabel(rule.outbound) + " · " + summary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onUpdate(rule.copy(enabled = it)) })
            IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Rounded.Edit, "Правка")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Удалить")
            }
        }

        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { onUpdate(rule.copy(name = it)) },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Куда направлять", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteOutbound.entries.forEach { o ->
                        FilterChip(
                            selected = rule.outbound == o,
                            onClick = { onUpdate(rule.copy(outbound = o)) },
                            label = { Text(actionLabel(o)) }
                        )
                    }
                }
                ListField(
                    fieldKey = rule.id + ":domains",
                    label = "Сайты и домены",
                    values = rule.domains,
                    placeholder = "youtube.com, geosite:netflix"
                ) { onUpdate(rule.copy(domains = it)) }
                ListField(
                    fieldKey = rule.id + ":ips",
                    label = "IP-адреса",
                    values = rule.ips,
                    placeholder = "geoip:ru, 8.8.8.8/32"
                ) { onUpdate(rule.copy(ips = it)) }
                OutlinedTextField(
                    value = rule.ports,
                    onValueChange = { onUpdate(rule.copy(ports = it)) },
                    label = { Text("Порты") },
                    placeholder = { Text("80,443") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rule.network,
                    onValueChange = { onUpdate(rule.copy(network = it.trim())) },
                    label = { Text("Сеть") },
                    placeholder = { Text("tcp,udp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ListField(
                    fieldKey = rule.id + ":protocol",
                    label = "Протоколы",
                    values = rule.protocol,
                    placeholder = "http, tls, bittorrent"
                ) { onUpdate(rule.copy(protocol = it)) }
            }
        }
    }
}

/**
 * Comma-separated editor that keeps the raw text.
 *
 * Rendering `values.joinToString(", ")` back into the field ate the separator the moment
 * it was typed, so only a single domain could ever be entered.
 */
@Composable
private fun ListField(
    fieldKey: Any,
    label: String,
    values: List<String>,
    placeholder: String,
    onChange: (List<String>) -> Unit
) {
    var text by remember(fieldKey) { mutableStateOf(values.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            onChange(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
}

private fun summary(rule: RoutingRuleDto): String {
    val parts = buildList {
        if (rule.domains.isNotEmpty()) add("${rule.domains.size} доменов")
        if (rule.ips.isNotEmpty()) add("${rule.ips.size} IP")
        if (rule.ports.isNotBlank()) add("порты ${rule.ports}")
        if (rule.protocol.isNotEmpty()) add(rule.protocol.joinToString("/"))
    }
    return if (parts.isEmpty()) "без условий" else parts.joinToString(", ")
}

private fun actionLabel(o: RouteOutbound) = when (o) {
    RouteOutbound.PROXY -> "Через VPN"
    RouteOutbound.DIRECT -> "Напрямую"
    RouteOutbound.BLOCK -> "Блокировать"
}
