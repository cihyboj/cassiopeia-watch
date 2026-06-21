package com.beamburst.casswatch.ui.debug

import java.util.Locale

object HexFormat {
	private val hexRegex = Regex("^[0-9a-fA-F]*$")

	fun parse(input: String): Result<ByteArray> {
		val compact = input
			.filterNot { it.isWhitespace() }
			.removePrefix("0x")
			.removePrefix("0X")

		if (compact.isBlank()) return Result.failure(IllegalArgumentException("HEX value is required."))
		if (compact.length % 2 != 0) return Result.failure(IllegalArgumentException("HEX value must have an even number of digits."))
		if (!hexRegex.matches(compact)) return Result.failure(IllegalArgumentException("HEX value contains non-hex characters."))

		return Result.success(
			compact.chunked(2)
				.map { it.toInt(16).toByte() }
				.toByteArray()
		)
	}

	fun format(bytes: ByteArray): String =
		bytes.joinToString(separator = "") { "%02X".format(Locale.US, it.toInt() and 0xff) }

	fun normalize(input: String): Result<String> = parse(input).map(::format)
}
