package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.DoorOpening
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus
import com.manzl.app.model.RoomRegion
import com.manzl.app.model.Vec2
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/**
 * Production-facing analyzer facade.
 *
 * 1) deterministic Geometry Engine v2 establishes measured topology, wall faces and metric scale;
 * 2) the extracted wall faces are independently re-rasterized over the source plan;
 * 3) if the normal 2200px pass misses PASS and memory permits, the exact same extractor retries at
 *    2800/3200px; thresholds are never loosened and the stronger independent report wins;
 * 4) explicit user corrections, when present, are re-applied to fresh geometry and independently
 *    verified against the original raster; they cannot set PASS directly;
 * 5) GeometryQualityGate blocks 3D unless the selected geometry fidelity report is PASS;
 * 6) a bounded source+geometry overlay is retained for explicit user review;
 * 7) deterministic polygon+rectilinear room topology creates a geometry baseline;
 * 8) bundled on-device semantic providers may label rooms or suggest stairs/openings;
 * 9) a tiny bundled neural patch model may independently confirm door/window/stair symbols;
 * 10) independent semantic observations are combined only when they agree spatially/structurally;
 * 11) GeometryEvidenceFusion accepts only evidence that is geometrically plausible;
 * 12) deterministic topology is re-run after fusion and remains the sole source of truth for 3D;
 * 13) door swing symbols may enrich an accepted opening but never create or move one.
 *
 * No provider is allowed to require a network connection in the release build.
 */
internal class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
    private val semanticProviders: List<SemanticEvidenceProvider> = listOf(
        RoomLabelEvidenceProvider(),
        StairPatternEvidenceProvider(),
        WindowSymbolEvidenceProvider(),
        TinySemanticPatchEvidenceProvider(),
    ),
) : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan {
        val primaryStructural = structuralAnalyzer.analyze(
            bitmap = bitmap,
            progress = ProgressSink { update ->
                val remapped = (update.percent * 0.78f).toInt().coerceIn(0, 78)
                progress.onUpdate(update.copy(percent = remapped))
            },
        )

        var structural = primaryStructural
        if (
            structuralAnalyzer is ClassicalFloorPlanAnalyzer &&
            primaryStructural.geometryFidelity.status != GeometryFidelityStatus.PASS
        ) {
            val retrySide = PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
            )
            if (retrySide != null) {
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        "المطابقة لم تصل PASS • إعادة فحص هندسي أدق حتى ${retrySide}px بدون تخفيف الشروط",
                    )
                )
                val retry = try {
                    ClassicalFloorPlanAnalyzer(maxAnalysisSide = retrySide).analyze(
                        bitmap = bitmap,
                        progress = ProgressSink { update ->
                            progress.onUpdate(
                                AnalysisUpdate(
                                    79,
                                    "فحص الدقة الإضافي • ${update.messageArabic}",
                                )
                            )
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                if (retry != null) {
                    structural = GeometryRetryChooser.choose(primaryStructural, retry)
                }
            }
        }

        val extractedStructural = structural
        val explicitCorrections = GeometryReviewStore.correctionsFor(bitmap)
        if (explicitCorrections.isNotEmpty()) {
            progress.onUpdate(AnalysisUpdate(79, "إعادة تطبيق تصحيحاتك الهندسية والتحقق منها على الصورة الأصلية"))
            val corrected = try {
                GeometryCorrectionEngine.applyAndVerify(
                    source = bitmap,
                    plan = extractedStructural,
                    corrections = explicitCorrections,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (corrected != null && corrected.appliedCount > 0) {
                structural = corrected.plan
            }
        }

        GeometryReviewStore.recordStructural(
            source = bitmap,
            plan = structural,
            basePlan = extractedStructural,
        )

        GeometryQualityGate.rejectionMessageArabic(structural)?.let { rejection ->
            progress.onUpdate(AnalysisUpdate(80, "فشل بوابة مطابقة 2D • افتح مراجعة التطابق"))
            GeometryReviewStore.commitFailure(structural)
            throw GeometryQualityRejectedException(structural, rejection)
        }

        return try {
            progress.onUpdate(AnalysisUpdate(82, "بناء خط أساس للأبواب والغرف متعددة الزوايا"))
            val baselineDoors = DoorInferenceEngine.infer(structural)
            val withDoors = structural.copy(
                doors = mergeDoors(structural.doors, baselineDoors),
            )
            val baselineRooms = RoomTopologyEngine.infer(withDoors)
            val baseline = withDoors.copy(
                rooms = mergeRooms(withDoors.rooms, baselineRooms),
            )

            progress.onUpdate(AnalysisUpdate(87, "قراءة الغرف والسلالم والفتحات والذكاء المحلي"))
            val semanticEvidence = ArrayList<SemanticEvidence>()
            semanticProviders.forEach { provider ->
                val providerPlan = if (provider is WindowSymbolEvidenceProvider) {
                    // Geometry-only door gaps are not sufficient evidence to suppress a real double-line
                    // window symbol. Door/window conflicts are decided later after swing-arc enrichment.
                    baseline.copy(doors = emptyList())
                } else {
                    baseline
                }
                semanticEvidence += provider.analyze(bitmap, providerPlan)
            }

            progress.onUpdate(AnalysisUpdate(91, "دمج أدلة CV والذكاء المحلي المتفقة"))
            val consensusEvidence = SemanticEvidenceConsensus.combine(semanticEvidence)

            progress.onUpdate(AnalysisUpdate(93, "مطابقة الدلالات مع هندسة المخطط"))
            val reconciled = GeometryEvidenceFusion.fuse(baseline, consensusEvidence)

            progress.onUpdate(AnalysisUpdate(95, "مراجعة مرشحات الأبواب والنوافذ"))
            val inferredDoors = DoorInferenceEngine.infer(reconciled)
            val finalDoors = mergeDoors(reconciled.doors, inferredDoors)
            val withFinalDoors = reconciled.copy(doors = finalDoors)

            progress.onUpdate(AnalysisUpdate(97, "قراءة مفصلات واتجاه فتح الأبواب من رموز المخطط"))
            val withDoorDynamics = withFinalDoors.copy(
                doors = DoorSwingArcDetector.enrich(bitmap, withFinalDoors),
            )
            val withClassifiedOpenings = OpeningSemanticReconciler.reconcile(withDoorDynamics)

            progress.onUpdate(AnalysisUpdate(98, "مراجعة حدود الغرف المائلة والأسقف"))
            val inferredRooms = RoomTopologyEngine.infer(withClassifiedOpenings)
            val enriched = withClassifiedOpenings.copy(
                rooms = mergeRooms(withClassifiedOpenings.rooms, inferredRooms),
            )

            GeometryReviewStore.recordFinal(bitmap, enriched)
            progress.onUpdate(AnalysisUpdate(100, "اجتاز المخطط بوابة الجودة وتم تجهيز المنزل للجولة"))
            enriched
        } catch (error: Throwable) {
            GeometryReviewStore.abortPending()
            throw error
        }
    }

    private fun mergeDoors(base: List<DoorOpening>, inferred: List<DoorOpening>): List<DoorOpening> =
        (base + inferred)
            .sortedByDescending { it.confidence }
            .fold(ArrayList<DoorOpening>()) { result, candidate ->
                val duplicate = result.any { existing ->
                    val dx = existing.center.x - candidate.center.x
                    val dz = existing.center.z - candidate.center.z
                    dx * dx + dz * dz < DOOR_DUPLICATE_DISTANCE_SQ
                }
                if (!duplicate) result += candidate
                result
            }

    private fun mergeRooms(base: List<RoomRegion>, inferred: List<RoomRegion>): List<RoomRegion> {
        val result = ArrayList<RoomRegion>()
        for (candidate in (base + inferred).sortedByDescending { it.confidence }) {
            val center = candidate.centroidOrNull() ?: continue
            val index = result.indexOfFirst { existing ->
                val existingCenter = existing.centroidOrNull() ?: return@indexOfFirst false
                val dx = existingCenter.x - center.x
                val dz = existingCenter.z - center.z
                val distance = sqrt(dx * dx + dz * dz)
                distance <= ROOM_CENTER_DUPLICATE_METERS &&
                    areaRatio(existing, candidate) >= ROOM_DUPLICATE_MIN_AREA_RATIO
            }
            if (index < 0) {
                result += candidate
            } else if (result[index].label.isNullOrBlank() && !candidate.label.isNullOrBlank()) {
                val existing = result[index]
                result[index] = existing.copy(
                    label = candidate.label,
                    confidence = maxOf(existing.confidence, candidate.confidence),
                )
            }
        }
        return result
    }

    private fun RoomRegion.centroidOrNull(): Vec2? {
        if (polygon.isEmpty()) return null
        return Vec2(
            x = polygon.sumOf { it.x.toDouble() }.toFloat() / polygon.size,
            z = polygon.sumOf { it.z.toDouble() }.toFloat() / polygon.size,
        )
    }

    private fun areaRatio(a: RoomRegion, b: RoomRegion): Float {
        val areaA = boundingArea(a)
        val areaB = boundingArea(b)
        if (areaA <= 0f || areaB <= 0f) return 0f
        return minOf(areaA, areaB) / maxOf(areaA, areaB)
    }

    private fun boundingArea(room: RoomRegion): Float {
        if (room.polygon.isEmpty()) return 0f
        val minX = room.polygon.minOf { it.x }
        val maxX = room.polygon.maxOf { it.x }
        val minZ = room.polygon.minOf { it.z }
        val maxZ = room.polygon.maxOf { it.z }
        return (maxX - minX).coerceAtLeast(0f) * (maxZ - minZ).coerceAtLeast(0f)
    }

    companion object {
        private const val DOOR_DUPLICATE_DISTANCE_SQ = 0.10f
        private const val ROOM_CENTER_DUPLICATE_METERS = 0.32f
        private const val ROOM_DUPLICATE_MIN_AREA_RATIO = 0.72f
    }
}
