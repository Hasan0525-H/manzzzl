package com.manzl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.manzl.app.ui.GeometryReviewHost
import com.manzl.app.ui.ManzlExperience

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            GeometryReviewHost {
                ManzlExperience()
            }
        }
    }
}
