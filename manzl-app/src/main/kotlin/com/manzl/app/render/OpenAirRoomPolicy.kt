package com.manzl.app.render

import com.manzl.app.model.RoomRegion

/**
 * Conservative ceiling policy for spaces that the on-device semantic pipeline has explicitly
 * identified as open to the sky.
 *
 * Geometry is still authoritative: this policy never creates or reshapes a room. It only prevents
 * HouseMeshBuilder from placing a ceiling over a trusted room polygon whose accepted label clearly
 * denotes a courtyard/yard/lightwell/garden/roof terrace. Unknown or weakly inferred rooms remain
 * covered so an ambiguous OCR result cannot punch a hole in the building.
 */
internal object OpenAirRoomPolicy {

    fun shouldRemainOpenToSky(room: RoomRegion): Boolean {
        if (room.confidence < MIN_ROOM_CONFIDENCE) return false
        val label = room.label?.trim()?.lowercase().orEmpty()
        if (label.isEmpty()) return false
        return OPEN_AIR_LABELS.any { token -> label.contains(token) }
    }

    private val OPEN_AIR_LABELS = listOf(
        "فناء",
        "حوش",
        "منور",
        "حديقة",
        "سطح",
        "courtyard",
        "patio",
        "yard",
        "lightwell",
        "light well",
        "garden",
        "roof terrace",
    )

    private const val MIN_ROOM_CONFIDENCE = 0.74f
}
