package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.io.encoding.Base64

class RawPackageTest {
    private fun pngHeader(width: Int = 1536, height: Int = 1872): ByteArray {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val ihdr = byteArrayOf(73, 72, 68, 82)
        val payload = ByteArray(13)
        fun put32(offset: Int, value: Int) {
            repeat(4) { payload[offset + it] = (value ushr (24 - 8 * it)).toByte() }
        }
        put32(0, width)
        put32(4, height)
        payload[8] = 8
        payload[9] = 6
        var crc = -1
        for (byte in ihdr + payload) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        crc = crc.inv()
        val crcBytes = ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() }
        return signature + byteArrayOf(0, 0, 0, 13) + ihdr + payload + crcBytes
    }

    @Test
    fun rawPublicPairNeedsNoCallerMetadata() {
        val outcome = PetPackageParser.parse("{}".encodeToByteArray(), pngHeader(), "raw")
        val success = assertIs<PetParseOutcome.Success>(outcome)
        assertEquals("raw", success.definition.id)
        assertEquals(1536, success.definition.geometry.atlasWidth)
    }

    @Test
    fun manifestBoundariesAreExact() {
        val sheet = pngHeader()
        for (size in listOf(PetInputLimits.MAX_MANIFEST_BYTES - 1, PetInputLimits.MAX_MANIFEST_BYTES)) {
            val manifest = ("{}" + " ".repeat(size - 2)).encodeToByteArray()
            assertIs<PetParseOutcome.Success>(PetPackageParser.parse(manifest, sheet, "raw"))
        }
        val over = ("{}" + " ".repeat(PetInputLimits.MAX_MANIFEST_BYTES - 1)).encodeToByteArray()
        val failed = assertIs<PetParseOutcome.Failure>(PetPackageParser.parse(over, sheet, "raw"))
        assertIs<PetCompatibilityError.InputLimitExceeded>(failed.report.errors.single())
    }

    @Test
    fun imageBoundariesAreExact() {
        val header = pngHeader()
        for (size in listOf(PetInputLimits.MAX_SPRITESHEET_BYTES - 1, PetInputLimits.MAX_SPRITESHEET_BYTES)) {
            val bytes = ByteArray(size)
            header.copyInto(bytes)
            assertIs<PetParseOutcome.Success>(PetPackageParser.parse("{}".encodeToByteArray(), bytes, "raw"))
        }
        val over = ByteArray(PetInputLimits.MAX_SPRITESHEET_BYTES + 1)
        header.copyInto(over)
        val failed = assertIs<PetParseOutcome.Failure>(PetPackageParser.parse("{}".encodeToByteArray(), over, "raw"))
        assertIs<PetCompatibilityError.InputLimitExceeded>(failed.report.errors.single())
    }

    @Test
    fun malformedMetadataIsTypedFailure() {
        val failed = assertIs<PetParseOutcome.Failure>(
            PetPackageParser.parse("{}".encodeToByteArray(), byteArrayOf(1, 2, 3), "raw"),
        )
        assertIs<PetCompatibilityError.InvalidSpritesheetBytes>(failed.report.errors.single())
    }

    @Test
    fun animatedWebpIsRejectedByPublicRawParser() {
        val animated = Base64.decode("UklGRsQAAABXRUJQVlA4WAoAAAACAAAADwAACwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAA8AAAsAAMgAAAJWUDggMgAAADABAJ0BKhAADAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAPAAALAADIAAAAVlA4IC4AAAA0AQCdASoQAAwAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA")
        val failed = assertIs<PetParseOutcome.Failure>(
            PetPackageParser.parse("{}".encodeToByteArray(), animated, "raw"),
        )
        assertIs<PetCompatibilityError.InvalidSpritesheetBytes>(failed.report.errors.single())
    }
}
