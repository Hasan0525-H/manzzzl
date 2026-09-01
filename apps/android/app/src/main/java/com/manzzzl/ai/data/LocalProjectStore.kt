package com.manzzzl.ai.data

import com.manzzzl.ai.model.HouseProject

class LocalProjectStore {
    private var currentProject: HouseProject? = null

    fun save(project: HouseProject) {
        currentProject = project
    }

    fun get(): HouseProject? = currentProject
}
