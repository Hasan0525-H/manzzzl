package com.manzzzl.ai.data

/**
 * Initial repository layer for house projects.
 * Future implementation will connect local storage and backend API.
 */
class ProjectRepository {
    fun createProject(name: String, city: String, floors: Int): Boolean {
        return name.isNotBlank() && city.isNotBlank() && floors in 1..2
    }
}
