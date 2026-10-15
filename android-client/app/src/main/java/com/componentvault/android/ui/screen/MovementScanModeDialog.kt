package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.MovementQuickAction

@Composable
internal fun MovementScanModeDialog(onDismiss: () -> Unit, onSelect: (MovementQuickAction) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_stock_choose_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scan_stock_mode_help))
                FilledTonalButton(onClick = { onSelect(MovementQuickAction.Inbound) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_stock_inbound))
                }
                FilledTonalButton(onClick = { onSelect(MovementQuickAction.Outbound) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_stock_outbound))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(vaultStrings().common.actionBack) } },
    )
}