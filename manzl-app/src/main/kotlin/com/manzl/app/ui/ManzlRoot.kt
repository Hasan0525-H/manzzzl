package com.manzl.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.manzl.app.analysis.ClassicalFloorPlanAnalyzer
import com.manzl.app.analysis.ProgressSink
import com.manzl.app.model.FloorPlan
import com.manzl.app.render.HouseWalkView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Sand = Color(0xFFB68B55)
private val Ink = Color(0xFF171717)
private val Canvas = Color(0xFFF7F5F1)
private val Success = Color(0xFF1F7A4D)

private enum class AppScreen { HOME, WALKTHROUGH }

@Composable
fun ManzlRoot() {
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = Ink,
                secondary = Sand,
                background = Canvas,
                surface = Color.White,
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                ManzlApp()
            }
        }
    }
}

@Composable
private fun ManzlApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember { ClassicalFloorPlanAnalyzer() }

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var floorPlan by remember { mutableStateOf<FloorPlan?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var progressMessage by remember { mutableStateOf("جاهز") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            errorMessage = null
            floorPlan = null
            selectedUri = uri
            selectedBitmap = runCatching {
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
            }.getOrElse {
                errorMessage = "تعذر قراءة الصورة: ${it.message ?: "خطأ غير معروف"}"
                null
            }
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            hasPlanImage = selectedBitmap != null,
            selectedUri = selectedUri,
            isProcessing = isProcessing,
            progress = progress,
            progressMessage = progressMessage,
            floorPlan = floorPlan,
            errorMessage = errorMessage,
            onPick = { picker.launch("image/*") },
            onExecute = {
                val bitmap = selectedBitmap ?: return@HomeScreen
                isProcessing = true
                progress = 0
                progressMessage = "بدء التحليل"
                errorMessage = null
                floorPlan = null
                scope.launch {
                    runCatching {
                        analyzer.analyze(
                            bitmap = bitmap,
                            progress = ProgressSink { update ->
                                scope.launch {
                                    progress = update.percent
                                    progressMessage = update.messageArabic
                                }
                            },
                        )
                    }.onSuccess { plan ->
                        floorPlan = plan
                        progress = 100
                        progressMessage = "جاهز للجولة"
                    }.onFailure { error ->
                        errorMessage = error.message ?: "فشل تحليل المخطط"
                    }
                    isProcessing = false
                }
            },
            onStartTour = { screen = AppScreen.WALKTHROUGH },
            onReset = {
                selectedBitmap = null
                selectedUri = null
                floorPlan = null
                progress = 0
                progressMessage = "جاهز"
                errorMessage = null
            },
        )

        AppScreen.WALKTHROUGH -> {
            val plan = floorPlan
            if (plan == null) {
                screen = AppScreen.HOME
            } else {
                WalkthroughScreen(plan = plan, onExit = { screen = AppScreen.HOME })
            }
        }
    }
}

@Composable
private fun HomeScreen(
    hasPlanImage: Boolean,
    selectedUri: Uri?,
    isProcessing: Boolean,
    progress: Int,
    progressMessage: String,
    floorPlan: FloorPlan?,
    errorMessage: String?,
    onPick: () -> Unit,
    onExecute: () -> Unit,
    onStartTour: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("منزل", fontSize = 34.sp, color = Ink, style = MaterialTheme.typography.headlineLarge)
                Text(
                    "حوّل مخططك إلى مساحة تمشي داخلها",
                    color = Color(0xFF64615D),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Surface(shape = CircleShape, color = Color(0xFFE8F5ED)) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFEFF7F1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Success)
                Column {
                    Text("يعمل على الجهاز", color = Success, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "لا يحتاج حساباً أو API أو اتصالاً بالإنترنت أثناء الاستخدام.",
                        color = Color(0xFF496253),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(shape = CircleShape, color = Color(0xFFF2E8DB)) {
                    Icon(
                        Icons.Rounded.UploadFile,
                        contentDescription = null,
                        tint = Sand,
                        modifier = Modifier.padding(16.dp).size(34.dp),
                    )
                }
                Text(
                    if (hasPlanImage) "تم اختيار المخطط" else "ارفع صورة المخطط الهندسي",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
                Text(
                    if (hasPlanImage) {
                        selectedUri?.lastPathSegment?.takeLast(42) ?: "الصورة جاهزة للتحليل"
                    } else {
                        "يفضل مخطط واضح بخطوط مستقيمة وأبعاد ظاهرة. يدعم الصور من الجوال مباشرة."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF77736D),
                )
                Button(
                    onClick = onPick,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0E9DF), contentColor = Ink),
                ) {
                    Text(if (hasPlanImage) "تغيير المخطط" else "اختيار المخطط")
                }
                Button(
                    onClick = onExecute,
                    enabled = hasPlanImage && !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Rounded.ViewInAr, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("تنفيذ", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (isProcessing || progress > 0) {
            ProcessingCard(progress = progress, message = progressMessage)
        }

        errorMessage?.let { message ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFE9E7)) {
                Text(
                    text = message,
                    color = Color(0xFF9C2B25),
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }
        }

        floorPlan?.let { plan ->
            ResultCard(plan = plan, onStartTour = onStartTour, onReset = onReset)
        }
    }
}

@Composable
private fun ProcessingCard(progress: Int, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$progress%", color = Color.White, fontSize = 30.sp, modifier = Modifier.weight(1f))
                Text(message, color = Color(0xFFD8D5CF), style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = Sand,
                trackColor = Color(0xFF373737),
            )
        }
    }
}

@Composable
private fun ResultCard(plan: FloorPlan, onStartTour: () -> Unit, onReset: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF6EE)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Success)
                Spacer(Modifier.size(8.dp))
                Text("تم بناء النموذج الأولي", color = Success, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "${plan.walls.size} جدار مكتشف • دقة التحليل ${(plan.analysisConfidence * 100).toInt()}% • ${"%.1f".format(plan.widthMeters)} × ${"%.1f".format(plan.depthMeters)} م",
                color = Color(0xFF3C5847),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onStartTour,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Success),
            ) {
                Icon(Icons.Rounded.ViewInAr, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("ابدأ الجولة")
            }
            IconButton(onClick = onReset, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "إعادة البدء")
            }
        }
    }
}

@Composable
private fun WalkthroughScreen(plan: FloorPlan, onExit: () -> Unit) {
    var walkView by remember { mutableStateOf<HouseWalkView?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                HouseWalkView(context).also {
                    walkView = it
                    it.setFloorPlan(plan)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = onExit) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "رجوع")
            }
            Spacer(Modifier.weight(1f))
            Surface(color = Color(0xCC111111), shape = RoundedCornerShape(14.dp)) {
                Text(
                    "اسحب الشاشة للنظر حولك",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FilledIconButton(onClick = { walkView?.moveForward(0.32f) }) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "أمام")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = { walkView?.strafe(-0.32f) }) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = "يسار")
                }
                FilledIconButton(onClick = { walkView?.moveForward(-0.32f) }) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "خلف")
                }
                FilledIconButton(onClick = { walkView?.strafe(0.32f) }) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = "يمين", modifier = Modifier)
                }
            }
        }

        Button(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCCAA2E2E)),
        ) {
            Text("إنهاء الجولة")
        }
    }
}
