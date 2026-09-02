package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
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
        androidx.compose.runtime.key(projectViewModel.state.currentStage) {
            when (projectViewModel.state.currentStage.name) {
                "HOME" -> HomeScreen {
                    projectViewModel.moveTo(com.manzzzl.ai.data.ProjectStage.CREATE_PROJECT)
                }
                "CREATE_PROJECT" -> CreateHomeProjectScreen {
                    projectViewModel.moveTo(com.manzzzl.ai.data.ProjectStage.QUESTIONS)
                }
                "QUESTIONS" -> HomeQuestionsScreen {
                    projectViewModel.moveTo(com.manzzzl.ai.data.ProjectStage.UPLOAD_PLAN)
                }
                else -> PlanUploadScreen()
            }
        }
    }
}
