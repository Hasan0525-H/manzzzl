package com.manzzzl.ai.data

import com.manzzzl.ai.model.HouseProject

class AppProjectState {
    private var project: HouseProject? = null

    fun update(value: HouseProject) {
        project = value
    }

    fun current(): HouseProject? = project
}
