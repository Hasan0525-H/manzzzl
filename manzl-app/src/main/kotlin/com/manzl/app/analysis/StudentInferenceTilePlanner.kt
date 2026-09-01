package com.manzl.app.analysis

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Plans overlapping high-resolution source crops for the 512px reconstruction student.
 *
 * A single global 512px pass is valuable for topology but can erase a 10–20px partition or opening
 * on a 4K architectural sheet. These tiles preserve context while increasing effective pixels per
 * wall. They are source-coordinate regions only; every decoded vector is mapped back to the original
 * raster before deterministic adjudication.
 */
internal object StudentInferenceTilePlanner {

    data class Region(
        val left: Int,
        val top: Int,
        val rightExclusive: Int,
        val bottomExclusive: Int,
        val kind: Kind,
    ) {
        enum class Kind { GLOBAL, DETAIL }
        val width: Int get() = rightExclusive - left
        val height: Int get() = bottomExclusive - top
    }

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        contentBounds: PixelContentBounds,
        maxDetailSidePx: Int = DEFAULT_MAX_DETAIL_SIDE_PX,
        overlapFraction: Float = DEFAULT_OVERLAP_FRACTION,
        maxRegions: Int = DEFAULT_MAX_REGIONS,
    ): List<Region> {
        if (imageWidth <= 0 || imageHeight <= 0 || maxRegions <= 0) return emptyList()
        val global = Region(0, 0, imageWidth, imageHeight, Region.Kind.GLOBAL)
        if (maxRegions == 1) return listOf(global)

        val bounds = PixelContentBounds(
            left = contentBounds.left.coerceIn(0, imageWidth - 1),
            top = contentBounds.top.coerceIn(0, imageHeight - 1),
            rightExclusive = contentBounds.rightExclusive.coerceIn(1, imageWidth),
            bottomExclusive = contentBounds.bottomExclusive.coerceIn(1, imageHeight),
        )
        if (bounds.width <= 1 || bounds.height <= 1) return listOf(global)

        // If the structural content already fits inside roughly one detail tile, a second inference
        // would carry almost the same information as the global pass and only waste battery/RAM.
        val contentLongest = max(bounds.width, bounds.height)
        if (contentLongest <= maxDetailSidePx * SKIP_DETAIL_IF_WITHIN_RATIO) return listOf(global)

        val safeOverlap = overlapFraction.coerceIn(MIN_OVERLAP_FRACTION, MAX_OVERLAP_FRACTION)
        val targetSide = min(maxDetailSidePx, contentLongest).coerceAtLeast(MIN_DETAIL_SIDE_PX)
        val step = max(MIN_DETAIL_STEP_PX, (targetSide * (1f - safeOverlap)).toInt())
        val xStarts = starts(bounds.left, bounds.rightExclusive, targetSide, step)
        val yStarts = starts(bounds.top, bounds.bottomExclusive, targetSide, step)

        val details = ArrayList<Region>()
        for (top in yStarts) {
            for (left in xStarts) {
                val right = min(bounds.rightExclusive, left + targetSide)
                val bottom = min(bounds.bottomExclusive, top + targetSide)
                if (right - left < MIN_DETAIL_SIDE_PX / 2 || bottom - top < MIN_DETAIL_SIDE_PX / 2) continue
                details += Region(left, top, right, bottom, Region.Kind.DETAIL)
            }
        }

        // Prefer broad central coverage first when device limits cap tile count. Sorting by distance
        // from content centre gives deterministic, spatially balanced coverage rather than scan-order
        // bias toward the top-left of the drawing.
        val cx = (bounds.left + bounds.rightExclusive) * 0.5f
        val cy = (bounds.top + bounds.bottomExclusive) * 0.5f
        val ordered = details.sortedBy { region ->
            val rx = (region.left + region.rightExclusive) * 0.5f
            val ry = (region.top + region.bottomExclusive) * 0.5f
            val dx = rx - cx
            val dy = ry - cy
            dx * dx + dy * dy
        }

        return buildList {
            add(global)
            addAll(ordered.take(maxRegions - 1))
        }
    }

    private fun starts(from: Int, toExclusive: Int, tile: Int, step: Int): List<Int> {
        val span = toExclusive - from
        if (span <= tile) return listOf(from)
        val count = max(2, ceil((span - tile) / step.toFloat()).toInt() + 1)
        val last = toExclusive - tile
        return (0 until count)
            .map { index ->
                if (index == count - 1) last
                else min(last, from + index * step)
            }
            .distinct()
    }

    private const val DEFAULT_MAX_DETAIL_SIDE_PX = 1200
    private const val DEFAULT_OVERLAP_FRACTION = 0.28f
    private const val DEFAULT_MAX_REGIONS = 13
    private const val MIN_OVERLAP_FRACTION = 0.15f
    private const val MAX_OVERLAP_FRACTION = 0.45f
    private const val MIN_DETAIL_SIDE_PX = 560
    private const val MIN_DETAIL_STEP_PX = 320
    private const val SKIP_DETAIL_IF_WITHIN_RATIO = 1.15f
}
