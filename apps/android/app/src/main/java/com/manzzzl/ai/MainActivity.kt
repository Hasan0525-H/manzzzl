package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ManzzzlApp()
        }
    }
}

fun ManzzzlApp() {
    // Initial application shell.
    // UI screens will be added in the next implementation stage.
}
