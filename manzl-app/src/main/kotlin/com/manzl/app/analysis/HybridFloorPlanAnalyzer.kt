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
 * The Ultra path runs the distilled multi-teacher student, an independent OpenCV wall expert and
 * MobileSAM boundary verification. An expert is allowed to report "no safe improvement", but it is
 * not allowed to disappear because of a missing/corrupt model, OOM or runtime exception and then let
 * the weaker pipeline continue silently. Runtime health is therefore fail-closed before any 3D can be
 * exposed. The student's door/window/stair/courtyard/shaft observations are reused from that same
 * inference later as semantics; columns are accepted only by a dedicated source-raster verifier.
 * None of the neural/CV experts becomes geometry authority.
 *
 * A second reconstruction gate runs after semantics/topology. It blocks 3D when strong wall gaps are
 * still unclassified, when a room polygon is not physically backed by measured walls/openings, when
 * trusted closed-room coverage is too sparse to construct real floors and ceilings without inventing
 * a rectangular house footprint, or when a verified vertical shaft cannot yet be represented
 * faithfully by the mesh path.
 */
internal class HybridFloorPlanAnalyzer(
    private val structuralAnalyzer: FloorPlanAnalyzer = ClassicalFloorPlanAnalyzer(),
    private val semanticProviders: List<SemanticEvidenceProvider> = listOf(
        RoomLabelEvidenceProvider(),
        StairPatternEvidenceProvider(),
        WindowSymbolEvidenceProvider(),
        StudentSemanticEvidenceProvider,
        TinySemanticPatchEvidenceProvider(),
    ),
    private val onDeviceStudent: ManzlStudentFloorPlanExpert? = UltraReconstructionRuntime.createStudentExpertOrNull(),
    private val boundaryRefiner: MobileSamBoundaryRefiner? = UltraReconstructionRuntime.createBoundaryRefinerOrNull(),
) : FloorPlanAnalyzer {

    override suspend fun analyze(bitmap: Bitmap, progress: ProgressSink): FloorPlan {
        if (structuralAnalyzer is ClassicalFloorPlanAnalyzer) {
            val ultraDecision = UltraRuntimeQualityGate.currentDecision()
            if (!ultraDecision.ready) {
                val message = ultraDecision.messageArabic
                    ?: "حزمة Ultra غير مكتملة؛ تم إيقاف التحويل بدلاً من تخفيض الجودة بصمت."
                progress.onUpdate(AnalysisUpdate(0, "بوابة الجودة الفائقة • لم يبدأ التحويل لأن حزمة Ultra غير مكتملة"))
                throw UltraRuntimeUnavailableException(message)
            }
        }

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

            val student = onDeviceStudent ?: throw UltraRuntimeUnavailableException(
                "أوقفت التحويل لأن خبير Manzl Reconstruction Student لم يُنشأ رغم أن بوابة Ultra اعتبرته متاحاً. لن أتابع بالمسار الأضعف وحده."
            )
            progress.onUpdate(AnalysisUpdate(79, "خبير Manzl العصبي يفحص المخطط عالمياً وعلى قصاصات عالية الدقة"))
            val studentResult = try {
                withContext(Dispatchers.Default) {
                    student.refine(bitmap, structural)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن الذاكرة لم تكفِ لتشغيل Manzl Reconstruction Student بالجودة المطلوبة. لن أخفض الدقة أو أتجاوز الخبير بصمت."
                )
            } catch (_: RuntimeException) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن Manzl Reconstruction Student فشل أثناء الاستدلال المحلي. لن أستبدله تلقائياً بمسار أقل جودة."
                )
            }
            if (!studentResult.modelAvailable || studentResult.inferenceRegions <= 0) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن نموذج Manzl المحلي لم يُنتج أي مرور استدلال صالح. وجود ملف النموذج وحده لا يكفي لاعتبار Ultra جاهزاً."
                )
            }
            if (studentResult.acceptedWalls > 0) structural = studentResult.plan
            progress.onUpdate(
                AnalysisUpdate(
                    79,
                    "Manzl فحص ${studentResult.inferenceRegions} مناطق، اقترح ${studentResult.proposedWalls} جداراً، قبل ${studentResult.acceptedWalls}، ورصد ${studentResult.semanticObservations} دلالة معمارية",
                )
            )

            progress.onUpdate(AnalysisUpdate(79, "خبير OpenCV يقيس وجهي الجدار ثم يبحث عن الجدران المفقودة"))
            val openCvResult = try {
                withContext(Dispatchers.Default) {
                    OpenCvWallExpert.refine(bitmap, structural)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن خبير OpenCV لم يكتمل بسبب الذاكرة. لن أسقط رأياً مستقلاً من الـEnsemble ثم أتابع كأن الجودة لم تتغير."
                )
            } catch (_: RuntimeException) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن خبير OpenCV الهندسي فشل. لا يوجد downgrade صامت إلى نتيجة أقل ثقة."
                )
            }
            if (openCvResult.acceptedCount > 0) structural = openCvResult.plan
            progress.onUpdate(
                AnalysisUpdate(
                    79,
                    "OpenCV اقترح ${openCvResult.proposedCount} مرشحاً وقبل التحقق ${openCvResult.acceptedCount} فقط",
                )
            )

            val refiner = boundaryRefiner ?: throw UltraRuntimeUnavailableException(
                "أوقفت التحويل لأن MobileSAM Boundary Refiner لم يُنشأ. لا أسمح ببناء 3D بدون مرحلة تدقيق وجوه الجدران عالية الدقة."
            )
            progress.onUpdate(AnalysisUpdate(79, "MobileSAM يدقق وجوه الجدران ونهاياتها على الصورة الأصلية"))
            val refined = try {
                withContext(Dispatchers.Default) {
                    refiner.refine(bitmap, structural)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن MobileSAM لم يكتمل بسبب الذاكرة. لن أتجاوز تدقيق حدود الجدران بالجودة الأقل."
                )
            } catch (_: RuntimeException) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن MobileSAM فشل أثناء تدقيق حدود الجدران محلياً."
                )
            }
            if (!refined.runtimeAvailable || refined.attemptedWalls <= 0) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن MobileSAM لم يتمكن من تنفيذ تدقيق فعلي لأي جدار. لن أعتبر وجود النموذج على الجهاز دليلاً على أن مرحلة الجودة اشتغلت."
                )
            }
            if (refined.accepted) {
                structural = refined.plan
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        "MobileSAM دقق ${refined.refinedWalls} من ${refined.attemptedWalls} جداراً واجتاز إعادة المطابقة",
                    )
                )
            } else {
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        "MobileSAM دقق ${refined.attemptedWalls} جداراً ولم يجد تعديلاً يحسن المطابقة بأمان؛ أبقى الهندسة المقاسة كما هي",
                    )
                )
            }

            // Columns are compact structural masses, not wall/room semantics. A student observation
            // is promoted only when its oriented footprint has strong support in the source raster.
            val columnResult = try {
                withContext(Dispatchers.Default) {
                    StudentColumnRefiner.refine(bitmap, structural)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن تحقق الأعمدة الإنشائية لم يكتمل بسبب الذاكرة؛ لن أسقط هذه الطبقة بصمت."
                )
            } catch (_: RuntimeException) {
                throw UltraRuntimeUnavailableException(
                    "أوقفت التحويل لأن مرحلة تحقق الأعمدة الإنشائية فشلت قبل اكتمال إعادة البناء."
                )
            }
            if (columnResult.acceptedCount > 0) {
                structural = columnResult.plan
                progress.onUpdate(
                    AnalysisUpdate(
                        79,
                        "تحقق المصدر من ${columnResult.acceptedCount} عمود إنشائي من ${columnResult.proposedCount} مرشحاً",
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

            progress.onUpdate(AnalysisUpdate(87, "دمج قراءة الغرف والسلالم والفتحات من الخبراء المحليين"))
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

            progress.onUpdate(AnalysisUpdate(93, "مطابقة الدلالات مع هندسة المخطط المقاسة"))
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

            progress.onUpdate(AnalysisUpdate(99, "فحص أن المنزل قابل للبناء بدون أرضيات أو فتحات أو حدود غرف مخترعة"))
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
