package com.componentvault.android.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.componentvault.android.R
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomProcurementEntry
import com.componentvault.android.data.bom.BomProcurementStatus
import com.componentvault.android.data.bom.BomProcurementStore
import com.componentvault.android.data.bom.BomRequirement
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Procurement is a local reminder, never a reservation or a stock movement. */
internal class BomProcurementViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BomProcurementStore(File(application.filesDir, "bom-procurement.json"))
    var entries by mutableStateOf<List<BomProcurementEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { refresh() }

    fun refresh() = runOperation { }

    fun mark(parsed: BomParseResult, requirements: List<BomRequirement>) = runOperation {
        store.markAll(BomProcurementStore.projectKey(parsed), parsed.projectName, requirements)
    }

    fun remove(entry: BomProcurementEntry) = runOperation {
        store.remove(entry.projectKey, entry.requirementCanonicalKey)
    }

    private fun runOperation(action: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                entries = withContext(Dispatchers.IO) { action(); store.list() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: getApplication<Application>().getString(R.string.bom_procurement_save_error)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun BomProcurementActions(
    entry: BomProcurementEntry?,
    eligible: Boolean,
    enabled: Boolean,
    onMark: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (entry != null) {
            Text(
                stringResource(if (entry.status == BomProcurementStatus.InTransit) R.string.bom_procurement_in_transit else R.string.bom_procurement_arrived),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Row {
                TextButton(enabled = enabled, onClick = onRemove) {
                    Text(stringResource(if (entry.status == BomProcurementStatus.InTransit) R.string.bom_procurement_cancel else R.string.bom_procurement_acknowledge))
                }
                if (entry.status == BomProcurementStatus.ArrivedNeedsReview && eligible) {
                    TextButton(enabled = enabled, onClick = onMark) { Text(stringResource(R.string.bom_procurement_reorder)) }
                }
            }
        } else if (eligible) {
            TextButton(enabled = enabled, onClick = onMark) { Text(stringResource(R.string.bom_procurement_mark)) }
        }
    }
}
