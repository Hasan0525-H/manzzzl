package com.manzzzl.ai.viewmodel

import com.manzzzl.ai.data.LocalProjectStore
import com.manzzzl.ai.model.HouseProject

class ProjectViewModel {
    private val store = LocalProjectStore()

    private var currentProject: HouseProject? = null
    private var progress: Int = 0

    fun create(project: HouseProject) {
        currentProject = project
        store.save(project)
    }

    fun current(): HouseProject? = currentProject ?: store.get()

    fun updateProgress(value: Int) {
        progress = value.coerceIn(0, 100)
    }

    fun generationProgress(): Int = progress

    fun markGenerated(): Boolean {
        progress = 100
        return true
    }
}
