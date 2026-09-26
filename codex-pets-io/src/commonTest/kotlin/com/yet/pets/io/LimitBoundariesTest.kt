package com.yet.pets.io

import com.yet.pets.io.internal.fs.loadPetDirectory
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Permanent limit-1 / limit / limit+1 boundary pins (F-10). Maximum semantics
 * are inclusive: `<= maximum` passes, `> maximum` fails. Every fixture is
 * otherwise valid so the boundary under test is the only variable.
 */
class LimitBoundariesTest {

    private fun manifestOfSize(total: Int): String {
        val base = manifestJson(description = "").length
        require(total >= base) { "target $total below base $base" }
        return manifestJson(description = "x".repeat(total - base))
    }

    private fun sheetOfSize(total: Int): ByteArray {
        val header = webpVp8Bytes()
        require(total >= header.size)
        return header + ByteArray(total - header.size)
    }

    // -- manifest bytes (64 KiB), directory + ZIP --

    @Test
    fun manifestBoundariesDirectory() {
        for ((size, ok) in listOf(65535 to true, 65536 to true, 65537 to false)) {
            val fs = FakeFileSystem()
            fs.createDirectories("/pkg".toPath())
            fs.writeText("/pkg/pet.json".toPath(), manifestOfSize(size))
            fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
            val outcome = loadPetDirectory(fs, "/pkg".toPath(), PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "manifest $size")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "manifest $size")
                val error = failure.errors.single()
                assertIs<PetLoadError.LimitExceeded>(error)
                assertTrue(error.limit == "manifestBytes", error.limit)
            }
        }
    }

    @Test
    fun manifestBoundariesZip() {
        for ((size, ok) in listOf(65535 to true, 65536 to true, 65537 to false)) {
            val zip = buildZip(
                listOf(
                    ZipEntrySpec("pet.json", manifestOfSize(size).encodeToByteArray()),
                    ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ),
            )
            val outcome = PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "manifest $size")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "manifest $size")
                assertIs<PetLoadError.LimitExceeded>(failure.errors.single())
            }
        }
    }

    // -- spritesheet bytes (8 MiB), directory + ZIP --

    @Test
    fun spritesheetBoundariesDirectory() {
        val cap = 8 * 1024 * 1024
        for ((size, ok) in listOf(cap - 1 to true, cap to true, cap + 1 to false)) {
            val fs = FakeFileSystem()
            fs.createDirectories("/pkg".toPath())
            fs.writeText("/pkg/pet.json".toPath(), manifestJson())
            fs.writeBytes("/pkg/spritesheet.webp".toPath(), sheetOfSize(size))
            val outcome = loadPetDirectory(fs, "/pkg".toPath(), PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "sheet $size")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "sheet $size")
                val error = failure.errors.single()
                assertIs<PetLoadError.LimitExceeded>(error)
                assertTrue(error.limit == "spritesheetBytes", error.limit)
            }
        }
    }

    @Test
    fun spritesheetBoundariesZip() {
        val cap = 8 * 1024 * 1024
        for ((size, ok) in listOf(cap - 1 to true, cap to true, cap + 1 to false)) {
            val zip = buildZip(
                listOf(
                    ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                    ZipEntrySpec("spritesheet.webp", sheetOfSize(size)),
                ),
            )
            val outcome = PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "sheet $size")
            } else {
                assertIs<PetLoadOutcome.Failure>(outcome, "sheet $size")
            }
        }
    }

    // -- raw archive (16 MiB) --

    @Test
    fun rawArchiveBoundaries() {
        val cap = 16 * 1024 * 1024
        val base = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        // Per-entry overhead for the filler: local 30 + name + central 46 + name.
        val fillerName = "pad.bin"
        val overhead = 30 + fillerName.length + 46 + fillerName.length
        for ((size, ok) in listOf(cap - 1 to true, cap to true, cap + 1 to false)) {
            val fillerData = size - base.size - overhead
            val zip = buildZip(
                listOf(
                    ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                    ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                    ZipEntrySpec(fillerName, ByteArray(fillerData)),
                ),
            )
            assertTrue(zip.size == size, "fixture size ${zip.size} != $size")
            val outcome = PetLoader.loadPetZip(zip, "pet", PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "raw $size")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "raw $size")
                val error = failure.errors.single()
                assertIs<PetLoadError.LimitExceeded>(error)
                assertTrue(error.limit == "compressedArchiveBytes", error.limit)
            }
        }
    }

    // -- entry count (64) --

    @Test
    fun entryCountBoundaries() {
        for ((total, ok) in listOf(63 to true, 64 to true, 65 to false)) {
            val specs = mutableListOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            )
            repeat(total - 2) { i -> specs += ZipEntrySpec("filler-$i.bin", byteArrayOf(1)) }
            val outcome = PetLoader.loadPetZip(buildZip(specs), "pet", PetPackageLimits.Default)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "entries $total")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "entries $total")
                val error = failure.errors.single()
                assertIs<PetLoadError.LimitExceeded>(error)
                assertTrue(error.limit == "zipEntries", error.limit)
            }
        }
    }

    // -- per-entry uncompressed (custom tight budget isolates the per-entry cap) --

    @Test
    fun perEntryBoundaries() {
        val limits = PetPackageLimits.Default.copy(maxEntryUncompressedBytes = 8192L)
        for ((size, ok) in listOf(8191 to true, 8192 to true, 8193 to false)) {
            val zip = buildZip(
                listOf(
                    ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                    ZipEntrySpec("spritesheet.webp", sheetOfSize(size)),
                ),
            )
            val outcome = PetLoader.loadPetZip(zip, "pet", limits)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "entry $size")
            } else {
                assertIs<PetLoadOutcome.Failure>(outcome, "entry $size")
            }
        }
    }

    // -- total uncompressed (custom small budget, exact tuned totals) --

    @Test
    fun totalUncompressedBoundaries() {
        val manifest = manifestJson().encodeToByteArray()
        val sheet = webpVp8Bytes()
        val fixed = manifest.size + sheet.size
        val limits = PetPackageLimits.Default.copy(maxUncompressedArchiveBytes = 1000L)
        for ((total, ok) in listOf(999 to true, 1000 to true, 1001 to false)) {
            val zip = buildZip(
                listOf(
                    ZipEntrySpec("pet.json", manifest),
                    ZipEntrySpec("spritesheet.webp", sheet),
                    ZipEntrySpec("pad.bin", ByteArray(total - fixed)),
                ),
            )
            val outcome = PetLoader.loadPetZip(zip, "pet", limits)
            if (ok) {
                assertIs<PetLoadOutcome.Success>(outcome, "total $total")
            } else {
                val failure = assertIs<PetLoadOutcome.Failure>(outcome, "total $total")
                val error = failure.errors.single()
                assertIs<PetLoadError.LimitExceeded>(error)
                assertTrue(error.limit == "uncompressedArchiveBytes", error.limit)
            }
        }
    }
}
