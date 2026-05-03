package com.beamburst.casswatch.utils

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber

object PhoneFinder {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun ring(context: Context) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
            handler.postDelayed({
                stop()
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }, 30_000)
        }.onFailure { e ->
            Timber.e(e, "PhoneFinder ring failed")
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
