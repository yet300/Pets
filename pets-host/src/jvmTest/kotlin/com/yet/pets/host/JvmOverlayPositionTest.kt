package com.yet.pets.host

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmOverlayPositionTest {
    private val primary = Rectangle(0, 0, 1920, 1080)

    @Test fun singleDisplayKeepsPartOfPetReachable() {
        assertEquals(Point(1872, 1032), recoverableOverlayLocation(
            Point(Int.MAX_VALUE, Int.MAX_VALUE), 96, 96, listOf(primary)))
        assertEquals(Point(-48, -48), recoverableOverlayLocation(
            Point(Int.MIN_VALUE, Int.MIN_VALUE), 96, 96, listOf(primary)))
    }

    @Test fun secondaryDisplayOnLeftAndAboveAreValid() {
        val left = Rectangle(-1920, 0, 1920, 1080)
        val above = Rectangle(0, -1080, 1920, 1080)
        assertEquals(Point(-900, 200), recoverableOverlayLocation(
            Point(-900, 200), 96, 96, listOf(left, primary)))
        assertEquals(Point(300, -600), recoverableOverlayLocation(
            Point(300, -600), 96, 96, listOf(above, primary)))
    }

    @Test fun differentDisplaySizesAndPartialEdge() {
        val small = Rectangle(-1280, -200, 1280, 720)
        assertEquals(Point(-1270, -190), recoverableOverlayLocation(
            Point(-1270, -190), 96, 96, listOf(small, primary)))
        val result = recoverableOverlayLocation(Point(-5000, 200), 96, 96, listOf(small, primary))
        assertTrue(result.x < 0)
        assertTrue(result.x >= -1328)
    }
}
