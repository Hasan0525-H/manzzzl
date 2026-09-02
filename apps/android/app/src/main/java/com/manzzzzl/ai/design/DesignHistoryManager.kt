package com.manzzzl.ai.design

/**
 * Keeps previous generated design variants so users can compare and restore.
 * Geometry remains unchanged; only design layers are versioned.
 */
object DesignHistoryManager {
    private val history = mutableListOf<FinalDesignSession>()

    fun save(session: FinalDesignSession) {
        history.add(session)
    }

    fun getAll(): List<FinalDesignSession> = history.toList()

    fun latest(): FinalDesignSession? = history.lastOrNull()
}
