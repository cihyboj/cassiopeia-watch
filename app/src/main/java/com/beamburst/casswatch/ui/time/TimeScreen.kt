// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.time

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.CassiopeiaWatchTheme
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.ScreenTitle
import com.beamburst.casswatch.ui.devices.PairedWatchList

@Composable
fun TimeScreen(timeViewModel: TimeViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) {
        timeViewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    AppSnackbar(event.message)
                }
            }
        }
    }

    CassiopeiaWatchTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ScreenTitle(
                    stringResource(id = R.string.home),
                    Modifier,
                    showStatusBadge = false,
                )

                LocalTimeView(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                )

                PairedWatchList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                )
            }
        }
    }
}

@Composable
private fun TimeScreenPreviewContent() {
    CassiopeiaWatchTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ScreenTitle(
                    stringResource(id = R.string.home),
                    Modifier,
                    showStatusBadge = false,
                )

                LocalTimeCardContent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    onSendTime = {},
                )

            }
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewTimeScreen() {
    TimeScreenPreviewContent()
}
