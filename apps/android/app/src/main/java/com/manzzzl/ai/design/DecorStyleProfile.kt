package com.manzzzl.ai.design

/**
 * Decorative layer only.
 * Furniture is intentionally excluded at this stage.
 * This profile is used after the 2D -> 3D structural reconstruction.
 */
data class DecorStyleProfile(
    val styleName: String,
    val wallFinishes: List<String>,
    val floorFinishes: List<String>,
    val ceilingStyle: String,
    val lightingStyle: String,
    val materialNotes: List<String>
)

object SaudiModernDecorProfiles {
    val default = DecorStyleProfile(
        styleName = "Saudi Modern Minimal",
        wallFinishes = listOf(
            "warm neutral tones",
            "natural stone accents",
            "local architectural textures"
        ),
        floorFinishes = listOf(
            "large format tiles",
            "natural stone appearance"
        ),
        ceilingStyle = "clean modern ceiling lines with indirect lighting",
        lightingStyle = "energy efficient layered architectural lighting",
        materialNotes = listOf(
            "adapt materials to city climate",
            "respect Saudi villa architectural character",
            "avoid adding furniture"
        )
    )
}
