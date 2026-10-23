package com.cauce.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The app's card: Material surface plus the standard inner padding. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(modifier = modifier) {
        Column(modifier = Modifier.padding(CauceSpacing.md), content = content)
    }
}
