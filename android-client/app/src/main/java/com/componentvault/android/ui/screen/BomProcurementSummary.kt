package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R

@Composable
internal fun BomProcurementSummary(
    inTransit: Int,
    arrived: Int,
    error: String?,
    enabled: Boolean,
    onReload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.bom_procurement_summary, inTransit, arrived), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.bom_procurement_local_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = enabled, onClick = onReload) { Text(stringResource(R.string.bom_procurement_reload)) }
        }
    }
}
