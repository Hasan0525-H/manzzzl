package com.manzl.app.analysis

import com.manzl.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conservative evidence ensemble used before GeometryEvidenceFusion.
 *
 * Independent detectors are allowed to strengthen the same observation only when their centres,
 * sizes and axes agree within tight tolerances. Conflicting observations remain separate and are
 * left for the geometry guard to accept/reject. Explicit user corrections are never averaged away.
 *
 * Independence is tracked by [SemanticEvidence.observerId], not merely by the broad evidence family.
 * This matters for the Ultra reconstruction path: two different CV stair readers should be able to
 * corroborate one another, while repeated proposals from one detector must never masquerade as two
 * votes. Legacy evidence without an observer id retains the previous source-family de-duplication.
 */
internal object SemanticEvidenceConsensus {

    fun combine(input: List<SemanticEvidence>): List<SemanticEvidence> {
        if (input.size < 2) return input

        val rooms = input.filter { it.kind == SemanticKind.ROOM }
        val geometric = input.filter { it.kind != SemanticKind.ROOM }
        val result = ArrayList<SemanticEvidence>(input.size)

        for (kind in listOf(SemanticKind.DOOR, SemanticKind.WINDOW, SemanticKind.STAIR)) {
            val clusters = ArrayList<Cluster>()
            geometric
                .filter { it.kind == kind }
                .sortedByDescending { it.confidence }
                .forEach { evidence ->
                    val cluster = clusters.firstOrNull { compatible(it.representative(), evidence) }
                    if (cluster == null) {
                        clusters += Cluster(mutableListOf(evidence))
                    } else {
                        cluster.addOrReplaceByObserver(evidence)
                    }
                }
            clusters.forEach { result += it.toConsensus() }
        }

        // Room polygons are geometry-bearing and may be non-convex; averaging them would be a hidden
        // topology edit. GeometryEvidenceFusion already merges room labels safely, so preserve them.
        result += rooms
        return result
    }

    private fun compatible(a: SemanticEvidence, b: SemanticEvidence): Boolean {
        if (a.kind != b.kind) return false
        if (distance(a.center, b.center) > centerTolerance(a.kind)) return false

        val aWidth = a.widthMeters
        val bWidth = b.widthMeters
        if (aWidth != null && bWidth != null && ratio(aWidth, bWidth) < MIN_SIZE_RATIO) return false

        val aLength = a.lengthMeters
        val bLength = b.lengthMeters
        if (aLength != null && bLength != null && ratio(aLength, bLength) < MIN_LENGTH_RATIO) return false

        val aRotation = a.rotationDegrees
        val bRotation = b.rotationDegrees
        if (aRotation != null && bRotation != null && axisAngleDelta(aRotation, bRotation) > MAX_AXIS_DELTA_DEGREES) {
            return false
        }
        return true
    }

    private fun Cluster.toConsensus(): SemanticEvidence {
        val explicit = items
            .filter { it.source == EvidenceSource.USER_CORRECTION }
            .maxByOrNull { it.confidence }
        if (explicit != null) return explicit

        if (items.size == 1) return items.first()

        val totalWeight = items.sumOf { sourceWeight(it.source).toDouble() * it.confidence.toDouble() }
            .toFloat()
            .coerceAtLeast(EPSILON)
        fun weighted(selector: (SemanticEvidence) -> Float?): Float? {
            var numerator = 0f
            var denominator = 0f
            for (item in items) {
                val value = selector(item) ?: continue
                val weight = sourceWeight(item.source) * item.confidence
                numerator += value * weight
                denominator += weight
            }
            return if (denominator <= EPSILON) null else numerator / denominator
        }

        val center = Vec2(
            x = items.sumOf {
                (it.center.x * sourceWeight(it.source) * it.confidence).toDouble()
            }.toFloat() / totalWeight,
            z = items.sumOf {
                (it.center.z * sourceWeight(it.source) * it.confidence).toDouble()
            }.toFloat() / totalWeight,
        )

        val confidence = independentConfidence(items)
        val source = when {
            items.any { it.source == EvidenceSource.LOCAL_AI } -> EvidenceSource.LOCAL_AI
            else -> EvidenceSource.CLASSICAL_CV
        }
        val observers = items.map(::observerKey).distinct().sorted()

        return SemanticEvidence(
            kind = items.first().kind,
            center = center,
            widthMeters = weighted { it.widthMeters },
            lengthMeters = weighted { it.lengthMeters },
            rotationDegrees = weightedAxisRotation(items),
            polygon = emptyList(),
            label = items.mapNotNull { it.label }.firstOrNull(),
            countHint = weightedCountHint(items),
            confidence = confidence,
            source = source,
            observerId = "consensus:${observers.joinToString("+")}",
        )
    }

    private fun weightedCountHint(items: List<SemanticEvidence>): Int? {
        val hints = items.mapNotNull { item -> item.countHint?.let { item to it } }
        if (hints.isEmpty()) return null
        return hints.maxByOrNull { (item, _) -> sourceWeight(item.source) * item.confidence }?.second
    }

    private fun weightedAxisRotation(items: List<SemanticEvidence>): Float? {
        var x = 0f
        var y = 0f
        var weightSum = 0f
        for (item in items) {
            val degrees = item.rotationDegrees ?: continue
            val weight = sourceWeight(item.source) * item.confidence
            val doubled = degrees * 2f * (PI.toFloat() / 180f)
            x += cos(doubled) * weight
            y += sin(doubled) * weight
            weightSum += weight
        }
        if (weightSum <= EPSILON || (abs(x) <= EPSILON && abs(y) <= EPSILON)) return null
        var degrees = atan2(y, x) * 0.5f * (180f / PI.toFloat())
        if (degrees < 0f) degrees += 180f
        return degrees
    }

    private fun independentConfidence(items: List<SemanticEvidence>): Float {
        var missProbability = 1f
        for (item in items) {
            val adjusted = (item.confidence * sourceReliability(item.source)).coerceIn(0f, MAX_SINGLE_CONTRIBUTION)
            missProbability *= 1f - adjusted
        }
        return (1f - missProbability).coerceIn(0f, MAX_CONSENSUS_CONFIDENCE)
    }

    private fun sourceWeight(source: EvidenceSource): Float = when (source) {
        EvidenceSource.USER_CORRECTION -> 2.2f
        EvidenceSource.LOCAL_AI -> 1.0f
        EvidenceSource.CLASSICAL_CV -> 0.92f
    }

    private fun sourceReliability(source: EvidenceSource): Float = when (source) {
        EvidenceSource.USER_CORRECTION -> 1f
        EvidenceSource.LOCAL_AI -> 0.94f
        EvidenceSource.CLASSICAL_CV -> 0.88f
    }

    private fun centerTolerance(kind: SemanticKind): Float = when (kind) {
        SemanticKind.DOOR -> 0.30f
        SemanticKind.WINDOW -> 0.34f
        SemanticKind.STAIR -> 0.62f
        SemanticKind.ROOM -> 0f
    }

    private fun axisAngleDelta(a: Float, b: Float): Float {
        val normalizedA = normalize180(a)
        val normalizedB = normalize180(b)
        val raw = abs(normalizedA - normalizedB)
        return min(raw, 180f - raw)
    }

    private fun normalize180(value: Float): Float {
        var normalized = value % 180f
        if (normalized < 0f) normalized += 180f
        return normalized
    }

    private fun ratio(a: Float, b: Float): Float {
        val high = max(abs(a), abs(b))
        val low = min(abs(a), abs(b))
        return if (high <= EPSILON) 0f else low / high
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun observerKey(evidence: SemanticEvidence): String =
        evidence.observerId?.takeIf { it.isNotBlank() } ?: "source:${evidence.source.name}"

    private data class Cluster(
        val items: MutableList<SemanticEvidence>,
    ) {
        fun representative(): SemanticEvidence = items.maxBy { it.confidence }

        fun addOrReplaceByObserver(candidate: SemanticEvidence) {
            val candidateKey = observerKey(candidate)
            val index = items.indexOfFirst { observerKey(it) == candidateKey }
            if (index < 0) {
                items += candidate
            } else if (candidate.confidence > items[index].confidence) {
                items[index] = candidate
            }
        }
    }

    private const val MIN_SIZE_RATIO = 0.58f
    private const val MIN_LENGTH_RATIO = 0.52f
    private const val MAX_AXIS_DELTA_DEGREES = 24f
    private const val MAX_SINGLE_CONTRIBUTION = 0.90f
    private const val MAX_CONSENSUS_CONFIDENCE = 0.97f
    private const val EPSILON = 0.000001f
}
