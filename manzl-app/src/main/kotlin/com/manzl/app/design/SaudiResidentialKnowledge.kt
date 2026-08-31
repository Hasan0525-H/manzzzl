package com.manzl.app.design

/**
 * Compact, rights-safe design priors distilled from public architectural descriptions and Saudi
 * design guidance. No third-party photographs, meshes or copyrighted plans are bundled here.
 *
 * These priors are intentionally descriptive rather than prescriptive. They influence materials,
 * light, privacy and presentation; they never move a wall or change a measured room boundary.
 */
internal object SaudiResidentialKnowledge {

    val principles: List<DesignPrinciple> = listOf(
        DesignPrinciple(
            id = "local_identity_with_contemporary_expression",
            weight = 1.0f,
            note = "Preserve local architectural identity while allowing contemporary expression.",
        ),
        DesignPrinciple(
            id = "privacy_gradient",
            weight = 0.96f,
            note = "Prefer a clear transition from guest/reception space toward family-private space.",
        ),
        DesignPrinciple(
            id = "majlis_reception_priority",
            weight = 0.90f,
            note = "Treat a likely majlis/reception zone as a culturally important public-facing room.",
        ),
        DesignPrinciple(
            id = "solar_shading",
            weight = 0.94f,
            note = "Use deep reveals, louvers or screened openings as climate-aware façade cues.",
        ),
        DesignPrinciple(
            id = "warm_mineral_materials",
            weight = 0.88f,
            note = "Favor warm stone, limestone/sandstone tones, off-white plaster and restrained wood.",
        ),
        DesignPrinciple(
            id = "courtyard_daylight",
            weight = 0.82f,
            note = "When the plan already contains an internal void/courtyard, emphasize daylight and shade there.",
        ),
        DesignPrinciple(
            id = "desert_landscape_palette",
            weight = 0.78f,
            note = "Exterior palette can draw from sand, limestone, clay and muted vegetation tones.",
        ),
    )

    val palettes: List<SaudiPalette> = listOf(
        SaudiPalette(
            id = "saudi_contemporary_warm",
            wall = Rgb(0.94f, 0.92f, 0.87f),
            floor = Rgb(0.73f, 0.68f, 0.59f),
            ceiling = Rgb(0.98f, 0.975f, 0.96f),
            stone = Rgb(0.67f, 0.58f, 0.46f),
            wood = Rgb(0.34f, 0.24f, 0.17f),
            accent = Rgb(0.18f, 0.17f, 0.16f),
        ),
        SaudiPalette(
            id = "najdi_contemporary",
            wall = Rgb(0.86f, 0.78f, 0.66f),
            floor = Rgb(0.62f, 0.52f, 0.40f),
            ceiling = Rgb(0.96f, 0.93f, 0.87f),
            stone = Rgb(0.55f, 0.42f, 0.29f),
            wood = Rgb(0.29f, 0.20f, 0.14f),
            accent = Rgb(0.24f, 0.17f, 0.12f),
        ),
        SaudiPalette(
            id = "coastal_eastern_light",
            wall = Rgb(0.95f, 0.94f, 0.90f),
            floor = Rgb(0.78f, 0.76f, 0.70f),
            ceiling = Rgb(0.99f, 0.99f, 0.98f),
            stone = Rgb(0.72f, 0.69f, 0.61f),
            wood = Rgb(0.43f, 0.32f, 0.23f),
            accent = Rgb(0.21f, 0.25f, 0.25f),
        ),
    )
}

internal data class DesignPrinciple(
    val id: String,
    val weight: Float,
    val note: String,
)

internal data class SaudiPalette(
    val id: String,
    val wall: Rgb,
    val floor: Rgb,
    val ceiling: Rgb,
    val stone: Rgb,
    val wood: Rgb,
    val accent: Rgb,
)

internal data class Rgb(
    val r: Float,
    val g: Float,
    val b: Float,
)
