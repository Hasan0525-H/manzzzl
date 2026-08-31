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

@Immutable
data class DoorOpening(
    val center: Vec2,
    val widthMeters: Float,
    val rotationDegrees: Float,
    val confidence: Float,
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
)

@Immutable
data class AnalysisUpdate(
    val percent: Int,
    val messageArabic: String,
)
