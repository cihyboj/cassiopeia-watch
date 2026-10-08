package com.beamburst.casswatch.ui.debug

import org.avmedia.gshockapi.ble.GetSetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDebugCommandTest {
	@Test
	fun `repeated read probe retains GET mode payload and read target`() {
		assertResend("Read probe", "0100", GetSetMode.GET, "26eb002c")
	}

	@Test
	fun `repeated write retains SET mode payload and command target`() {
		assertResend("Write probe", "1540400700", GetSetMode.SET, "26eb002d")
	}

	@Test
	fun `debug state defaults to dry run`() {
		assertTrue(BleDebugUiState().dryRun)
	}

	private fun assertResend(label: String, hex: String, mode: GetSetMode, target: String) {
		val command = BleDebugCommand(label, hex, mode, target)
		var sends = 0
		repeat(3) {
			command.resend { sentLabel, sentHex, sentMode, sentTarget ->
				assertEquals(label, sentLabel)
				assertEquals(hex, sentHex)
				assertEquals(mode, sentMode)
				assertEquals(target, sentTarget)
				sends++
			}
		}
		assertEquals(3, sends)
	}
}
