package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Review payload exposed to Compose after a geometry session finishes or is rejected.
 *
 * [source] is a non-owning reference to the same Bitmap already held by the current floor draft; the
 * store never recycles it. [basePlan] is the fresh extractor output before explicit user edits, so
 * undo/replay always starts from measured geometry rather than compounding floating-point drags.
 */
internal data class GeometryReviewItem(
    val floorIndex: Int,
    val source: Bitmap,
    val basePlan: FloorPlan,
    val overlay: Bitmap,
    val plan: FloorPlan,
)

internal data class GeometryReviewState(
    val items: List<GeometryReviewItem> = emptyList(),
    val autoOpen: Boolean = false,
    val revision: Long = 0L,
) {
    val hasBlockingFloor: Boolean
        get() = items.any { !GeometryQualityGate.isReadyFor3d(it.plan) }
}

/**
 * Bridges the background geometry pipeline to a trust/review surface in the UI.
 *
 * Explicit user corrections are retained by source-Bitmap identity for the life of the current
 * draft set. A subsequent Execute re-applies them to a fresh extraction and re-runs the independent
 * fidelity gate. No stored correction can directly set PASS.
 *
 * The runtime gate is stricter than the aggregate fidelity enum: a floor can have aggregate PASS but
 * still be rejected for a severe localized mismatch or a physical near-miss wall junction. Every
 * review-only copy therefore passes through [GeometryQualityGate.planForReview]. The canonical
 * [basePlan] remains untouched so diagnostics can never become geometry authority.
 */
internal object GeometryReviewStore {
    private val lock = Any()
    private val pending = ArrayList<GeometryReviewItem>()
    private val correctionsBySource = IdentityHashMap<Bitmap, MutableList<GeometryCorrection>>()
    private val knownProjectSources = IdentityHashMap<Bitmap, Boolean>()
    private val _state = MutableStateFlow(GeometryReviewState())
    val state: StateFlow<GeometryReviewState> = _state.asStateFlow()

    suspend fun recordStructural(
        source: Bitmap,
        plan: FloorPlan,
        basePlan: FloorPlan = plan,
    ) {
        val reviewPlan = GeometryQualityGate.planForReview(plan)
        val overlay = renderOverlay(source, reviewPlan)
        synchronized(lock) {
            if (pending.isEmpty()) {
                // A first floor whose Bitmap identity was never part of the current draft set marks a
                // new project. Drop old correction references before adopting the new source.
                if (knownProjectSources.isNotEmpty() && !knownProjectSources.containsKey(source)) {
                    correctionsBySource.clear()
                    knownProjectSources.clear()
                }
                _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
            }
            knownProjectSources[source] = true
            pending += GeometryReviewItem(
                floorIndex = pending.size,
                source = source,
                basePlan = basePlan,
                overlay = overlay,
                plan = reviewPlan,
            )
        }
    }

    suspend fun recordFinal(source: Bitmap, plan: FloorPlan) {
        val reviewPlan = GeometryQualityGate.planForReview(plan)
        val overlay = renderOverlay(source, reviewPlan)
        synchronized(lock) {
            knownProjectSources[source] = true
            if (pending.isEmpty()) {
                _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
                pending += GeometryReviewItem(
                    floorIndex = 0,
                    source = source,
                    basePlan = plan,
                    overlay = overlay,
                    plan = reviewPlan,
                )
                return
            }
            val last = pending.last()
            if (!last.overlay.isRecycled) last.overlay.recycle()
            pending[pending.lastIndex] = last.copy(
                source = source,
                overlay = overlay,
                plan = reviewPlan,
            )
        }
    }

    fun correctionsFor(source: Bitmap): List<GeometryCorrection> = synchronized(lock) {
        correctionsBySource[source]?.toList().orEmpty()
    }

    fun correctionCount(source: Bitmap): Int = synchronized(lock) {
        correctionsBySource[source]?.size ?: 0
    }

    /** Apply one explicit edit, replay every edit from [GeometryReviewItem.basePlan], then re-verify. */
    suspend fun applyCorrection(
        floorIndex: Int,
        correction: GeometryCorrection,
    ): GeometryReviewItem? {
        val item = synchronized(lock) {
            _state.value.items.firstOrNull { it.floorIndex == floorIndex }
        } ?: return null

        // Validate this edit against the currently reviewed geometry first. Invalid accidental taps do
        // not enter persistent replay history.
        val validation = GeometryCorrectionEngine.apply(item.plan, listOf(correction))
        if (validation.appliedCount <= 0) return item

        val existing = correctionsFor(item.source)
        val replay = existing + correction
        val verified = GeometryCorrectionEngine.applyAndVerify(
            source = item.source,
            plan = item.basePlan,
            corrections = replay,
        )
        if (verified.appliedCount <= 0) return item
        val reviewPlan = GeometryQualityGate.planForReview(verified.plan)
        val overlay = renderOverlay(item.source, reviewPlan)

        return synchronized(lock) {
            val current = _state.value
            val index = current.items.indexOfFirst { it.floorIndex == floorIndex }
            if (index < 0) {
                if (!overlay.isRecycled) overlay.recycle()
                return@synchronized null
            }
            correctionsBySource.getOrPut(item.source) { ArrayList() }.add(correction)
            knownProjectSources[item.source] = true
            val previous = current.items[index]
            if (!previous.overlay.isRecycled) previous.overlay.recycle()
            val updated = previous.copy(overlay = overlay, plan = reviewPlan)
            val items = current.items.toMutableList().also { it[index] = updated }
            _state.value = current.copy(
                items = items,
                autoOpen = true,
                revision = current.revision + 1L,
            )
            updated
        }
    }

    suspend fun undoLastCorrection(floorIndex: Int): GeometryReviewItem? {
        val item = synchronized(lock) {
            _state.value.items.firstOrNull { it.floorIndex == floorIndex }
        } ?: return null
        val remaining = synchronized(lock) {
            val list = correctionsBySource[item.source] ?: return@synchronized null
            if (list.isEmpty()) return@synchronized null
            list.dropLast(1)
        } ?: return item

        val verifiedPlan = if (remaining.isEmpty()) {
            item.basePlan
        } else {
            GeometryCorrectionEngine.applyAndVerify(
                source = item.source,
                plan = item.basePlan,
                corrections = remaining,
            ).plan
        }
        val reviewPlan = GeometryQualityGate.planForReview(verifiedPlan)
        val overlay = renderOverlay(item.source, reviewPlan)

        return synchronized(lock) {
            val current = _state.value
            val index = current.items.indexOfFirst { it.floorIndex == floorIndex }
            if (index < 0) {
                if (!overlay.isRecycled) overlay.recycle()
                return@synchronized null
            }
            if (remaining.isEmpty()) correctionsBySource.remove(item.source)
            else correctionsBySource[item.source] = ArrayList(remaining)
            val previous = current.items[index]
            if (!previous.overlay.isRecycled) previous.overlay.recycle()
            val updated = previous.copy(overlay = overlay, plan = reviewPlan)
            val items = current.items.toMutableList().also { it[index] = updated }
            _state.value = current.copy(
                items = items,
                autoOpen = true,
                revision = current.revision + 1L,
            )
            updated
        }
    }

    suspend fun clearCorrections(floorIndex: Int): GeometryReviewItem? {
        val item = synchronized(lock) {
            _state.value.items.firstOrNull { it.floorIndex == floorIndex }
        } ?: return null
        val reviewPlan = GeometryQualityGate.planForReview(item.basePlan)
        val overlay = renderOverlay(item.source, reviewPlan)
        return synchronized(lock) {
            correctionsBySource.remove(item.source)
            val current = _state.value
            val index = current.items.indexOfFirst { it.floorIndex == floorIndex }
            if (index < 0) {
                if (!overlay.isRecycled) overlay.recycle()
                return@synchronized null
            }
            val previous = current.items[index]
            if (!previous.overlay.isRecycled) previous.overlay.recycle()
            val updated = previous.copy(overlay = overlay, plan = reviewPlan)
            val items = current.items.toMutableList().also { it[index] = updated }
            _state.value = current.copy(
                items = items,
                autoOpen = true,
                revision = current.revision + 1L,
            )
            updated
        }
    }

    fun commitFailure(plan: FloorPlan) {
        synchronized(lock) {
            if (pending.isEmpty()) return
            val last = pending.last()
            pending[pending.lastIndex] = last.copy(plan = GeometryQualityGate.planForReview(plan))
            publish(autoOpen = true)
        }
    }

    fun commitBuilding(orderedPlans: List<FloorPlan>) {
        synchronized(lock) {
            if (pending.isEmpty()) return
            val count = minOf(pending.size, orderedPlans.size)
            val resolved = ArrayList<GeometryReviewItem>(count)
            for (index in 0 until count) {
                val item = pending[index]
                resolved += item.copy(
                    floorIndex = index,
                    plan = GeometryQualityGate.planForReview(orderedPlans[index]),
                )
            }
            _state.value = GeometryReviewState(
                items = resolved,
                autoOpen = false,
                revision = _state.value.revision + 1L,
            )
            pending.clear()
        }
    }

    fun clearVisible() {
        synchronized(lock) {
            _state.value.items.forEach { item ->
                if (!item.overlay.isRecycled) item.overlay.recycle()
            }
            _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
        }
    }

    fun clearAllCorrections() {
        synchronized(lock) {
            correctionsBySource.clear()
            knownProjectSources.clear()
            pending.forEach { item -> if (!item.overlay.isRecycled) item.overlay.recycle() }
            pending.clear()
            _state.value.items.forEach { item -> if (!item.overlay.isRecycled) item.overlay.recycle() }
            _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
        }
    }

    fun abortPending() {
        synchronized(lock) {
            pending.forEach { item ->
                if (!item.overlay.isRecycled) item.overlay.recycle()
            }
            pending.clear()
        }
    }

    private fun publish(autoOpen: Boolean) {
        _state.value.items.forEach { previous ->
            if (!previous.overlay.isRecycled) previous.overlay.recycle()
        }
        _state.value = GeometryReviewState(
            items = pending.toList(),
            autoOpen = autoOpen,
            revision = _state.value.revision + 1L,
        )
        pending.clear()
    }

    private suspend fun renderOverlay(source: Bitmap, plan: FloorPlan): Bitmap =
        withContext(Dispatchers.Default) {
            GeometryOverlayRenderer.render(source, plan, REVIEW_MAX_SIDE)
        }

    private const val REVIEW_MAX_SIDE = 1200
}
