package com.manzl.app.model

/** Shared semantic policy for measured room polygons that represent through-floor vertical voids. */
internal object VerticalVoidRoomPolicy {

    fun isVerticalVoid(room: RoomRegion): Boolean {
        if (room.confidence < MIN_CONFIDENCE) return false
        val label = room.label?.trim()?.lowercase().orEmpty()
        if (label.isBlank()) return false
        return LABELS.any { token -> label.contains(token) }
    }

    private val LABELS = listOf(
        "shaft",
        "service shaft",
        "duct shaft",
        "elevator shaft",
        "lift shaft",
        "شافت",
        "بئر مصعد",
        "فتحة مصعد",
    )

    private const val MIN_CONFIDENCE = 0.66f
}
