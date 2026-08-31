package com.manzl.app.analysis

import android.content.Context

/**
 * Process-local holder for the application context required to open bundled model assets.
 *
 * Only applicationContext is retained; there is no Activity/View reference and no network client.
 * Initialisation happens once from MainActivity before Compose constructs the analyzer.
 */
internal object UltraReconstructionRuntime {
    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun createStudentExpertOrNull(): ManzlStudentFloorPlanExpert? =
        applicationContext?.let(::ManzlStudentFloorPlanExpert)

    fun modelAvailabilityOrNull(): UltraModelAvailability? {
        val context = applicationContext ?: return null
        return OnnxAssetModelRepository(context).use { it.availability() }
    }
}
