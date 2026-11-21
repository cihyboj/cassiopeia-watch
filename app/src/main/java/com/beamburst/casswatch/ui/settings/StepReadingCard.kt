package com.beamburst.casswatch.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.time.StepsViewModel

/** Phone-only switch: read the step counter at each connection and keep the history locally. */
@Composable
fun StepReadingCard(stepsViewModel: StepsViewModel = hiltViewModel()) {
    val enabled by stepsViewModel.readingEnabled.collectAsState()
    SettingCard(modifier = Modifier.fillMaxWidth()) { contentPadding ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(text = stringResource(R.string.steps_setting_title), style = MaterialTheme.typography.titleMedium)
                AppText(
                    text = stringResource(R.string.steps_setting_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(checked = enabled, onCheckedChange = stepsViewModel::setReadingEnabled)
        }
    }
}
