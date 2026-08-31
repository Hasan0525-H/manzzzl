package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.AnalysisUpdate
import com.manzl.app.model.DoorEvidenceKind
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
 * Deterministic measured geometry is authoritative. Semantic providers may classify already measured
 * features, but cannot create topology. Geometry-only opening-sized gaps stay available internally
 * for room closure and symbol search, then are removed from the final user-visible plan unless an
 * independent semantic/user signal confirms that the gap is actually a door.
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
        if (structuralAnalyzer is ClassicalFloorPlanAnalyzer) {
            val precisionSide = PrecisionGeometryRetryPolicy.analysisSideOrNull(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
            )
            if (precisionSide != null) {
                val primaryWasPass = primaryStructural.geometryFidelity.status == GeometryFidelityStatus.PASS
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        if (primaryWasPass) {
                            "تأكيد PASS على دقة أعلى حتى ${precisionSide}px قبل السماح ببناء 3D"
                        } else {
                            "المطابقة لم تصل PASS • إعادة فحص هندسي أدق حتى ${precisionSide}px بدون تخفيف الشروط"
                        },
                    )
                )

                val retry = try {
                    ClassicalFloorPlanAnalyzer(maxAnalysisSide = precisionSide).analyze(
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

                val primaryVerifiedAtDenseRaster = try {
                    HighResolutionFidelityVerifier.verify(
                        source = bitmap,
                        plan = primaryStructural,
                        analysisSide = precisionSide,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }

                structural = when {
                    retry != null && primaryVerifiedAtDenseRaster != null ->
                        GeometryRetryChooser.choose(primaryVerifiedAtDenseRaster, retry)
                    retry != null -> retry
                    primaryVerifiedAtDenseRaster != null -> primaryVerifiedAtDenseRaster
                    else -> primaryStructural
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
            progress.onUpdate(AnalysisUpdate(82, "بناء خط أساس للفتحات والغرف متعددة الزوايا"))
            val baselineDoors = DoorInferenceEngine.infer(structural)
            val withDoors = structural.copy(
                doors = mergeDoors(structural.doors, baselineDoors),
            )
            val baselineRooms = PolygonRoomInferenceEngine.infer(withDoors) + RoomInferenceEngine.infer(withDoors)
            val baseline = withDoors.copy(
                rooms = mergeRooms(withDoors.rooms, baselineRooms),
            )

            progress.onUpdate(AnalysisUpdate(87, "قراءة الغرف والسلالم والفتحات والذكاء المحلي"))
            val semanticEvidence = ArrayList<SemanticEvidence>()
            semanticProviders.forEach { provider ->
                val providerPlan = if (provider is WindowSymbolEvidenceProvider) {
                    // A door-sized geometry gap is not enough to suppress a real double-line window.
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
            val inferredRooms = PolygonRoomInferenceEngine.infer(withClassifiedOpenings) +
                RoomInferenceEngine.infer(withClassifiedOpenings)
            val topologyEnriched = withClassifiedOpenings.copy(
                rooms = mergeRooms(withClassifiedOpenings.rooms, inferredRooms),
            )

            // Geometry-only door candidates have served their topology purpose. Do not let a gap
            // that merely has a door-like width receive frames, animated leaves or façade joinery.
            val finalPlan = DoorPresentationPolicy.stripUnclassifiedGaps(topologyEnriched)

            GeometryReviewStore.recordFinal(bitmap, finalPlan)
            progress.onUpdate(AnalysisUpdate(100, "اجتاز المخطط بوابة الجودة وتم تجهيز المنزل للجولة"))
            finalPlan
        } catch (error: Throwable) {
            GeometryReviewStore.abortPending()
            throw error
        }
    }

    private fun mergeDoors(base: List<DoorOpening>, inferred: List<DoorOpening>): List<DoorOpening> =
        (base + inferred)
            .sortedWith(
                compareByDescending<DoorOpening> { doorAuthority(it.evidenceKind) }
                    .thenByDescending { it.confidence }
            )
            .fold(ArrayList<DoorOpening>()) { result, candidate ->
                val duplicate = result.any { existing ->
                    val dx = existing.center.x - candidate.center.x
                    val dz = existing.center.z - candidate.center.z
                    dx * dx + dz * dz < DOOR_DUPLICATE_DISTANCE_SQ
                }
                if (!duplicate) result += candidate
                result
            }

    private fun doorAuthority(kind: DoorEvidenceKind): Int = when (kind) {
        DoorEvidenceKind.MEASURED_GAP -> 0
        DoorEvidenceKind.SEMANTIC_CONFIRMED -> 1
        DoorEvidenceKind.USER_CONFIRMED -> 2
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
