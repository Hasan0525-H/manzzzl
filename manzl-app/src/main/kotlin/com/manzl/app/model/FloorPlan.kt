package com.manzl.app.model

import androidx.compose.runtime.Immutable

@Immutable
data class Vec2(
    val x: Float,
    val z: Float,
)

@Immutable
data class WallSegment(
    val start: Vec2,
    val end: Vec2,
    val thicknessMeters: Float = 0.18f,
    val heightMeters: Float = 3.0f,
    val confidence: Float = 1f,
)

/**
 * Hinge endpoint relative to the opening axis defined by [DoorOpening.rotationDegrees].
 * AXIS_START is centre - axis * width/2, AXIS_END is centre + axis * width/2.
 */
@Immutable
enum class DoorHingeSide {
    AXIS_START,
    AXIS_END,
    UNKNOWN,
}

/**
 * Side of the wall to which the leaf swings, relative to the opening axis normal (-sin, cos).
 */
@Immutable
enum class DoorSwingSide {
    NEGATIVE_NORMAL,
    POSITIVE_NORMAL,
    UNKNOWN,
}

@Immutable
data class DoorOpening(
    val center: Vec2,
    val widthMeters: Float,
    val rotationDegrees: Float,
    val confidence: Float,
    val hingeSide: DoorHingeSide = DoorHingeSide.UNKNOWN,
    val swingSide: DoorSwingSide = DoorSwingSide.UNKNOWN,
    val swingConfidence: Float = 0f,
)

@Immutable
data class WindowOpening(
    val center: Vec2,
    val widthMeters: Float,
    val rotationDegrees: Float,
    val sillHeightMeters: Float = 0.90f,
    val heightMeters: Float = 1.35f,
    val confidence: Float,
)

@Immutable
data class Staircase(
    val center: Vec2,
    val widthMeters: Float,
    val runMeters: Float,
    val rotationDegrees: Float,
    val stepCount: Int,
    val floorToFloorHeightMeters: Float = 3.20f,
    val confidence: Float,
)

@Immutable
data class RoomRegion(
    val id: String,
    val polygon: List<Vec2>,
    val label: String? = null,
    val confidence: Float,
)

@Immutable
data class FloorPlan(
    val widthMeters: Float,
    val depthMeters: Float,
    val walls: List<WallSegment>,
    val doors: List<DoorOpening> = emptyList(),
    val windows: List<WindowOpening> = emptyList(),
    val stairs: List<Staircase> = emptyList(),
    val rooms: List<RoomRegion> = emptyList(),
    val analysisConfidence: Float,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val scaleConfidence: Float = 0f,
    val scaleSource: String = "unknown",
    /** Structural drawing envelope inside the uploaded raster; normalized to the original image. */
    val contentLeftFraction: Float = 0f,
    val contentTopFraction: Float = 0f,
    val contentRightFraction: Float = 1f,
    val contentBottomFraction: Float = 1f,
)

@Immutable
data class AnalysisUpdate(
    val percent: Int,
    val messageArabic: String,
)
