package com.cauce.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** Whole-number input. Empty text maps to `null`, never to zero. */
@Composable
fun NumberInput(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    range: IntRange? = null,
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filter(Char::isDigit)
            val parsed = digits.toIntOrNull()
            val inRange = parsed != null && (range == null || parsed in range)
            onValueChange(if (digits.isEmpty() || !inRange) null else parsed)
        },
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
