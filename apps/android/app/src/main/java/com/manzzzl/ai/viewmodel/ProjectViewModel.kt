package com.manzzzl.ai.viewmodel

import com.manzzzl.ai.data.LocalProjectStore
import com.manzzzl.ai.model.HouseProject
import com.manzzzl.ai.pipeline.ProjectPipeline

/**
 * Single controller for upload -> analysis -> generation flow.
 */
class ProjectViewModel {
    private val store = LocalProjectStore()
    private val pipeline = ProjectPipeline()

    private var currentProject: HouseProject? = null
    private var progress: Int = 0

    fun create(project: HouseProject) {
        currentProject = project.copy(analysisStatus = "DRAFT")
        store.save(currentProject!!)
    }

    fun setPlan(path: String) {
        updateProject {
            it.copy(floorPlanPath = path, analysisStatus = "UPLOADED")
        }
    }

    fun generateProject(): Boolean {
        val project = currentProject ?: store.get() ?: return false

        return try {
            updateProject { it.copy(analysisStatus = "ANALYZING") }
            updateProgress(30)

            val result = pipeline.generate(project)

            updateProject { it.copy(analysisStatus = "GENERATING_3D") }
            updateProgress(70)

            val generatedModelId = "geometry://${result.geometry.roomCount}rooms-${result.geometry.wallCount}walls"

            updateProject {
                it.copy(
                    modelPath = generatedModelId,
                    analysisStatus = "COMPLETED"
                )
            }

            updateProgress(100)
            true
        } catch (e: Exception) {
            fail()
            false
        }
    }

    fun current(): HouseProject? = currentProject ?: store.get()

    fun updateProgress(value: Int) {
        progress = value.coerceIn(0, 100)
    }

    fun generationProgress(): Int = progress

    fun fail() {
        updateProject { it.copy(analysisStatus = "FAILED") }
    }

    private fun updateProject(transform: (HouseProject) -> HouseProject) {
        val project = currentProject ?: store.get() ?: return
        currentProject = transform(project)
        store.save(currentProject!!)
    }
}
