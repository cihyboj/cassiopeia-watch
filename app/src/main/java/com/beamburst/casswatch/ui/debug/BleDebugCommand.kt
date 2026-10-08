package com.beamburst.casswatch.ui.debug

import org.avmedia.gshockapi.ble.GetSetMode

internal data class BleDebugCommand(
	val label: String,
	val hex: String,
	val mode: GetSetMode,
	val targetDescription: String,
) {
	fun resend(send: (String, String, GetSetMode, String) -> Unit) {
		send(label, hex, mode, targetDescription)
	}
}
