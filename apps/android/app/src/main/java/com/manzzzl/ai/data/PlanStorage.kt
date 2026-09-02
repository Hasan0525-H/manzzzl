package com.manzzzl.ai.data

/**
 * Keeps plan references in one place before analysis starts.
 * The actual file copy/persistence layer can use this contract.
 */
class PlanStorage {
    private var currentPlanUri: String? = null

    fun savePlan(uri: String) {
        currentPlanUri = uri.takeIf { it.isNotBlank() }
    }

    fun getPlan(): String? = currentPlanUri

    fun clear() {
        currentPlanUri = null
    }
}
