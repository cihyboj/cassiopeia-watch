package com.beamburst.casswatch.ui.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber

class PhoneFallbackReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ALARM_INDEX = "alarm_index"
        const val EXTRA_EXPECTED_HASH = "expected_hash"
        const val EXTRA_FIRE_INSTANT = "fire_instant"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val ACTION_EXPIRE = "com.beamburst.casswatch.EXPIRE_ALARM"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun alarmSchedulingCoordinator(): AlarmSchedulingCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = AlarmKey.from(intent.getStringExtra(EXTRA_DEVICE_ADDRESS), intent.getIntExtra(EXTRA_ALARM_INDEX, -1)) ?: return
        val expiry = intent.action == ACTION_EXPIRE
        if (intent.data?.toString() != if (expiry) "${key.uri}/expiry" else key.uri) return
        val hash = intent.getStringExtra(EXTRA_EXPECTED_HASH)
        if (!expiry && hash == null) return
        val pending = goAsync()
        AppScopes.io.launch {
            try {
                val coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
                    .alarmSchedulingCoordinator()
                if (expiry) coordinator.reconcile(key.normalizedAddress)
                else coordinator.fallback(key, intent.getLongExtra(EXTRA_FIRE_INSTANT, -1), hash!!) { alarm ->
                    // Best-effort hand-off, hour/minute only: no date, creation acknowledgement or cancellation API.
                    context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, alarm.name)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (error: Exception) {
                Timber.e(error, "Phone fallback callback failed for %s", key)
            } finally {
                pending.finish()
            }
        }
    }
}
