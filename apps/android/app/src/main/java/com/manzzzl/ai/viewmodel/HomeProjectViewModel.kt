package com.manzzzl.ai.viewmodel

import androidx.lifecycle.ViewModel
import com.manzzzl.ai.data.HomeProjectState
import com.manzzzl.ai.data.ProjectStep

class HomeProjectViewModel : ViewModel() {

    val state = HomeProjectState()

    fun moveTo(step: ProjectStep) {
        state.currentStep = step
    }
}
