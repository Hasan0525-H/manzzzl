package com.manzl.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.manzl.app.analysis.HybridFloorPlanAnalyzer
import com.manzl.app.analysis.ProgressSink
import com.manzl.app.model.FloorPlan
import com.manzl.app.recording.TourRecordingService
import com.manzl.app.recording.TourRecordingState
import com.manzl.app.render.HouseWalkView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ManzlInk = Color(0xFF171717)
private val ManzlSand = Color(0xFFB68B55)
private val ManzlCanvas = Color(0xFFF7F5F1)
private val ManzlGreen = Color(0xFF1F7A4D)
private val ManzlRecord = Color(0xFFD63C32)

private enum class ManzlScreen { CREATE, WALK }

@Composable
fun ManzlExperience() {
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = ManzlInk,
                secondary = ManzlSand,
                background = ManzlCanvas,
                surface = Color.White,
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = ManzlCanvas) {
                ManzlExperienceContent()
            }
        }
    }
}

@Composable
private fun ManzlExperienceContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember { HybridFloorPlanAnalyzer() }
    val recordingState by TourRecordingState.state.collectAsState()

    var screen by remember { mutableStateOf(ManzlScreen.CREATE) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var plan by remember { mutableStateOf<FloorPlan?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf("جاهز") }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }

    val recordingPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val permissionData = result.data
        if (result.resultCode != Activity.RESULT_OK || permissionData == null) {
            error = "تم إلغاء إذن تسجيل الجولة. لم يبدأ التجول حتى لا تفقد الفيديو المطلوب."
            return@rememberLauncherForActivityResult
        }

        TourRecordingState.clearSavedUri()
        screen = ManzlScreen.WALK
        scope.launch {
            // Let the first walkthrough frame reach the screen before the virtual display starts.
            delay(260)
            val metrics = context.resources.displayMetrics
            TourRecordingService.start(
                context = context,
                resultCode = result.resultCode,
                resultData = permissionData,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            plan = null
            progress = 0
            sourceUri = uri
            bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
            }.getOrElse {
                error = "تعذر قراءة المخطط: ${it.message ?: "خطأ غير معروف"}"
                null
            }
        }
    }

    when (screen) {
        ManzlScreen.CREATE -> CreateHouseScreen(
            bitmap = bitmap,
            sourceUri = sourceUri,
            plan = plan,
            progress = progress,
            stage = stage,
            processing = processing,
            error = error,
            lastSavedVideo = recordingState.lastSavedUri,
            onPick = { picker.launch("image/*") },
            onExecute = {
                val input = bitmap ?: return@CreateHouseScreen
                processing = true
                error = null
                plan = null
                progress = 0
                stage = "بدء التحليل الهندسي"
                scope.launch {
                    runCatching {
                        analyzer.analyze(
                            bitmap = input,
                            progress = ProgressSink { update ->
                                scope.launch {
                                    progress = update.percent
                                    stage = update.messageArabic
                                }
                            },
                        )
                    }.onSuccess { generated ->
                        plan = generated
                        progress = 100
                        stage = "جاهز للجولة"
                    }.onFailure {
                        error = it.message ?: "تعذر تحليل المخطط"
                    }
                    processing = false
                }
            },
            onWalk = {
                error = null
                recordingPermission.launch(projectionManager.createScreenCaptureIntent())
            },
            onReset = {
                bitmap = null
                sourceUri = null
                plan = null
                progress = 0
                stage = "جاهز"
                error = null
                TourRecordingState.clearSavedUri()
            },
        )

        ManzlScreen.WALK -> {
            val generated = plan
            if (generated == null) {
                screen = ManzlScreen.CREATE
            } else {
                NaturalWalkthrough(
                    plan = generated,
                    isRecording = recordingState.isRecording,
                    onExit = {
                        TourRecordingService.stop(context)
                        screen = ManzlScreen.CREATE
                    },
                )
            }
        }
    }
}

@Composable
private fun CreateHouseScreen(
    bitmap: Bitmap?,
    sourceUri: Uri?,
    plan: FloorPlan?,
    progress: Int,
    stage: String,
    processing: Boolean,
    error: String?,
    lastSavedVideo: String?,
    onPick: () -> Unit,
    onExecute: () -> Unit,
    onWalk: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("منزل", fontSize = 34.sp, color = ManzlInk, style = MaterialTheme.typography.headlineLarge)
                Text("من مخطط 2D إلى منزل تمشي داخله", color = Color(0xFF65615B))
            }
            Surface(shape = CircleShape, color = Color(0xFFE9F5ED)) {
                Icon(
                    Icons.Rounded.Home,
                    contentDescription = null,
                    tint = ManzlGreen,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }
        }

        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFEFF7F1)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = ManzlGreen)
                Text(
                    "البناء والذكاء المحلي والجولة والتسجيل تعمل على الجهاز بدون API أثناء الاستخدام.",
                    color = Color(0xFF405C49),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (bitmap == null) {
                    Surface(shape = CircleShape, color = Color(0xFFF3EADF)) {
                        Icon(
                            Icons.Rounded.UploadFile,
                            contentDescription = null,
                            tint = ManzlSand,
                            modifier = Modifier.padding(16.dp).size(34.dp),
                        )
                    }
                    Text("ارفع المخطط الهندسي", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "يفضل صورة واضحة، مستقيمة، وتظهر فيها الجدران والأبعاد.",
                        color = Color(0xFF77736D),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "المخطط المختار",
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        sourceUri?.lastPathSegment?.takeLast(48) ?: "المخطط جاهز",
                        color = Color(0xFF6C6862),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    onClick = onPick,
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF0E9DF),
                        contentColor = ManzlInk,
                    ),
                ) {
                    Text(if (bitmap == null) "اختيار المخطط" else "تغيير المخطط")
                }

                Button(
                    onClick = onExecute,
                    enabled = bitmap != null && !processing,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ManzlInk),
                ) {
                    if (processing) {
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

        if (processing || progress > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = ManzlInk),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$progress%", color = Color.White, fontSize = 28.sp, modifier = Modifier.weight(1f))
                        Text(stage, color = Color(0xFFD9D5CE), style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = ManzlSand,
                        trackColor = Color(0xFF383838),
                    )
                }
            }
        }

        error?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFE9E7)) {
                Text(it, color = Color(0xFF962D27), modifier = Modifier.fillMaxWidth().padding(13.dp))
            }
        }

        lastSavedVideo?.let {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFE8F2FF)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.VideoFile, contentDescription = null, tint = Color(0xFF245A92))
                    Text(
                        "تم حفظ الجولة MP4 في مجلد Movies/Manzl على الجوال.",
                        color = Color(0xFF245A92),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        plan?.let { generated ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF6EE)),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = ManzlGreen)
                        Spacer(Modifier.size(7.dp))
                        Text("النموذج جاهز", color = ManzlGreen, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "${generated.walls.size} جدار • ${generated.doors.size} باب • ${generated.windows.size} نافذة • " +
                            "${"%.1f".format(generated.widthMeters)} × ${"%.1f".format(generated.depthMeters)} م • " +
                            "ثقة ${(generated.analysisConfidence * 100).toInt()}%",
                        color = Color(0xFF405847),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (generated.scaleSource == "bundled_ocr") {
                        Text(
                            "المقياس قُرئ محلياً من الأبعاد المطبوعة • ثقة ${(generated.scaleConfidence * 100).toInt()}%",
                            color = Color(0xFF56715E),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onWalk,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ManzlGreen),
                    ) {
                        Icon(Icons.Rounded.ViewInAr, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("ابدأ الجولة والتسجيل")
                    }
                    IconButton(onClick = onReset, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "إعادة البدء")
                    }
                }
            }
        }
    }
}

@Composable
private fun NaturalWalkthrough(
    plan: FloorPlan,
    isRecording: Boolean,
    onExit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> HouseWalkView(context).apply { setFloorPlan(plan) } },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = onExit) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "إنهاء الجولة")
            }
            Spacer(Modifier.weight(1f))
            Surface(color = Color(0xD1111111), shape = RoundedCornerShape(14.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (isRecording) {
                        Icon(
                            Icons.Rounded.FiberManualRecord,
                            contentDescription = null,
                            tint = ManzlRecord,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        if (isRecording) "REC • يسار للمشي • يمين للنظر" else "جاري بدء التسجيل…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(18.dp),
            color = Color(0x7A111111),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                "اسحب هنا بأي اتجاه\nللمشي الطبيعي",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xD09E2D26)),
        ) {
            Text("إنهاء وحفظ MP4")
        }
    }
}
