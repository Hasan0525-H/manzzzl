package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manzzzl.ai.data.ProjectStep
import com.manzzzl.ai.screens.CreateHomeProjectScreen
import com.manzzzl.ai.screens.HomeQuestionsScreen
import com.manzzzl.ai.screens.HomeScreen
import com.manzzzl.ai.screens.PlanUploadScreen
import com.manzzzl.ai.viewmodel.HomeProjectViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ManzzzlApp()
        }
    }
}

@Composable
fun ManzzzlApp() {
    MaterialTheme {
        val projectViewModel: HomeProjectViewModel = viewModel()

        when (projectViewModel.state.currentStep) {
            ProjectStep.HOME -> HomeScreen {
                projectViewModel.moveTo(ProjectStep.CREATE_PROJECT)
            }
            ProjectStep.CREATE_PROJECT -> CreateHomeProjectScreen {
                projectViewModel.moveTo(ProjectStep.QUESTIONS)
            }
            ProjectStep.QUESTIONS -> HomeQuestionsScreen {
                projectViewModel.moveTo(ProjectStep.UPLOAD_PLAN)
            }
            ProjectStep.UPLOAD_PLAN -> PlanUploadScreen()
            ProjectStep.ANALYSIS -> PlanUploadScreen()
        }
    }
}
