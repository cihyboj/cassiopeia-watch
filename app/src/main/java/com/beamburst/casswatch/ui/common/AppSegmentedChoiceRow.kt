package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beamburst.casswatch.theme.Spacing

@Composable
fun <T> AppSegmentedChoiceRow(
    title: String,
    selected: T,
    choices: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            AppText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!caption.isNullOrBlank()) {
                AppText(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SingleChoiceSegmentedButtonRow {
            choices.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                    modifier = Modifier.heightIn(min = 36.dp),
                    label = { Text(label) },
                )
            }
        }
    }
}
