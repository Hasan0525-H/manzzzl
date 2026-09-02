package com.manzzzl.ai.design

/**
 * Controls redesign requests while keeping the original floor plan.
 * Geometry stays unchanged; only visual design layers can be regenerated.
 */
object DesignRegenerationController {
    fun regenerateFacade(
        session: FinalDesignSession,
        newStyle: String? = null,
        newMaterial: String? = null
    ): FinalDesignSession {
        return session.copy(
            facadeStyle = newStyle ?: session.facadeStyle,
            materialProfile = newMaterial ?: session.materialProfile
        )
    }
}
