// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.settings

import com.beamburst.casswatch.ui.common.AppText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppSegmentedChoiceRow
import org.avmedia.gshockapi.WatchInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Locale(
    onUpdate: (SettingsViewModel.Locale) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by settingsViewModel.state.collectAsState()
    val localeSetting =
        state.settingsMap[SettingsViewModel.Locale::class.java] as SettingsViewModel.Locale

    var timeFormat by remember { mutableStateOf(localeSetting.timeFormat) }
    var dateFormat by remember { mutableStateOf(localeSetting.dateFormat) }

    LaunchedEffect(state.settings) {
        timeFormat = localeSetting.timeFormat
        dateFormat = localeSetting.dateFormat
    }

    SettingCard(modifier = Modifier.fillMaxWidth()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            AppSegmentedChoiceRow(
                title = stringResource(id = R.string.time_format),
                selected = timeFormat,
                choices = listOf(
                    SettingsViewModel.Locale.TimeFormat.TWELVE_HOURS to stringResource(id = R.string._12h),
                    SettingsViewModel.Locale.TimeFormat.TWENTY_FOUR_HOURS to stringResource(id = R.string._24h),
                ),
                onSelected = {
                    timeFormat = it
                    onUpdate(localeSetting.copy(timeFormat = it))
                }
            )

            if (WatchInfo.hasDateFormat) {
                AppSegmentedChoiceRow(
                    title = stringResource(id = R.string.date_format),
                    selected = dateFormat,
                    choices = listOf(
                        SettingsViewModel.Locale.DateFormat.MONTH_DAY to stringResource(id = R.string.mm_dd),
                        SettingsViewModel.Locale.DateFormat.DAY_MONTH to stringResource(id = R.string.dd_mm),
                    ),
                    onSelected = {
                        dateFormat = it
                        onUpdate(localeSetting.copy(dateFormat = it))
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                SettingsLabel(
                    text = stringResource(
                        id = R.string.language
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Spacing.xxs)
                )

                LanguageDropdownMenu(
                    modifier = Modifier
                        .weight(1.5f)
                        .padding(bottom = Spacing.xxs),
                    onUpdate = onUpdate,
                    localeSetting = localeSetting,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdownMenu(
    onUpdate: (SettingsViewModel.Locale) -> Unit,
    localeSetting: SettingsViewModel.Locale,
    modifier: Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val state by settingsViewModel.state.collectAsState()

    val languages = SettingsViewModel.Locale.DayOfWeekLanguage.entries.map { it }
    var selectedLanguage by remember { mutableStateOf(localeSetting.dayOfWeekLanguage) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.settings) {
        selectedLanguage = localeSetting.dayOfWeekLanguage
    }

    // ExposedDropdownMenuBox wraps around the TextField and DropdownMenu
    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedLanguage.nativeName,
            onValueChange = {},
            readOnly = true,  // To prevent user from typing in the field
            label = {
                AppText(
                    text = stringResource(
                        R.string.select_language
                    )
                )
            },

            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    true
                ),  // Attach menu to the OutlinedTextField
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { AppText(language.nativeName) },
                    onClick = {
                        selectedLanguage = language
                        expanded = false
                        onUpdate(localeSetting.copy(dayOfWeekLanguage = language))
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewLocale() {
    Locale(onUpdate = {})
}
