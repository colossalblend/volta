package com.ars3nb.volta.ui.screens

import android.content.ActivityNotFoundException
import com.ars3nb.volta.core.locale.UiText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.LinkRow
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.theme.VoltaTheme

/**
 * «Перенос на новый телефон»: everything into one file, and back from it. Android's
 * own backup does not cover Volta, so this is how a new phone gets the subscriptions,
 * including ones whose link the provider keeps hidden.
 */
@Composable
internal fun BackupCard(vm: VoltaViewModel) {
    val offer by vm.restoreOffer.collectAsStateWithLifecycle()
    val working by vm.backupWorking.collectAsStateWithLifecycle()
    val primary = MaterialTheme.colorScheme.primary

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    // Storage apps disagree on what type a .json file is, so any file can be picked;
    // the app checks that it is a Volta file before doing anything.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readBackup(uri)
    }

    VoltaCard(contentPadding = 10) {
        LinkRow(
            icon = Icons.Rounded.SaveAlt,
            tint = primary,
            title = stringResource(R.string.settings_backup_save_title),
            subtitle = stringResource(R.string.settings_backup_save_text),
            onClick = { if (!working) launchPicker(vm) { save.launch(vm.backupFileName()) } }
        )
        LinkRow(
            icon = Icons.Rounded.Restore,
            tint = VoltaTheme.palette.good,
            title = stringResource(R.string.settings_backup_restore_title),
            subtitle = stringResource(R.string.settings_backup_restore_text),
            onClick = { if (!working) launchPicker(vm) { open.launch(arrayOf("*/*")) } }
        )
        AnimatedVisibility(
            visible = working,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Hint(stringResource(R.string.settings_backup_working))
            }
        }
        Hint(
            stringResource(R.string.settings_backup_warning),
            Modifier.padding(start = 48.dp, top = 2.dp, bottom = 6.dp, end = 4.dp)
        )
    }

    offer?.let { contents ->
        val resources = LocalResources.current
        AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.settings_backup_restore_question)) },
            text = { Text(BackupCodec.describe(contents, resources)) },
            confirmButton = {
                TextButton(onClick = vm::confirmRestore) { Text(stringResource(R.string.settings_backup_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.settings_backup_cancel)) }
            }
        )
    }
}

/** Many TV boxes have no document picker at all: say so instead of crashing. */
private inline fun launchPicker(vm: VoltaViewModel, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        vm.showMessage(UiText.of(R.string.settings_import_no_file_picker))
    }
}
