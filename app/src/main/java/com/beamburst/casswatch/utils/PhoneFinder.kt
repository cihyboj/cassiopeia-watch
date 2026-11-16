package com.beamburst.casswatch.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.FindPhoneMelody
import com.beamburst.casswatch.receivers.PhoneFinderStopReceiver
import timber.log.Timber

object PhoneFinder {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterTimeout = Runnable { stop() }
    private var audioManager: AudioManager? = null
    private var savedVolume: Int? = null
    private var appContext: Context? = null

    private const val CHANNEL_ID = FIND_PHONE_NOTIFICATION_CHANNEL
    private const val NOTIFICATION_ID = 4242

    // Watch events can arrive on a background dispatcher. Serialize the toggle with
    // playback and timeout cleanup on the main looper.
    fun toggle(context: Context, volumePercent: Int = 100, melody: FindPhoneMelody = FindPhoneMelody.ALARM) {
        handler.post {
            if (shouldStopPhoneFinder(player != null)) stop() else ring(context, volumePercent, melody)
        }
    }

    fun ring(context: Context, volumePercent: Int = 100, melody: FindPhoneMelody = FindPhoneMelody.ALARM) {
        stop()
        appContext = context.applicationContext
        val uri = when (melody) {
            FindPhoneMelody.ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            FindPhoneMelody.RINGTONE -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            FindPhoneMelody.NOTIFICATION -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = manager
            savedVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVolume = ((maxVolume * volumePercent.coerceIn(0, 100)) / 100f).toInt().coerceIn(1, maxVolume)
            // Throws SecurityException when Do Not Disturb is active and the app lacks
            // notification-policy access; keep ringing at the current volume in that case.
            runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0) }
                .onFailure {
                    savedVolume = null
                    Timber.w(it, "Could not raise alarm volume (DND?)")
                }
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
            // Ringing and the 30-second timeout work even without a stop notification.
            runCatching { showStopNotification(context.applicationContext) }
                .onFailure { Timber.w(it, "Could not show find-phone stop notification") }
            handler.postDelayed(stopAfterTimeout, 30_000)
        }.onFailure { e ->
            Timber.e(e, "PhoneFinder ring failed")
            stop()
        }
    }

    /** Restore the Stop action if notification permission was granted during a ring. */
    fun refreshStopNotification(context: Context) {
        val applicationContext = context.applicationContext
        handler.post {
            runCatching {
                if (shouldShowPhoneFinderStopNotification(player != null, canPostFindPhoneNotifications(applicationContext))) {
                    showStopNotification(applicationContext)
                }
            }.onFailure { Timber.w(it, "Could not refresh find-phone stop notification") }
        }
    }

    fun stop() {
        handler.removeCallbacks(stopAfterTimeout)
        runCatching { player?.stop(); player?.release() }
        runCatching {
            val manager = audioManager
            val previous = savedVolume
            if (manager != null && previous != null) {
                manager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            }
        }
        dismissStopNotification()
        player = null
        audioManager = null
        savedVolume = null
    }

    private fun showStopNotification(context: Context) {
        if (!canPostNotifications(context)) {
            Timber.d("Find-phone stop notification unavailable: notifications are disabled")
            return
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Find phone", NotificationManager.IMPORTANCE_HIGH)
        )
        val stopIntent = Intent(context, PhoneFinderStopReceiver::class.java).apply {
            action = PhoneFinderStopReceiver.ACTION_STOP
        }
        val pendingStop = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(context.getString(R.string.find_phone_notification_title))
            .setContentText(context.getString(R.string.find_phone_notification_text))
            .setOngoing(true)
            .addAction(0, context.getString(R.string.stop), pendingStop)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun dismissStopNotification() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}

internal fun shouldStopPhoneFinder(isRinging: Boolean): Boolean = isRinging

internal fun shouldShowPhoneFinderStopNotification(isRinging: Boolean, notificationsPermitted: Boolean): Boolean =
    isRinging && notificationsPermitted
