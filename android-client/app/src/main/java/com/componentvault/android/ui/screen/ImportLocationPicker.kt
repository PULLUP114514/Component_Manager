package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.StorageLocationSaveIntent
import kotlinx.coroutines.launch
import java.util.UUID

/** Shows names while retaining the storage location ID in the draft. */
@Composable
internal fun ImportLocationPicker(
    value: String,
    onValueChange: (String) -> Unit,
    storageLocations: List<StorageLocationRecord>,
) {
    val context = LocalContext.current
    val repository = remember(context) { InventoryRepository(context) }
    val scope = rememberCoroutineScope()
    var locations by remember(storageLocations) { mutableStateOf(storageLocations.filterNot(StorageLocationRecord::deleted)) }
    LaunchedEffect(storageLocations, value) { locations = repository.loadStorageLocations().filterNot(StorageLocationRecord::deleted) }
    var menuExpanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(locations.firstOrNull { it.id == value }?.name ?: stringResource(R.string.importer_select_location))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.heightIn(max = 280.dp).widthIn(max = 320.dp)) {
                locations.forEach { location ->
                    DropdownMenuItem(
                        text = { Text(location.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        onClick = { onValueChange(location.id); menuExpanded = false },
                    )
                }
            }
        }
        OutlinedButton(onClick = {
            name = ""
            error = null
            creating = true
        }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.importer_create_location))
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { if (!busy) creating = false },
        title = { Text(stringResource(R.string.importer_create_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.importer_location_name)) },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && name.isNotBlank(), onClick = {
                val normalizedName = name.trim()
                if (locations.any { it.name.equals(normalizedName, ignoreCase = true) }) {
                    error = context.getString(R.string.importer_location_duplicate)
                    return@Button
                }
                val id = "LOC-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
                busy = true
                scope.launch {
                    val result = repository.saveStorageLocation(id, normalizedName, StorageLocationSaveIntent.Create)
                    busy = false
                    if (result.isSuccess) {
                        locations = repository.loadStorageLocations().filterNot(StorageLocationRecord::deleted)
                        onValueChange(id)
                        creating = false
                    } else error = result.message
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { creating = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
