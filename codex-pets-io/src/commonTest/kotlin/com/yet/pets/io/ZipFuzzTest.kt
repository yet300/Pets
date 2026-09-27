package com.yet.pets.io

import com.yet.pets.core.EncodedSpritesheetProbe as ImageProbe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministic foreign-input totality: every truncation and a sweep of
 * single-byte corruptions must yield Success or typed Failure — never an
 * uncaught exception, infinite loop, or pathological allocation.
 */
class ZipFuzzTest {

    private val valid: ByteArray = buildZip(
        listOf(
            ZipEntrySpec("pet.json", manifestJson(id = "fuzz").encodeToByteArray()),
            ZipEntrySpec("spritesheet.webp", webpVp8Bytes(), ZIP_METHOD_DEFLATED),
        ),
    )

    @Test
    fun truncationSweepIsTotal() {
        assertIs<PetLoadOutcome.Success>(PetLoader.loadPetZip(valid, "pet", PetPackageLimits.Default))
        for (length in 0 until valid.size) {
            val outcome = try {
                PetLoader.loadPetZip(valid.copyOfRange(0, length), "pet", PetPackageLimits.Default)
            } catch (t: Throwable) {
                throw AssertionError("truncation at $length threw ${t::class.simpleName}: ${t.message}")
            }
            assertIs<PetLoadOutcome.Failure>(
                outcome,
                "truncation at $length of ${valid.size} must fail closed, got $outcome",
            )
        }
    }

    @Test
    fun corruptionSweepIsTotal() {
        // Deterministic stride over the archive: every outcome typed, none thrown.
        var failures = 0
        var successes = 0
        var at = 0
        while (at < valid.size) {
            val corrupted = valid.copyOf()
            corrupted[at] = (corrupted[at] + 0x40).toByte()
            val outcome = try {
                PetLoader.loadPetZip(corrupted, "pet", PetPackageLimits.Default)
            } catch (t: Throwable) {
                throw AssertionError("corruption at $at threw ${t::class.simpleName}: ${t.message}")
            }
            when (outcome) {
                is PetLoadOutcome.Success -> successes++
                is PetLoadOutcome.Failure -> {
                    failures++
                    assertTrue(outcome.errors.isNotEmpty(), "empty error list at $at")
                }
            }
            at += 7
        }
        assertTrue(failures > 0, "expected at least one corruption to fail")
    }

    @Test
    fun probeTruncationSweepIsTotal() {
        val fixtures = mapOf(
            "png" to pngBytes(),
            "gif" to gifBytes(),
            "jpeg" to jpegBytes(),
            "vp8" to webpVp8Bytes(),
            "vp8l" to webpVp8lBytes(),
            "vp8x" to webpVp8xBytes(),
        )
        for ((name, full) in fixtures) {
            for (length in 0 until full.size step 3) {
                val bytes = full.copyOfRange(0, length)
                val probed = try {
                    ImageProbe.probe(bytes)
                } catch (t: Throwable) {
                    throw AssertionError("$name truncation at $length threw ${t::class.simpleName}")
                }
                if (probed != null) {
                    assertTrue(probed.width > 0 && probed.height > 0, "$name at $length: $probed")
                }
            }
            // Full fixtures must probe.
            assertTrue(ImageProbe.probe(full) != null, "$name full fixture")
        }
    }
}
