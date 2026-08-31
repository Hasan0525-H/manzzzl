package com.manzl.app.design

import com.manzl.app.model.FloorPlan
import kotlin.math.max

/**
 * Converts measured floor-plan geometry into a visual/architectural presentation profile.
 *
 * This engine is intentionally local and deterministic. During product development we may study
 * public Saudi residential references and benchmark third-party AI services, but the release APK
 * consumes only the distilled, redistributable design priors in SaudiResidentialKnowledge.
 */
internal object ReferenceDrivenDesignEngine {

    fun synthesize(plan: FloorPlan): HouseRenderProfile {
        val area = plan.widthMeters * plan.depthMeters
        val wallDensity = plan.walls.size / max(area, 1f)
        val doorwayConfidence = if (plan.doors.isEmpty()) 0f else plan.doors.map { it.confidence }.average().toFloat()

        // We do not infer a Saudi region from geometry alone. The balanced contemporary profile is
        // the safe default; future UI can let the user explicitly choose Najdi/Hijazi/Eastern cues.
        val palette = SaudiResidentialKnowledge.palettes.first { it.id == "saudi_contemporary_warm" }

        val quality = (
            plan.analysisConfidence * 0.62f +
                doorwayConfidence * 0.22f +
                wallDensity.coerceIn(0f, 0.7f) * 0.16f
            ).coerceIn(0f, 0.98f)

        return HouseRenderProfile(
            id = "saudi_contemporary_balanced",
            palette = palette,
            wallHeightMeters = when {
                area >= 180f -> 3.35f
                area >= 120f -> 3.25f
                else -> 3.10f
            },
            doorHeightMeters = if (area >= 140f) 2.25f else 2.15f,
            skirtingHeightMeters = 0.11f,
            privacyPriority = principle("privacy_gradient"),
            shadingPriority = principle("solar_shading"),
            courtyardPriority = principle("courtyard_daylight"),
            renderConfidence = quality,
        )
    }

    private fun principle(id: String): Float =
        SaudiResidentialKnowledge.principles.firstOrNull { it.id == id }?.weight ?: 0.5f
}

internal data class HouseRenderProfile(
    val id: String,
    val palette: SaudiPalette,
    val wallHeightMeters: Float,
    val doorHeightMeters: Float,
    val skirtingHeightMeters: Float,
    val privacyPriority: Float,
    val shadingPriority: Float,
    val courtyardPriority: Float,
    val renderConfidence: Float,
)
