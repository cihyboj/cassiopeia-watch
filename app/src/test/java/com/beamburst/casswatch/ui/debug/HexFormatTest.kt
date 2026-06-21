package com.beamburst.casswatch.ui.debug

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexFormatTest {
	@Test
	fun `parse accepts compact hex`() {
		assertArrayEquals(byteArrayOf(0x15, 0x40, 0x40, 0x07, 0x00), HexFormat.parse("1540400700").getOrThrow())
	}

	@Test
	fun `parse accepts spaced hex`() {
		assertArrayEquals(byteArrayOf(0x4c, 0x00, 0x01), HexFormat.parse("4c 00 01").getOrThrow())
	}

	@Test
	fun `parse accepts leading hex prefix`() {
		assertArrayEquals(byteArrayOf(0x01, 0x02), HexFormat.parse("0x0102").getOrThrow())
	}

	@Test
	fun `format returns uppercase compact hex`() {
		assertEquals("0102FF", HexFormat.format(byteArrayOf(0x01, 0x02, 0xff.toByte())))
	}

	@Test
	fun `parse rejects odd digit count`() {
		assertTrue(HexFormat.parse("010").isFailure)
	}

	@Test
	fun `parse rejects non hex chars`() {
		assertTrue(HexFormat.parse("01xx").isFailure)
	}
}
