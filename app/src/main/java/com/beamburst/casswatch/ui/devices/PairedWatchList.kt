package com.beamburst.casswatch.ui.devices

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.others.CompanionDeviceHelper
import com.beamburst.casswatch.ui.others.PreConnectionViewModel
import com.beamburst.casswatch.ui.others.PreparingPairingDialog
import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.ICDPDelegate
import timber.log.Timber

private const val PAIRING_TIMEOUT_MS = 60000L

@SuppressLint("MissingPermission")
@Composable
fun PairedWatchList(
    modifier: Modifier = Modifier,
    viewModel: MultiWatchViewModel = hiltViewModel(),
    preConnectionViewModel: PreConnectionViewModel = hiltViewModel(),
) {
    val watches by viewModel.paired.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showPreparing by preConnectionViewModel.showPreparing.collectAsState()
    val timeoutJob = remember { mutableStateOf<Job?>(null) }
    var forgetTarget by remember { mutableStateOf<PairedWatch?>(null) }

    val handleSuccessfulPairing = { device: BluetoothDevice ->
        timeoutJob.value?.cancel()
        val address = Utils.sanitizeMacAddress(device.address)
        val name = device.name ?: ""
        preConnectionViewModel.setDevice(address, name)
        viewModel.select(address)
        viewModel.refresh()
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return

                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )
                val previousBondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )

                when {
                    device != null && bondState == BluetoothDevice.BOND_BONDED ->
                        handleSuccessfulPairing(device)

                    previousBondState == BluetoothDevice.BOND_BONDING &&
                        bondState == BluetoothDevice.BOND_NONE ->
                        timeoutJob.value?.cancel()
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
            timeoutJob.value?.cancel()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val device = CompanionDeviceHelper.extractDevice(result.data)
            ?: return@rememberLauncherForActivityResult

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Timber.i("Starting OS pairing for ${device.address}")
            device.createBond()
            timeoutJob.value?.cancel()
            timeoutJob.value = scope.launch {
                delay(PAIRING_TIMEOUT_MS)
                Timber.w("Pairing timed out")
            }
        } else {
            handleSuccessfulPairing(device)
        }
    }

    PreparingPairingDialog(visible = showPreparing, ptrConnectionViewModel = preConnectionViewModel)

    AppCard(modifier = modifier.fillMaxWidth(), padding = Spacing.xs) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (watches.isEmpty()) {
                EmptyPairedWatches()
            } else {
                watches.forEach { watch ->
                    WatchRow(
                        watch = watch,
                        onSelect = { viewModel.select(watch.address) },
                        onConnect = { viewModel.connect(watch.address) },
                        onDisconnect = { viewModel.disconnect(watch.address) },
                        onForget = { forgetTarget = watch },
                    )
                }
            }

            AddWatchButton(
                onClick = {
                    preConnectionViewModel.associateWithUi(
                        context,
                        object : ICDPDelegate {
                            override fun onChooserReady(chooserLauncher: IntentSender) {
                                launcher.launch(
                                    IntentSenderRequest.Builder(chooserLauncher).build()
                                )
                            }

                            override fun onError(error: String) {
                                Timber.i(error)
                                AppSnackbar(error)
                            }
                        }
                    )
                }
            )
        }
    }

    forgetTarget?.let { watch ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { AppText(stringResource(R.string.forget_watch_confirm_title, watch.name)) },
            text = { AppText(stringResource(R.string.forget_watch_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(watch.address)
                        forgetTarget = null
                    }
                ) {
                    AppText(stringResource(R.string.forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) {
                    AppText(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EmptyPairedWatches() {
    AppText(
        text = stringResource(R.string.paired_watches_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Spacing.sm)
    )
}

@Composable
private fun AddWatchButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            AppText(stringResource(R.string.add_watch))
        }
    }
}
