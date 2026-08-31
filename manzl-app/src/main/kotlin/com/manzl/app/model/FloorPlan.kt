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
data class FloorPlan(
    val widthMeters: Float,
    val depthMeters: Float,
    val walls: List<WallSegment>,
    val doors: List<DoorOpening> = emptyList(),
    val analysisConfidence: Float,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
)

@Immutable
data class AnalysisUpdate(
    val percent: Int,
    val messageArabic: String,
)
