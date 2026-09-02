package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.manzzzl.ai.screens.HomeScreen

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
        HomeScreen(
            onCreateProject = {
                // Project setup flow will be connected here
            }
        )
    }
}
