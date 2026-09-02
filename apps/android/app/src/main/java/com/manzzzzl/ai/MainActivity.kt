package com.manzzzl.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.manzzzl.ai.design.DesignGenerationSession
import com.manzzzl.ai.design.DesignQuestionnaire
import com.manzzzl.ai.design.DesignSessionBuilder
import com.manzzzl.ai.design.SaudiCityProfile
import com.manzzzl.ai.design.SmartDesignQuestionEngine
import com.manzzzl.ai.threeD.model.ThreeDModel
import com.manzzzl.ai.ui.CreateProjectScreen
import com.manzzzl.ai.ui.FloorPlanUploadScreen
import com.manzzzl.ai.ui.ProcessingScreen
import com.manzzzl.ai.ui.SmartQuestionsScreen
import com.manzzzl.ai.ui.ThreeDViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ManzzzlApp() }
    }
}

@Composable
fun ManzzzlApp() {
    MaterialTheme {
        Surface {
            var step by remember { mutableStateOf("create") }
            var answers by remember { mutableStateOf(emptyMap<String, String>()) }
            var designSession by remember { mutableStateOf<DesignGenerationSession?>(null) }
            var generatedModel by remember { mutableStateOf<ThreeDModel?>(null) }

            val questions = SmartDesignQuestionEngine.missingQuestions(
                hasFloors = answers.containsKey("عدد الأدوار"),
                hasLandArea = answers.containsKey("مساحة الأرض"),
                hasStreetDirection = answers.containsKey("اتجاه الشارع"),
                hasFacadePreference = answers.containsKey("نوع الواجهة")
            )

            when (step) {
                "create" -> CreateProjectScreen { step = "upload" }
                "upload" -> FloorPlanUploadScreen { step = "processing" }
                "processing" -> ProcessingScreen { step = "questions" }
                "questions" -> SmartQuestionsScreen(
                    questions = questions,
                    answers = answers,
                    onAnswerChanged = { q, v -> answers = answers + (q to v) },
                    onComplete = {
                        val questionnaire = DesignQuestionnaire(
                            city = answers["المدينة"],
                            floors = answers["عدد الأدوار"]?.toIntOrNull(),
                            streetDirection = answers["اتجاه الشارع"],
                            facadePreference = answers["نوع الواجهة"]
                        )

                        generatedModel = ThreeDModel()
                        designSession = DesignSessionBuilder.build(
                            questionnaire,
                            generatedModel ?: ThreeDModel(),
                            SaudiCityProfile(questionnaire.city ?: "")
                        )

                        step = if (designSession != null) "viewer" else "questions"
                    }
                )
                "viewer" -> designSession?.let {
                    ThreeDViewerScreen(
                        model = generatedModel,
                        session = it
                    )
                }
            }
        }
    }
}
