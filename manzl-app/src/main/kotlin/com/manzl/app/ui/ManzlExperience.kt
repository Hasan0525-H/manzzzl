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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.manzl.app.analysis.BuildingPlanAssembler
import com.manzl.app.analysis.HybridFloorPlanAnalyzer
import com.manzl.app.analysis.ProgressSink
import com.manzl.app.model.BuildingPlan
import com.manzl.app.model.DoorHingeSide
import com.manzl.app.model.DoorSwingSide
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.FloorRegistrationStatus
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
private val ManzlAmberSurface = Color(0xFFFFF4DC)
private val ManzlAmberInk = Color(0xFF7A5312)

private const val MAX_FLOORS = 6
private const val TRUSTED_DOOR_SWING_CONFIDENCE = 0.66f
private enum class ManzlScreen { CREATE, WALK }
private enum class PickerIntent { REPLACE_ALL, APPEND_FLOOR }

private data class FloorDraft(
    val uri: Uri,
    val bitmap: Bitmap,
)

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
    var drafts by remember { mutableStateOf<List<FloorDraft>>(emptyList()) }
    var building by remember { mutableStateOf<BuildingPlan?>(null) }
    var pickerIntent by remember { mutableStateOf(PickerIntent.REPLACE_ALL) }
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
            building = null
            progress = 0
            val decoded = runCatching {
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
            }.getOrElse {
                error = "تعذر قراءة المخطط: ${it.message ?: "خطأ غير معروف"}"
                null
            } ?: return@launch

            val draft = FloorDraft(uri = uri, bitmap = decoded)
            drafts = when (pickerIntent) {
                PickerIntent.REPLACE_ALL -> listOf(draft)
                PickerIntent.APPEND_FLOOR -> {
                    if (drafts.size >= MAX_FLOORS) {
                        error = "الحد الحالي $MAX_FLOORS أدوار في مشروع واحد."
                        drafts
                    } else {
                        drafts + draft
                    }
                }
            }
        }
    }

    when (screen) {
        ManzlScreen.CREATE -> CreateHouseScreen(
            drafts = drafts,
            building = building,
            progress = progress,
            stage = stage,
            processing = processing,
            error = error,
            lastSavedVideo = recordingState.lastSavedUri,
            onPickFirst = {
                pickerIntent = PickerIntent.REPLACE_ALL
                picker.launch("image/*")
            },
            onAddFloor = {
                pickerIntent = PickerIntent.APPEND_FLOOR
                picker.launch("image/*")
            },
            onExecute = {
                val inputs = drafts
                if (inputs.isEmpty()) return@CreateHouseScreen
                processing = true
                error = null
                building = null
                progress = 0
                stage = "بدء التحليل الهندسي"

                scope.launch {
                    val analyzed = ArrayList<FloorPlan>(inputs.size)
                    val result = runCatching {
                        inputs.forEachIndexed { index, input ->
                            val generated = analyzer.analyze(
                                bitmap = input.bitmap,
                                progress = ProgressSink { update ->
                                    scope.launch {
                                        val combined = ((index * 100) + update.percent.coerceIn(0, 100)) / inputs.size
                                        progress = combined.coerceIn(0, 99)
                                        stage = "${floorNameArabic(index)} • ${update.messageArabic}"
                                    }
                                },
                            )
                            analyzed += generated
                        }
                        BuildingPlanAssembler.assemble(analyzed)
                    }

                    result.onSuccess { generatedBuilding ->
                        building = generatedBuilding
                        progress = 100
                        val needsRegistrationReview = generatedBuilding.registrationDiagnostics.any {
                            it.status != FloorRegistrationStatus.ALIGNED
                        }
                        stage = when {
                            generatedBuilding.levels.size == 1 -> "جاهز للجولة"
                            needsRegistrationReview -> "تم البناء • بعض محاذاة الأدوار تحتاج مراجعة"
                            else -> "تم بناء ${generatedBuilding.levels.size} أدوار وربط السلالم الموثوقة"
                        }
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
                drafts = emptyList()
                building = null
                progress = 0
                stage = "جاهز"
                error = null
                TourRecordingState.clearSavedUri()
            },
        )

        ManzlScreen.WALK -> {
            val generated = building
            if (generated == null) {
                screen = ManzlScreen.CREATE
            } else {
                NaturalWalkthrough(
                    building = generated,
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
    drafts: List<FloorDraft>,
    building: BuildingPlan?,
    progress: Int,
    stage: String,
    processing: Boolean,
    error: String?,
    lastSavedVideo: String?,
    onPickFirst: () -> Unit,
    onAddFloor: () -> Unit,
    onExecute: () -> Unit,
    onWalk: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
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
                    "التحليل والبناء والجولة والتسجيل تعمل على الجهاز بدون API أثناء الاستخدام.",
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
                if (drafts.isEmpty()) {
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
                        "لمنزل متعدد الأدوار: ارفع الأرضي أولاً ثم أضف الأدوار بالترتيب إلى الأعلى.",
                        color = Color(0xFF77736D),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    drafts.forEachIndexed { index, draft ->
                        FloorDraftCard(index = index, draft = draft)
                    }
                }

                Button(
                    onClick = onPickFirst,
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF0E9DF),
                        contentColor = ManzlInk,
                    ),
                ) {
                    Text(if (drafts.isEmpty()) "اختيار المخطط" else "استبدال المشروع بمخطط جديد")
                }

                if (drafts.isNotEmpty() && drafts.size < MAX_FLOORS) {
                    Button(
                        onClick = onAddFloor,
                        enabled = !processing,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE9F5ED),
                            contentColor = ManzlGreen,
                        ),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("إضافة مخطط الدور التالي")
                    }
                }

                Button(
                    onClick = onExecute,
                    enabled = drafts.isNotEmpty() && !processing,
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

        building?.let { generated ->
            val wallCount = generated.levels.sumOf { it.plan.walls.size }
            val doorCount = generated.levels.sumOf { it.plan.doors.size }
            val windowCount = generated.levels.sumOf { it.plan.windows.size }
            val stairCount = generated.levels.sumOf { it.plan.stairs.size }
            val trustedSwingDoorCount = generated.levels.sumOf { level ->
                level.plan.doors.count { door ->
                    door.hingeSide != DoorHingeSide.UNKNOWN &&
                        door.swingSide != DoorSwingSide.UNKNOWN &&
                        door.swingConfidence >= TRUSTED_DOOR_SWING_CONFIDENCE
                }
            }
            val averageConfidence = generated.levels
                .map { it.plan.analysisConfidence }
                .average()
                .takeIf { it.isFinite() }
                ?: 0.0
            val reviewDiagnostics = generated.registrationDiagnostics.filter {
                it.status == FloorRegistrationStatus.REVIEW_REQUIRED
            }
            val unresolvedDiagnostics = generated.registrationDiagnostics.filter {
                it.status == FloorRegistrationStatus.UNRESOLVED
            }

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
                        "${generated.levels.size} دور • $wallCount جدار • $doorCount باب • $windowCount نافذة • " +
                            "$stairCount سلم • ثقة ${(averageConfidence * 100).toInt()}%",
                        color = Color(0xFF405847),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (doorCount > 0) {
                        Text(
                            "اتجاه فتح مؤكد من رمز المخطط: $trustedSwingDoorCount من $doorCount باب. الأبواب غير الواضحة تبقى فتحات آمنة بدون تخمين المفصلة.",
                            color = Color(0xFF56715E),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (generated.levels.size > 1) {
                        Text(
                            "روابط السلالم الموثوقة بين الأدوار: ${generated.stairLinks.size}. لن يتم اختراع انتقال بين دورين إذا لم تتطابق هندسة السلم.",
                            color = Color(0xFF56715E),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (reviewDiagnostics.isNotEmpty() || unresolvedDiagnostics.isNotEmpty()) {
                        RegistrationWarning(
                            building = generated,
                            reviewCount = reviewDiagnostics.size,
                            unresolvedCount = unresolvedDiagnostics.size,
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

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RegistrationWarning(
    building: BuildingPlan,
    reviewCount: Int,
    unresolvedCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ManzlAmberSurface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "محاذاة الأدوار تحتاج انتباه",
                color = ManzlAmberInk,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "$reviewCount قابل للمراجعة • $unresolvedCount غير محسوم. لم يتم تحريك أي مخطط تلقائياً.",
                color = ManzlAmberInk,
                style = MaterialTheme.typography.bodySmall,
            )
            building.registrationDiagnostics
                .filter { it.status == FloorRegistrationStatus.REVIEW_REQUIRED }
                .take(3)
                .forEach { diagnostic ->
                    val lowerIndex = building.levels.indexOfFirst { it.id == diagnostic.lowerLevelId }
                    val upperIndex = building.levels.indexOfFirst { it.id == diagnostic.upperLevelId }
                    if (lowerIndex >= 0 && upperIndex >= 0) {
                        Text(
                            "${floorNameArabic(lowerIndex)} ↔ ${floorNameArabic(upperIndex)}: " +
                                "اقتراح فقط ΔX ${"%.2f".format(diagnostic.suggestedOffsetXMeters)}م، " +
                                "ΔZ ${"%.2f".format(diagnostic.suggestedOffsetZMeters)}م • " +
                                "ثقة ${(diagnostic.confidence * 100).toInt()}%",
                            color = ManzlAmberInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
        }
    }
}

@Composable
private fun FloorDraftCard(index: Int, draft: FloorDraft) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF7F3ED),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    floorNameArabic(index),
                    color = ManzlInk,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "#${index + 1}",
                    color = ManzlSand,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Image(
                bitmap = draft.bitmap.asImageBitmap(),
                contentDescription = "مخطط ${floorNameArabic(index)}",
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                draft.uri.lastPathSegment?.takeLast(56) ?: "مخطط الدور",
                color = Color(0xFF6C6862),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NaturalWalkthrough(
    building: BuildingPlan,
    isRecording: Boolean,
    onExit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> HouseWalkView(context).apply { setBuildingPlan(building) } },
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

private fun floorNameArabic(index: Int): String = when (index) {
    0 -> "الدور الأرضي"
    1 -> "الدور الأول"
    2 -> "الدور الثاني"
    3 -> "الدور الثالث"
    4 -> "الدور الرابع"
    5 -> "الدور الخامس"
    else -> "الدور ${index + 1}"
}
