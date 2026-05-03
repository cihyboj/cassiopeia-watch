package com.beamburst.casswatch.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import AppSwitch
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppText

@Composable
fun PressCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dialogContent: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        dialogContent?.invoke { showDialog = false }
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (dialogContent != null) Modifier.clickable { showDialog = true }
                    else Modifier
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppText(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.graphicsLayer(scaleX = 0.82f, scaleY = 0.82f),
            )
        }
    }
}
