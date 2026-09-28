package com.yet.pets.core

/** Plain integer rectangle in atlas-pixel space. Core-owned; renderers convert it. */
public data class IntRect(
    public val left: Int,
    public val top: Int,
    public val width: Int,
    public val height: Int,
)

/**
 * Atlas grid geometry. The single source of truth for cell layout: frames store
 * only a [sprite index][PetFrame.spriteIndex], never a rectangle.
 *
 * Generic runtime: arbitrary rectangular atlases are supported. Requirements:
 * all dimensions positive, `atlasWidth % cellWidth == 0`,
 * `atlasHeight % cellHeight == 0`, with derived `columns`, `rows`, and
 * `frameCapacity = columns * rows`. Use overflow-safe arithmetic. No generic
 * requirement of 1536/1872/2288/8/9/11/192/208 — the Codex V1 adapter still
 * enforces its exact constants.
 *
 * The constructor is internal (validated instances come from parsers);
 * fail-fast guards cover positivity, exact grid cover, and overflow-safe
 * products. Profile rules are checked by compatibility validation, which
 * returns typed reports instead of throwing.
 */
@ConsistentCopyVisibility
public data class AtlasGeometry internal constructor(
    public val atlasWidth: Int,
    public val atlasHeight: Int,
    public val columns: Int,
    public val rows: Int,
    public val cellWidth: Int,
    public val cellHeight: Int,
) {
    init {
        require(atlasWidth > 0) { "atlasWidth must be positive, got $atlasWidth" }
        require(atlasHeight > 0) { "atlasHeight must be positive, got $atlasHeight" }
        require(columns > 0) { "columns must be positive, got $columns" }
        require(rows > 0) { "rows must be positive, got $rows" }
        require(cellWidth > 0) { "cellWidth must be positive, got $cellWidth" }
        require(cellHeight > 0) { "cellHeight must be positive, got $cellHeight" }
        require(columns.toLong() * cellWidth <= Int.MAX_VALUE) {
            "grid width overflows Int: $columns * $cellWidth"
        }
        require(rows.toLong() * cellHeight <= Int.MAX_VALUE) {
            "grid height overflows Int: $rows * $cellHeight"
        }
        require(columns.toLong() * cellWidth == atlasWidth.toLong()) {
            "grid must cover atlas width exactly: $columns * $cellWidth != $atlasWidth"
        }
        require(rows.toLong() * cellHeight == atlasHeight.toLong()) {
            "grid must cover atlas height exactly: $rows * $cellHeight != $atlasHeight"
        }
    }

    /** Total cell capacity as [Long] so it can never overflow. */
    public val frameCapacity: Long
        get() = columns.toLong() * rows.toLong()

    /**
     * Source rectangle for [spriteIndex], or `null` for invalid indices
     * (negative or at/above capacity). Deterministic; never throws, wraps,
     * or divides by zero.
     */
    public fun sourceRectForOrNull(spriteIndex: Int): IntRect? {
        if (spriteIndex < 0 || spriteIndex.toLong() >= frameCapacity) return null
        // Overflow-safe: both factors are bounded by the constructor guarantees
        // ((columns - 1) * cellWidth < columns * cellWidth <= Int.MAX_VALUE).
        val x = (spriteIndex % columns) * cellWidth
        val y = (spriteIndex / columns) * cellHeight
        return IntRect(left = x, top = y, width = cellWidth, height = cellHeight)
    }
}

/**
 * Unchecked fast path for indices already guaranteed valid (e.g. produced by
 * [samplePetAnimation] against the same definition). Not public API.
 */
internal fun AtlasGeometry.sourceRectFor(spriteIndex: Int): IntRect =
    sourceRectForOrNull(spriteIndex)
        ?: error("sprite index $spriteIndex out of range for $columns x $rows grid")
