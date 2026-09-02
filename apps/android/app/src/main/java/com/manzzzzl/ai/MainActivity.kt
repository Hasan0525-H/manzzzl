package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.manzzzl.ai.ui.CreateProjectScreen
import com.manzzzl.ai.ui.FloorPlanUploadScreen

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
        Surface {
            var step by remember { mutableStateOf("create") }

            when (step) {
                "create" -> CreateProjectScreen {
                    step = "upload"
                }
                "upload" -> FloorPlanUploadScreen {
                    step = "analysis"
                }
                else -> androidx.compose.material3.Text("جاري تحليل المخطط")
            }
        }
    }
}
