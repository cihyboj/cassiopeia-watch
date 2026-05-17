package com.beamburst.casswatch.ui.settings

import com.beamburst.casswatch.ui.common.AppSwitch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppSegmentedChoiceRow
import org.avmedia.gshockapi.WatchInfo

@Composable
fun Light(
    onUpdate: (SettingsViewModel.Light) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by settingsViewModel.state.collectAsState()
    val lightSetting =
        state.settingsMap[SettingsViewModel.Light::class.java] as SettingsViewModel.Light

    var autoLight by remember { mutableStateOf(lightSetting.autoLight) }
    var lightDuration by remember { mutableStateOf(lightSetting.duration) }

    LaunchedEffect(state.settings) {
        autoLight = lightSetting.autoLight
        lightDuration = lightSetting.duration
    }

    SettingCard(modifier = Modifier.fillMaxWidth()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            if (WatchInfo.hasAutoLight) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        SettingsLabel(
                            text = stringResource(id = R.string.auto_light),
                            modifier = Modifier.padding(end = Spacing.sm)
                        )
                    }
                    AppSwitch(
                        checked = autoLight,
                        onCheckedChange = {
                            autoLight = it
                            onUpdate(lightSetting.copy(autoLight = it))
                        }
                    )
                }
            }

            AppSegmentedChoiceRow(
                title = stringResource(id = R.string.illumination_period),
                selected = lightDuration,
                choices = SettingsViewModel.Light.LightDuration.entries.map { it to it.value },
                onSelected = {
                    lightDuration = it
                    onUpdate(lightSetting.copy(duration = it))
                }
            )
        }
    }
}
