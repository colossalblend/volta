package com.ars3nb.volta.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.AppLanguage

/**
 * «Язык» in Settings: a row with the current language, and a sheet listing every language
 * Volta has, each in its own name, with "as the system" on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val systemLabel = stringResource(R.string.language_system)
    val current = remember { AppLanguage.current(context) }
    var open by rememberSaveable { mutableStateOf(false) }

    LinkRow(
        icon = Icons.Rounded.Translate,
        tint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.language_title),
        subtitle = AppLanguage.currentName(context, systemLabel),
        onClick = { open = true },
        modifier = modifier
    )

    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    stringResource(R.string.language_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                val options = listOf(AppLanguage.Option("", systemLabel)) + AppLanguage.SUPPORTED
                LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                    items(options, key = { it.tag }) { option ->
                        LanguageOption(
                            name = option.nativeName,
                            selected = option.tag == current,
                            onClick = {
                                open = false
                                if (option.tag != current) context.findActivity()?.let { AppLanguage.set(it, option.tag) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Check, null, tint = scheme.primary)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
