package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.VoltaCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(vm: VoltaViewModel, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("Подписка по ссылке")
            Hint("Вставьте ссылку от VPN-сервиса. Название, серверы и правила подтянутся сами.")

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Ссылка на подписку") },
                placeholder = { Text("https://…") },
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { url = it.trim() }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, "Вставить из буфера")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Своё название (необязательно)") },
                placeholder = { Text("Оставьте пустым — возьмём с сервера") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.addSubscription(name.trim(), url.trim()) { ok -> if (ok) onBack() }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !state.busy
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Добавить подписку")
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader("Одна ссылка или JSON")
            Hint("Ссылка на сервер или готовый конфиг.")

            OutlinedTextField(
                value = paste,
                onValueChange = { paste = it },
                label = { Text("Вставьте сюда") },
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { paste = it.trim() }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, "Вставить из буфера")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5
            )
            OutlinedButton(
                onClick = { vm.importLink(paste.trim()) { ok -> if (ok) onBack() } },
                modifier = Modifier.fillMaxWidth(),
                enabled = paste.isNotBlank() && !state.busy
            ) {
                Text("Импортировать")
            }

            Spacer(Modifier.height(4.dp))
            VoltaCard {
                Text(
                    "Что понимает это ядро",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUPPORTED.forEach { proto ->
                        AssistChip(onClick = {}, label = { Text(proto) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val SUPPORTED = listOf(
    "VLESS", "VMess", "Trojan", "Shadowsocks", "SS-2022",
    "SOCKS5", "HTTP", "Hysteria2", "WireGuard"
)
