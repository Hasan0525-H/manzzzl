package com.manzl.app.analysis

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

/**
 * Bundled, fully on-device semantic room labelling.
 *
 * ML Kit supplies OCR evidence only. Geometry remains authoritative: a label is accepted only when
 * the text centre falls inside a room polygon that was already inferred from measured walls.
 * The shared PlanRasterTransform removes any coordinate drift caused by white page margins/crops.
 */
internal class RoomLabelEvidenceProvider : SemanticEvidenceProvider {

    override suspend fun analyze(bitmap: Bitmap, structuralPlan: FloorPlan): List<SemanticEvidence> {
        if (structuralPlan.rooms.isEmpty() || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val text = recognize(recognizer, InputImage.fromBitmap(bitmap, 0))
            collectEvidence(text, bitmap, structuralPlan)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
    ): Text = suspendCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    private fun collectEvidence(
        text: Text,
        bitmap: Bitmap,
        plan: FloorPlan,
    ): List<SemanticEvidence> {
        val transform = PlanRasterTransform.forImage(plan, bitmap.width, bitmap.height)
        val bestByRoom = LinkedHashMap<String, SemanticEvidence>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val match = RoomLabelSemantics.match(line.text) ?: continue
                val point = transform.imageToPlan(box.exactCenterX(), box.exactCenterY())
                val room = plan.rooms.firstOrNull { pointInsidePolygon(point, it.polygon) } ?: continue
                val confidence = (
                    match.confidence * 0.82f + room.confidence.coerceIn(0f, 1f) * 0.18f
                    ).coerceIn(0f, 0.96f)
                val candidate = SemanticEvidence(
                    kind = SemanticKind.ROOM,
                    center = room.centroid(),
                    polygon = room.polygon,
                    label = match.labelArabic,
                    confidence = confidence,
                    source = EvidenceSource.LOCAL_AI,
                )
                val previous = bestByRoom[room.id]
                if (previous == null || candidate.confidence > previous.confidence) {
                    bestByRoom[room.id] = candidate
                }
            }
        }
        return bestByRoom.values.toList()
    }

    private fun pointInsidePolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.z > point.z) != (previous.z > point.z)
            if (crosses) {
                val denominator = previous.z - current.z
                val safeDenominator = if (kotlin.math.abs(denominator) < 0.000001f) 0.000001f else denominator
                val boundaryX = (previous.x - current.x) * (point.z - current.z) / safeDenominator + current.x
                if (point.x < boundaryX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun RoomRegion.centroid(): Vec2 {
        if (polygon.isEmpty()) return Vec2(0f, 0f)
        var x = 0f
        var z = 0f
        polygon.forEach { point ->
            x += point.x
            z += point.z
        }
        return Vec2(x / max(1, polygon.size), z / max(1, polygon.size))
    }
}

internal data class RoomLabelMatch(
    val labelArabic: String,
    val confidence: Float,
)

/** Pure semantic mapping kept separate from OCR so it is unit-testable on the JVM. */
internal object RoomLabelSemantics {

    fun match(source: String): RoomLabelMatch? {
        val normalized = source
            .uppercase()
            .replace(Regex("[^A-Z0-9\\u0600-\\u06FF ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length < 2) return null

        return RULES.firstNotNullOfOrNull { rule ->
            if (rule.tokens.any { token -> containsToken(normalized, token) }) {
                RoomLabelMatch(rule.labelArabic, rule.confidence)
            } else {
                null
            }
        }
    }

    private fun containsToken(text: String, token: String): Boolean {
        if (token.any { it.code > 127 }) return text.contains(token)
        val padded = " $text "
        return padded.contains(" $token ") || text.contains(token)
    }

    private data class Rule(
        val labelArabic: String,
        val confidence: Float,
        val tokens: List<String>,
    )

    private val RULES = listOf(
        Rule("غرفة نوم رئيسية", 0.94f, listOf("MASTER BEDROOM", "MASTER BED", "MASTER BR", "غرفة نوم رئيسية")),
        Rule("مجلس", 0.94f, listOf("MAJLIS", "MAJLES", "مجلس")),
        Rule("مطبخ", 0.94f, listOf("KITCHEN", "مطبخ")),
        Rule("حمام", 0.93f, listOf("BATHROOM", "BATH", "TOILET", "W C", "WC", "حمام", "دورة مياه")),
        Rule("فناء", 0.93f, listOf("COURTYARD", "PATIO", "فناء")),
        Rule("حوش", 0.92f, listOf("YARD", "حوش")),
        Rule("منور", 0.92f, listOf("LIGHTWELL", "LIGHT WELL", "منور")),
        Rule("حديقة", 0.91f, listOf("GARDEN", "LANDSCAPE", "حديقة")),
        Rule("مرآب", 0.91f, listOf("GARAGE", "CAR PARK", "CAR PARKING", "PARKING", "كراج", "مرآب", "موقف سيارة", "مواقف")),
        Rule("مصلى", 0.91f, listOf("PRAYER ROOM", "PRAYER", "مصلى", "غرفة صلاة")),
        Rule("غرفة نوم", 0.92f, listOf("BEDROOM", "BED ROOM", "BED", "BR", "غرفة نوم")),
        Rule("صالة عائلية", 0.91f, listOf("FAMILY LIVING", "FAMILY ROOM", "FAMILY", "صالة عائلية")),
        Rule("صالة", 0.90f, listOf("LIVING ROOM", "LIVING", "LOUNGE", "صالة")),
        Rule("غرفة طعام", 0.90f, listOf("DINING ROOM", "DINING", "غرفة طعام")),
        Rule("غرفة ضيوف", 0.89f, listOf("GUEST ROOM", "GUEST", "غرفة ضيوف")),
        Rule("غرفة خادمة", 0.89f, listOf("MAID ROOM", "MAID", "غرفة خادمة")),
        Rule("غرفة سائق", 0.89f, listOf("DRIVER ROOM", "DRIVER", "غرفة سائق")),
        Rule("مغاسل", 0.89f, listOf("WASH BASIN", "WASHBASIN", "WASH AREA", "مغاسل")),
        Rule("غسيل", 0.88f, listOf("LAUNDRY", "WASH", "غسيل")),
        Rule("مخزن", 0.88f, listOf("STORE ROOM", "STORAGE", "STORE", "PANTRY", "مخزن", "مستودع")),
        Rule("مكتب", 0.88f, listOf("OFFICE", "STUDY", "مكتب")),
        Rule("غرفة ملابس", 0.88f, listOf("DRESSING", "WARDROBE", "CLOSET", "غرفة ملابس")),
        Rule("مصعد", 0.88f, listOf("ELEVATOR", "LIFT", "مصعد")),
        Rule("درج", 0.88f, listOf("STAIRCASE", "STAIRS", "STAIR", "درج", "سلم")),
        Rule("مدخل", 0.86f, listOf("ENTRANCE", "ENTRY", "FOYER", "مدخل")),
        Rule("ممر", 0.84f, listOf("CORRIDOR", "HALLWAY", "PASSAGE", "ممر")),
        Rule("بهو", 0.82f, listOf("LOBBY", "HALL", "بهو")),
        Rule("سطح", 0.82f, listOf("ROOF TERRACE", "TERRACE", "ROOF", "سطح")),
    )
}
