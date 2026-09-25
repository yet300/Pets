package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun defaultGeometryMatchesV1Profile() {
        val geometry = CodexV1.defaultGeometry()
        assertEquals(AtlasGeometry(1536, 1872, 8, 9, 192, 208), geometry)
        assertEquals(72L, geometry.frameCapacity)
    }

    @Test
    fun exactCoverCustomGridsAccepted() {
        val tall = parseSuccess(
            """{"frame": {"width": 384, "height": 104, "columns": 4, "rows": 18}}""",
        )
        assertEquals(AtlasGeometry(1536, 1872, 4, 18, 384, 104), tall.definition.geometry)
        assertEquals(72, tall.definition.frameCount)

        val wide = parseSuccess(
            """{"frame": {"width": 96, "height": 208, "columns": 16, "rows": 9}}""",
        )
        assertEquals(144, wide.definition.frameCount)
    }

    @Test
    fun zeroGridValuesRejected() {
        for (frame in listOf(
            """{"width": 0, "height": 208, "columns": 8, "rows": 9}""",
            """{"width": 192, "height": 0, "columns": 8, "rows": 9}""",
            """{"width": 192, "height": 208, "columns": 0, "rows": 9}""",
            """{"width": 192, "height": 208, "columns": 8, "rows": 0}""",
            """{"width": -192, "height": 208, "columns": 8, "rows": 9}""",
        )) {
            val report = parseFailure("""{"frame": $frame}""")
            assertEquals(1, report.errors.size)
            assertIs<PetCompatibilityError.InvalidFrameGrid>(report.errors.first())
        }
    }

    @Test
    fun nonCoveringGridRejected() {
        val report = parseFailure("""{"frame": {"width": 192, "height": 208, "columns": 7, "rows": 9}}""")
        assertIs<PetCompatibilityError.InvalidFrameGrid>(report.errors.first())
    }

    @Test
    fun hugeValuesRejectedWithoutOverflow() {
        val report = parseFailure(
            """{"frame": {"width": 2000000000, "height": 2000000000, "columns": 2000000000, "rows": 2000000000}}""",
        )
        // Long arithmetic: products simply do not cover 1536x1872. No crash, no wrap.
        assertIs<PetCompatibilityError.InvalidFrameGrid>(report.errors.first())
    }

    @Test
    fun frameCountAbove256RejectedWithExactCount() {
        val report = parseFailure(
            """{"frame": {"width": 8, "height": 8, "columns": 192, "rows": 234}}""",
        )
        val error = report.errors.first()
        assertIs<PetCompatibilityError.FrameCountExceeded>(error)
        assertEquals(44928L, error.count)
        assertEquals(256, error.maximum)
    }

    @Test
    fun smallValidGridRejectsDefaultTableIndices() {
        // 1536x208 1x9 grid = 9 frames: default running-right needs indices 8..15.
        val report = parseFailure(
            """{"frame": {"width": 1536, "height": 208, "columns": 1, "rows": 9}}""",
        )
        val outOfRange = report.errors.filterIsInstance<PetCompatibilityError.SpriteIndexOutOfRange>()
        assertTrue(outOfRange.isNotEmpty())
        // Sorted validation order reports aliases first; running-right must be among them.
        assertTrue(outOfRange.any { it.animation == "running-right" })
        assertTrue(outOfRange.all { it.frameCount == 9 })
    }

    @Test
    fun sourceRectBoundaries() {
        val geometry = CodexV1.defaultGeometry()
        assertEquals(IntRect(0, 0, 192, 208), geometry.sourceRectForOrNull(0))
        assertEquals(IntRect(7 * 192, 0, 192, 208), geometry.sourceRectForOrNull(7))
        assertEquals(IntRect(0, 208, 192, 208), geometry.sourceRectForOrNull(8))
        assertEquals(IntRect(7 * 192, 7 * 208, 192, 208), geometry.sourceRectForOrNull(63))
        assertEquals(IntRect(0, 8 * 208, 192, 208), geometry.sourceRectForOrNull(64))
        assertEquals(IntRect(7 * 192, 8 * 208, 192, 208), geometry.sourceRectForOrNull(71))
        assertNull(geometry.sourceRectForOrNull(-1))
        assertNull(geometry.sourceRectForOrNull(72))
        assertNull(geometry.sourceRectForOrNull(Int.MAX_VALUE))
        assertNull(geometry.sourceRectForOrNull(Int.MIN_VALUE))
    }

    @Test
    fun sourceRectCustomGrid() {
        val geometry = AtlasGeometry(1536, 1872, 4, 18, 384, 104)
        assertEquals(IntRect(0, 0, 384, 104), geometry.sourceRectForOrNull(0))
        assertEquals(IntRect(3 * 384, 0, 384, 104), geometry.sourceRectForOrNull(3))
        assertEquals(IntRect(0, 104, 384, 104), geometry.sourceRectForOrNull(4))
        assertEquals(IntRect(3 * 384, 17 * 104, 384, 104), geometry.sourceRectForOrNull(71))
        assertNull(geometry.sourceRectForOrNull(72))
    }

    @Test
    fun constructorRejectsNonPositiveDimensions() {
        assertFailsWith<IllegalArgumentException> { AtlasGeometry(0, 1872, 8, 9, 192, 208) }
        assertFailsWith<IllegalArgumentException> { AtlasGeometry(1536, 1872, 0, 9, 192, 208) }
        assertFailsWith<IllegalArgumentException> { AtlasGeometry(1536, 1872, 8, 9, 192, -1) }
        // SpritesheetInfo stays total (see ModelInvariantsTest.spritesheetInfoConstructorIsTotal).
    }
}
