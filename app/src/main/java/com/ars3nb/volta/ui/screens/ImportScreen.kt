package com.ars3nb.volta.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.link.ImportDetector.Kind
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.IconBubble
import com.ars3nb.volta.ui.components.LinkRow
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.VoltaField
import com.ars3nb.volta.ui.theme.VoltaTheme

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
    val state by vm.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val kind = remember(input) { ImportDetector.detect(input) }
    // Only whether there is text: reading it would show Android's "pasted" toast for
    // nothing. The text itself is read when the button is tapped.
    val canPaste = remember(input.isEmpty()) { input.isEmpty() && clipboard.hasText() }

    // A .conf from AmneziaVPN, AmneziaWG or WireGuard. Its type varies by file manager,
    // so any file is offered; the parser decides.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                String(stream.readNBytesCompat(MAX_FILE_BYTES), Charsets.UTF_8)
            }
        }.getOrNull()
        if (!text.isNullOrBlank()) vm.importLink(text.trim()) { ok -> if (ok) onBack() }
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
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Добавить", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Подписку, сервер или ключ. Вставьте как есть, Volta разберётся сама.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Box {
            VoltaField(
                value = input,
                onValueChange = { input = it },
                placeholder = "Ссылка или ключ от VPN-сервиса",
                minLines = 4,
                maxLines = 9
            )
            // Pasting is what nearly everyone comes here to do: the button sits in the
            // empty field, where the thumb already is.
            if (canPaste) {
                Surface(
                    onClick = { clipboard.getText()?.text?.trim()?.let { input = it } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.ContentPaste,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Вставить",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
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
                label = "Название, если хотите своё",
                singleLine = true,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = ::submit,
            enabled = kind.canSubmit && !state.busy,
            modifier = Modifier.fillMaxWidth().height(54.dp)
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
                ) { Text(it, style = MaterialTheme.typography.titleSmall) }
            }
        }

        Spacer(Modifier.height(14.dp))
        VoltaCard(contentPadding = 8) {
            LinkRow(
                icon = Icons.Rounded.FileOpen,
                tint = MaterialTheme.colorScheme.primary,
                title = "Открыть файл настроек",
                subtitle = "Файл, который прислал VPN-сервис",
                onClick = { if (!state.busy) openFile.launch(arrayOf("*/*")) }
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Что сюда можно вставить")
        Spacer(Modifier.height(6.dp))
        VoltaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FORMATS.forEach { (title, body) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Hint(body)
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
            Kind.Empty -> Look(Icons.Rounded.Link, scheme.onSurfaceVariant, "Пока пусто", "Вставьте ссылку или ключ")
            is Kind.Subscription -> if (k.hidden) {
                Look(Icons.Rounded.Lock, scheme.primary, "Подписка со скрытой ссылкой", "Адрес не будет виден в приложении")
            } else {
                Look(Icons.Rounded.CloudSync, scheme.primary, "Подписка", "Серверы и правила будут обновляться сами")
            }
            Kind.Encrypted -> Look(
                Icons.Rounded.EnhancedEncryption, palette.good,
                "Зашифрованная подписка", "Volta откроет её сама, адрес останется скрытым"
            )
            is Kind.Server -> Look(Icons.Rounded.Storage, palette.home, "Сервер", "Добавится в общий список серверов")
            is Kind.Servers -> Look(Icons.Rounded.Storage, palette.home, "Серверов: ${k.count}", "Все добавятся в общий список")
            Kind.AmneziaKey -> Look(Icons.Rounded.Key, palette.home, "Ключ AmneziaVPN", "Серверы из ключа добавятся в список")
            Kind.Conf -> Look(Icons.Rounded.Description, palette.home, "Файл настроек сервера", "Станет одним сервером в списке")
            Kind.Json -> Look(Icons.Rounded.DataObject, palette.home, "Настройки серверов", "Серверы добавятся в общий список")
            Kind.Control -> Look(
                Icons.Rounded.PowerSettingsNew, scheme.primary,
                "Ссылка управления", "Не добавляется, а включает или выключает VPN"
            )
            Kind.Unknown -> Look(
                Icons.Rounded.QuestionMark, palette.poor,
                "Не похоже на ссылку", "Проверьте, что скопировали её целиком"
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
private val Kind.action: String
    get() = when (this) {
        is Kind.Subscription, Kind.Encrypted -> "Добавить подписку"
        is Kind.Server -> "Добавить сервер"
        is Kind.Servers -> "Добавить серверы"
        Kind.AmneziaKey, Kind.Conf, Kind.Json -> "Импортировать"
        Kind.Control -> "Выполнить"
        Kind.Empty, Kind.Unknown -> "Добавить"
    }

private val FORMATS = listOf(
    "Подписка" to "Ссылка от VPN-сервиса, обычная или зашифрованная. Серверы в ней обновляются сами",
    "Сервер" to "Ссылка на один сервер, которую прислал сервис",
    "Ключ AmneziaVPN" to "Длинный ключ, который начинается с vpn://",
    "Файл настроек" to "Файл, который прислал сервис: его можно открыть кнопкой выше"
)

/** A config file is a few kilobytes; anything far bigger is not one. */
private const val MAX_FILE_BYTES = 512 * 1024

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
