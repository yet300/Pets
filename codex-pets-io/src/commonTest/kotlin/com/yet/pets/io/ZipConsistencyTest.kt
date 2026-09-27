package com.yet.pets.io

import com.yet.pets.io.internal.zip.Crc32
import com.yet.pets.io.internal.zip.ratioExceeds
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun validPetEntries(): List<ZipEntrySpec> = listOf(
    ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
    ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
)

private fun zipError(bytes: ByteArray): PetLoadError {
    val outcome = PetLoader.loadPetZip(bytes, "pet", PetPackageLimits.Default)
    assertIs<PetLoadOutcome.Failure>(outcome, "expected failure, got $outcome")
    return outcome.errors.single()
}

/**
 * Permanent adversarial regressions for central/local consistency (F-02),
 * flag policy (F-03), stored invariants (F-06), CRC policy (F-07), collisions
 * (F-08), ratio arithmetic (F-09), lying metadata (F-11), encoding scope
 * (F-12), and central-size reconciliation (F-14).
 */
class ZipConsistencyTest {

    @Test
    fun deflateRequiresExactDeclaredPayloadConsumption() {
        val manifest = manifestJson(id = "zip").encodeToByteArray()
        val sheet = webpVp8Bytes()
        val valid = deflateRaw(sheet)
        fun load(payload: ByteArray): PetLoadOutcome = PetLoader.loadPetZip(buildZip(listOf(
            ZipEntrySpec("pet.json", manifest),
            ZipEntrySpec("spritesheet.webp", sheet, ZIP_METHOD_DEFLATED, compressedOverride = payload),
        )))
        assertIs<PetLoadOutcome.Success>(load(valid))
        for ((caseIndex, invalid) in listOf(
            valid + byteArrayOf(0x7f),
            valid + byteArrayOf(1, 2, 3),
            valid + deflateRaw(sheet),
            valid.copyOf(valid.size - 1),
            valid + ByteArray(8),
        ).withIndex()) {
            val outcome = load(invalid)
            assertIs<PetLoadOutcome.Failure>(outcome, "case $caseIndex")
            assertIs<PetLoadError.InvalidArchive>(outcome.errors.single())
        }
    }

    // -- F-02: central/local consistency --

    @Test
    fun localFilenameMismatchRejected() {
        val zip = buildZip(
            validPetEntries() + ZipEntrySpec(
                "other.bin",
                byteArrayOf(1),
                localNameOverride = "otherXbin",
            ),
        )
        // other.bin is inert, but the archive itself is malformed.
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun manifestLocalFilenameMismatchRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(id = "zip").encodeToByteArray(),
                    localNameOverride = "petXjson",
                ),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun localSizeMismatchRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(id = "zip").encodeToByteArray(),
                    localUncompSizeOverride = 5L,
                ),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun localCrcMismatchRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(id = "zip").encodeToByteArray(),
                    localCrcOverride = 0x12345678L,
                ),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun localFlagsMismatchRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(id = "zip").encodeToByteArray(),
                    localFlagsOverride = 0x0800,
                ),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun overlappingRangesRejected() {
        // Second entry's central localOffset points into the first entry's payload.
        val manifest = manifestJson(id = "zip").encodeToByteArray()
        val sheet = webpVp8Bytes()
        // pet.json's local header is at 0 with an 8-byte name: payload is [38, 51).
        // Point the sheet record into the middle of that payload.
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifest),
                ZipEntrySpec("spritesheet.webp", sheet, centralLocalOffsetOverride = 43L),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun duplicateLocalOffsetRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes(), centralLocalOffsetOverride = 0L),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun overlappingPayloadRangesRejectedByOverlapGuard() {
        // True overlap with fully consistent headers on both sides: entry A is a
        // valid stored file whose payload embeds a complete, consistent local
        // header + payload for entry B; B's central record points inside A's
        // payload. Only the O(n^2) span check can catch this.
        val bPayload = ByteArray(20) { 0x42 }
        val bCrc = Crc32.of(bPayload)
        // Local header for b.bin (30 bytes + 5-byte name), then its payload.
        val bLocal = ByteWriter().apply {
            le32(0x04034b50L)
            le16(20)
            le16(0) // flags
            le16(0) // stored
            le16(0)
            le16(0)
            le32(bCrc)
            le32(20)
            le32(20)
            le16(5)
            le16(0)
            ascii("b.bin")
            raw(bPayload)
        }.build()
        // Entry A is FIRST so its layout is fixed: local header at 0 ("a.bin"),
        // data at 35. B's consistent local header is embedded at A-data + 10.
        val bLocalOffset = (30 + "a.bin".length + 10).toLong()
        val aData = ByteArray(200)
        bLocal.copyInto(aData, 10)
        val zip = buildZip(
            listOf(
                ZipEntrySpec("a.bin", aData),
                ZipEntrySpec(
                    "b.bin",
                    bPayload,
                    centralLocalOffsetOverride = bLocalOffset,
                ),
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        val error = zipError(zip)
        assertIs<PetLoadError.InvalidArchive>(error)
        assertTrue("overlap" in error.message, error.message)
    }

    // -- F-03: flag policy --

    @Test
    fun dataDescriptorBitRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray(), flagsOverride = 0x08),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes(), flagsOverride = 0x08),
            ),
        )
        val error = zipError(zip)
        assertIs<PetLoadError.InvalidArchive>(error)
        assertTrue("data-descriptor" in error.message)
    }

    @Test
    fun utf8BitAccepted() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray(), flagsOverride = 0x0800),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes(), flagsOverride = 0x0800),
            ),
        )
        val outcome = PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(outcome)
    }

    @Test
    fun reservedBitsIgnored() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray(), flagsOverride = 0x0020),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes(), flagsOverride = 0x0020),
            ),
        )
        assertIs<PetLoadOutcome.Success>(PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default))
    }

    // -- F-06: stored invariant --

    @Test
    fun storedSizeMismatchRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(id = "zip").encodeToByteArray(),
                    centralCompSizeOverride = 1L,
                ),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    // -- F-07: CRC always verified --

    @Test
    fun emptyEntryBadCrcRejected() {
        // Empty selected sheet with a lying CRC: read passes length, CRC must fail.
        assertIs<PetLoadError.InvalidArchive>(sheetWithEmptyBinCrc(0xDEADBEEFL))
    }

    @Test
    fun emptyEntryZeroCrcPassesRead() {
        // CRC32(empty) == 0: the read succeeds and the package then fails closed
        // at the image probe — proving the CRC gate passed rather than skipped.
        assertIs<PetLoadError.UnsupportedImageFormat>(sheetWithEmptyBinCrc(0L))
    }

    private fun sheetWithEmptyBinCrc(crc: Long): PetLoadError {
        val zip = buildZip(
            listOf(
                ZipEntrySpec(
                    "pet.json",
                    manifestJson(extra = "\"spritesheetPath\": \"empty.bin\"").encodeToByteArray(),
                ),
                ZipEntrySpec("empty.bin", ByteArray(0), centralCrcOverride = crc),
            ),
        )
        return zipError(zip)
    }

    // -- F-08: file/directory collisions --

    @Test
    fun fileDirectoryCollisionRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json/", byteArrayOf(), directory = true),
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        val error = zipError(zip)
        assertIs<PetLoadError.DuplicateEntry>(error)
    }

    @Test
    fun directoryDirectoryCollisionRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("sub/", byteArrayOf(), directory = true),
                ZipEntrySpec("SUB/", byteArrayOf(), directory = true),
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.DuplicateEntry>(zipError(zip))
    }

    // -- F-09: integer ratio boundaries --

    @Test
    fun ratioPredicateExact() {
        assertEquals(false, ratioExceeds(0L, 0L, 100.0))
        assertEquals(false, ratioExceeds(0L, 1000L, 100.0))
        assertEquals(true, ratioExceeds(5L, 0L, 100.0))
        assertEquals(false, ratioExceeds(100_000L, 1_000L, 100.0)) // exactly 100x passes
        assertEquals(true, ratioExceeds(100_001L, 1_000L, 100.0)) // just above fails
        assertEquals(false, ratioExceeds(99_999L, 1_000L, 100.0))
        assertEquals(false, ratioExceeds(5L, 5L, 1.0))
        assertEquals(true, ratioExceeds(6L, 5L, 1.0))
        assertEquals(false, ratioExceeds(Long.MAX_VALUE / 2, Long.MAX_VALUE / 4, 100.0))
    }

    // -- F-11: lying metadata bomb --

    @Test
    fun lyingSmallDeclarationLargeDeflateOutput() {
        val sheet = webpVp8Bytes() + ByteArray(4096) { 0x41 }
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "zip").encodeToByteArray()),
                ZipEntrySpec(
                    "spritesheet.webp",
                    sheet,
                    ZIP_METHOD_DEFLATED,
                    centralUncompSizeOverride = 10L,
                ),
            ),
        )
        // Declared 10 bytes, actual ~4 KiB: never Success with partial bytes.
        val error = zipError(zip)
        assertTrue(
            error is PetLoadError.InvalidArchive || error is PetLoadError.LimitExceeded,
            "got $error",
        )
    }

    // -- F-12: encoding scope + external golden --

    @Test
    fun malformedNameBytesAreInert() {
        val zip = buildZip(
            validPetEntries() + ZipEntrySpec("junk", byteArrayOf(9), nameBytesOverride = byteArrayOf(0xFF.toByte())),
        )
        // U+FFFD-substituted name matches nothing: package loads, entry ignored.
        assertIs<PetLoadOutcome.Success>(PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default))
    }

    @Test
    fun substitutionCollisionIsDuplicate() {
        val zip = buildZip(
            validPetEntries() +
                ZipEntrySpec("a", byteArrayOf(1), nameBytesOverride = byteArrayOf(0xFF.toByte())) +
                ZipEntrySpec("b", byteArrayOf(2), nameBytesOverride = byteArrayOf(0xFE.toByte())),
        )
        // Both decode to "\uFFFD": fail closed as duplicates.
        assertIs<PetLoadError.DuplicateEntry>(zipError(zip))
    }

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun externalEncoderGoldenLoads() {
        // Produced by CPython `zipfile` (stored manifest + deflated sheet) with
        // independently computed CRCs — not repo-computed values agreeing
        // with themselves.
        val golden = Base64.decode(EXTERNAL_GOLDEN_ZIP_BASE64)
        val outcome = PetLoader.loadPetZip(golden, "pet", PetPackageLimits.Default)
        val success = assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals("golden", success.definition.id)
        assertEquals(72, success.definition.frameCount)
    }

    // -- F-14: central-size reconciliation --

    @Test
    fun centralSizeMismatchRejected() {
        val zip = buildZip(validPetEntries()).copyOf()
        // EOCD (no comment) occupies the last 22 bytes; centralSize at +12.
        val eocdAt = zip.size - 22
        zip[eocdAt + 12] = 0
        zip[eocdAt + 13] = 0
        zip[eocdAt + 14] = 0
        zip[eocdAt + 15] = 0
        assertIs<PetLoadError.InvalidArchive>(zipError(zip))
    }

    @Test
    fun dotAndEmptySegmentsRejected() {
        for (bad in listOf("./pet.json", "a//pet.json", "a/./pet.json")) {
            val zip = buildZip(
                listOf(
                    ZipEntrySpec(bad, manifestJson(id = "zip").encodeToByteArray()),
                    ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ),
            )
            assertIs<PetLoadError.InvalidArchive>(zipError(zip), bad)
        }
    }
}

private const val EXTERNAL_GOLDEN_ZIP_BASE64: String =
    "UEsDBBQAAAAAAESkOl0s/Bo+JgAAACYAAAAIAAAAcGV0Lmpzb257ImlkIjoiZ29sZGVuIiwiZGlzcGxheU5hbWUiOiJHb2xkZW4ifVBLAwQUAAAACABEpDpdA5BniyEAAABeAAAAEAAAAHNwcml0ZXNoZWV0LndlYnAL8nRzC2NgYAh3dQoIC7BQ8AKydQUY5jJqMbAFsDNQCABQSwECFAMUAAAAAABEpDpdLPwaPiYAAAAmAAAACAAAAAAAAAAAAAAAgAEAAAAAcGV0Lmpzb25QSwECFAMUAAAACABEpDpdA5BniyEAAABeAAAAEAAAAAAAAAAAAAAAgAFMAAAAc3ByaXRlc2hlZXQud2VicFBLBQYAAAAAAgACAHQAAACbAAAAAAA="
