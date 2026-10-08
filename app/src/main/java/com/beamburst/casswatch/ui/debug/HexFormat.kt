package com.beamburst.casswatch.ui.debug

import java.util.Locale

object HexFormat {
    enum class ValidationError { REQUIRED, ODD_LENGTH, NON_HEX }

    class ValidationException(val reason: ValidationError) : IllegalArgumentException(reason.name)

	private val hexRegex = Regex("^[0-9a-fA-F]*$")

	fun parse(input: String): Result<ByteArray> {
		val compact = input
			.filterNot { it.isWhitespace() }
			.removePrefix("0x")
			.removePrefix("0X")

		if (compact.isBlank()) return Result.failure(ValidationException(ValidationError.REQUIRED))
		if (compact.length % 2 != 0) return Result.failure(ValidationException(ValidationError.ODD_LENGTH))
		if (!hexRegex.matches(compact)) return Result.failure(ValidationException(ValidationError.NON_HEX))

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
