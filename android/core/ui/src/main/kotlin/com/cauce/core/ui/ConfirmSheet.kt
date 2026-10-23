package com.cauce.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A bottom sheet used for destructive or irreversible confirmations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String = "Cancelar",
) {
    if (!visible) return

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CauceSpacing.md)
                .padding(bottom = CauceSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(CauceSpacing.sm),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BigButton(text = confirmLabel, onClick = onConfirm)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(dismissLabel)
            }
        }
    }
}
