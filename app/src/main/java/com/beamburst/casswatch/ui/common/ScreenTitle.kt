// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.beamburst.casswatch.theme.Spacing

@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier,
    showStatusBadge: Boolean = true,
) {
    val defaultModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.xl, vertical = Spacing.md)

    Row(
        modifier = defaultModifier.then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AppText(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
        if (showStatusBadge) {
            WatchStatusBadge()
        }
    }
}
