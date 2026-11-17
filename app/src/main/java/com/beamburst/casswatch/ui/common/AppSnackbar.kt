// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents

/**
 * Height of bottom chrome (the app's navigation bar, including the system
 * navigation inset) that snackbars must stay above. Zero when none is shown.
 */
object SnackbarBottomInset {
    var height: Dp by mutableStateOf(0.dp)
}

fun AppSnackbar(message: String) {
    ProgressEvents.onNext("SnackbarMessage", message)
}

@Composable
fun PopupMessageReceiver(duration: SnackbarDuration = SnackbarDuration.Short) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val subscriberId = "PopupMessageReceiver-${System.identityHashCode(snackbarHostState)}"

    DisposableEffect(Unit) {
        val eventActions =
                arrayOf(
                        EventAction("SnackbarMessage") {
                            // Read the payload BEFORE launching: two rapid messages would
                            // otherwise both read the latest payload (one shown twice, one
                            // lost). Safe-cast — a null/non-String payload must not throw.
                            val message = ProgressEvents.getPayload("SnackbarMessage") as? String
                            if (message != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                            message = message,
                                            duration = duration
                                    )
                                }
                            }
                        },
                )

        ProgressEvents.runEventActions(subscriberId, eventActions)
        onDispose { ProgressEvents.subscriber.stop(subscriberId) }
    }

    val bottomChrome = SnackbarBottomInset.height
    Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                // Sit above the navigation bar when one is shown, so its tabs stay usable.
                .then(if (bottomChrome > 0.dp) Modifier.padding(bottom = bottomChrome) else Modifier.navigationBarsPadding()),
            contentAlignment = Alignment.BottomCenter,
    ) { SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(16.dp)) }
}
