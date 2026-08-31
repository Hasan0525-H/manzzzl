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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Production-facing analyzer facade.
 *
 * Deterministic measured geometry is authoritative. Semantic providers may classify already measured
 * features, but cannot create topology. Geometry-only opening-sized gaps stay available internally
 * for room closure and symbol search, then are removed from the final user-visible plan unless an
 * independent semantic/user signal confirms that the gap is actually a door.
 *
 * The ultra path can run a distilled Raster2Seq/RoomFormer student first, then an independent OpenCV
 * expert. Neither becomes geometry authority: every novel wall must improve independent source-raster
 * fidelity without a material precision regression before it is admitted into the measured plan.
 *
 * A second reconstruction gate runs after semantics/topology. It blocks 3D when strong wall gaps are
 * still unclassified or when trusted closed-room coverage is too sparse to construct real floors and
 * ceilings without inventing a rectangular house footprint.
 */
internal class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
    private val semanticProviders: List<SemanticEvidenceProvider> = listOf(
        RoomLabelEvidenceProvider(),
        StairPatternEvidenceProvider(),
        WindowSymbolEvidenceProvider(),
        TinySemanticPatchEvidenceProvider(),
    ),
    private val onDeviceStudent: ManzlStudentFloorPlanExpert? = UltraReconstructionRuntime.createStudentExpertOrNull(),
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

            onDeviceStudent?.let { student ->
                progress.onUpdate(AnalysisUpdate(79, "خبير Manzl العصبي يراجع الجدران المقاسة بدون سلطة على الهندسة"))
                val studentResult = try {
                    withContext(Dispatchers.Default) {
                        student.refine(bitmap, structural)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                if (studentResult != null && studentResult.acceptedWalls > 0) {
                    structural = studentResult.plan
                    progress.onUpdate(
                        AnalysisUpdate(
                            79,
                            "النموذج اقترح ${studentResult.proposedWalls} جداراً وقبل التحقق ${studentResult.acceptedWalls} فقط",
                        )
                    )
                }
            }

            progress.onUpdate(AnalysisUpdate(79, "خبير OpenCV مستقل يبحث عن جدران مفقودة ويعيد التحقق من الصورة"))
            val openCvResult = try {
                withContext(Dispatchers.Default) {
                    OpenCvWallExpert.refine(bitmap, structural)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (openCvResult != null && openCvResult.acceptedCount > 0) {
                structural = openCvResult.plan
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        "OpenCV اقترح ${openCvResult.proposedCount} خطاً وقبل التحقق ${openCvResult.acceptedCount} جداراً فقط",
                    )
                )
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

            progress.onUpdate(AnalysisUpdate(99, "فحص أن المنزل قابل للبناء بدون أرضيات أو فتحات مخترعة"))
            ReconstructionReadinessGate.rejectionMessageArabic(finalPlan)?.let { rejection ->
                val reviewPlan = ReconstructionReadinessGate.planForReview(finalPlan)
                GeometryReviewStore.recordFinal(bitmap, reviewPlan)
                GeometryReviewStore.commitFailure(reviewPlan)
                throw GeometryQualityRejectedException(reviewPlan, rejection)
            }

            GeometryReviewStore.recordFinal(bitmap, finalPlan)
            progress.onUpdate(AnalysisUpdate(100, "اجتاز المخطط بوابة إعادة البناء وتم تجهيز المنزل للجولة"))
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
