package com.beamburst.casswatch

import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.data.local.BroadcastExtra
import com.beamburst.casswatch.data.local.ExtraType

object PressBroadcastEmitter {
    fun send(context: Context, spec: BroadcastSpec, extras: List<BroadcastExtra>) {
        context.sendBroadcast(createIntent(spec, extras))
    }

    internal fun createIntent(spec: BroadcastSpec, extras: List<BroadcastExtra>): Intent {
        return Intent(actionFor(spec)).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            extras.forEach { ex ->
                when (val value = coerceExtraValue(ex)) {
                    is String -> putExtra(ex.key, value)
                    is Int -> putExtra(ex.key, value)
                    is Boolean -> putExtra(ex.key, value)
                }
            }
        }
    }

    internal fun actionFor(spec: BroadcastSpec): String = spec.action

    internal fun coerceExtraValue(extra: BroadcastExtra): Any = when (extra.type) {
        ExtraType.STRING -> extra.value
        ExtraType.INT -> extra.value.toIntOrNull() ?: 0
        ExtraType.BOOLEAN -> extra.value.toBooleanStrictOrNull() ?: false
    }
}
