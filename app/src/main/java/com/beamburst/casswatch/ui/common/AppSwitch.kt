package com.beamburst.casswatch.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

private const val AppSwitchScale = 0.82f

@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors? = null,
    thumbContent: (@Composable () -> Unit)? = null,
) {
    val handleChange = { newValue: Boolean ->
        if (enabled) {
            onCheckedChange(newValue)
        }
    }

    val scheme = MaterialTheme.colorScheme
    val resolvedColors = colors ?: SwitchDefaults.colors(
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = scheme.primary,
        checkedIconColor = scheme.onPrimary,
        uncheckedThumbColor = scheme.outlineVariant,
        uncheckedTrackColor = scheme.surfaceVariant,
        uncheckedBorderColor = scheme.outline,
        uncheckedIconColor = scheme.surfaceVariant,
    )

    Switch(
        checked = checked,
        onCheckedChange = handleChange,
        modifier = Modifier.scale(AppSwitchScale).then(modifier),
        enabled = enabled,
        colors = resolvedColors,
        thumbContent = thumbContent,
    )
}
