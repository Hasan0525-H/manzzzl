package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.manzzzl.ai.screens.CreateHomeProjectScreen
import com.manzzzl.ai.screens.HomeQuestionsScreen
import com.manzzzl.ai.screens.HomeScreen
import com.manzzzl.ai.screens.PlanUploadScreen

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
        val screen = remember { mutableStateOf("home") }

        when (screen.value) {
            "home" -> HomeScreen {
                screen.value = "create"
            }
            "create" -> CreateHomeProjectScreen {
                screen.value = "questions"
            }
            "questions" -> HomeQuestionsScreen {
                screen.value = "upload"
            }
            else -> PlanUploadScreen()
        }
    }
}
