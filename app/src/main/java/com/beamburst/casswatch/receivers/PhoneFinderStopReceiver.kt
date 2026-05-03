package com.beamburst.casswatch.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.utils.PhoneFinder

class PhoneFinderStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            PhoneFinder.stop()
        }
    }

    companion object {
        const val ACTION_STOP = "com.beamburst.casswatch.action.STOP_FIND_PHONE"
    }
}
