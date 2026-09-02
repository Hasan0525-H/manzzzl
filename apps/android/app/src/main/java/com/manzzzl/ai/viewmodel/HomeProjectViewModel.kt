package com.manzzzl.ai.viewmodel

import androidx.lifecycle.ViewModel
import com.manzzzl.ai.data.HomeProjectState
import com.manzzzl.ai.data.ProjectStage

class HomeProjectViewModel : ViewModel() {

    val state = HomeProjectState()

    fun moveTo(stage: ProjectStage) {
        state.currentStage = stage
    }
}
