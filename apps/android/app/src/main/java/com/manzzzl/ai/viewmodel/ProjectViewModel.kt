package com.manzzzl.ai.viewmodel

import com.manzzzl.ai.data.LocalProjectStore
import com.manzzzl.ai.model.HouseProject

/**
 * Single controller for upload -> analysis -> generation flow.
 */
class ProjectViewModel {
    private val store = LocalProjectStore()

    private var currentProject: HouseProject? = null
    private var progress: Int = 0

    fun create(project: HouseProject) {
        currentProject = project.copy(analysisStatus = "DRAFT")
        store.save(currentProject!!)
    }

    fun setPlan(path: String) {
        updateProject {
            it.copy(
                floorPlanPath = path,
                analysisStatus = "UPLOADED"
            )
        }
    }

    fun startAnalysis() {
        updateProject {
            it.copy(analysisStatus = "ANALYZING")
        }
    }

    fun startGeneration() {
        updateProject {
            it.copy(analysisStatus = "GENERATING_3D")
        }
    }

    fun setModel(path: String) {
        updateProject {
            it.copy(
                modelPath = path,
                analysisStatus = "COMPLETED"
            )
        }
        progress = 100
    }

    fun fail() {
        updateProject {
            it.copy(analysisStatus = "FAILED")
        }
    }

    fun updateProgress(value: Int) {
        progress = value.coerceIn(0, 100)
    }

    fun generationProgress(): Int = progress

    fun current(): HouseProject? = currentProject ?: store.get()

    private fun updateProject(transform: (HouseProject) -> HouseProject) {
        val project = currentProject ?: return
        currentProject = transform(project)
        store.save(currentProject!!)
    }
}
