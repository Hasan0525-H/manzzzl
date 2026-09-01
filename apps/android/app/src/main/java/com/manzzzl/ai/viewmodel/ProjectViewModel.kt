package com.manzzzl.ai.viewmodel

import com.manzzzl.ai.data.LocalProjectStore
import com.manzzzl.ai.model.HouseProject

class ProjectViewModel {
    private val store = LocalProjectStore()

    fun create(project: HouseProject) {
        store.save(project)
    }

    fun current(): HouseProject? = store.get()
}
