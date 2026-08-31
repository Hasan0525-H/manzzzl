package com.manzl.app.analysis

import android.graphics.Bitmap
import com.manzl.app.model.FloorPlan
import com.manzl.app.model.GeometryFidelityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Immutable review payload exposed to Compose after a geometry session finishes or is rejected.
 * Only a bounded overlay bitmap is retained; the original uploaded bitmap is never stored here.
 */
internal data class GeometryReviewItem(
    val floorIndex: Int,
    val overlay: Bitmap,
    val plan: FloorPlan,
)

internal data class GeometryReviewState(
    val items: List<GeometryReviewItem> = emptyList(),
    val autoOpen: Boolean = false,
    val revision: Long = 0L,
) {
    val hasBlockingFloor: Boolean
        get() = items.any { it.plan.geometryFidelity.status != GeometryFidelityStatus.PASS }
}

/**
 * Bridges the background geometry pipeline to a read-only trust/review surface in the UI.
 *
 * A new session is inferred when the pending list is empty. Multi-floor analysis appends in upload
 * order. Successful sessions are published only after BuildingPlanAssembler receives every floor;
 * rejected sessions are published immediately so the exact failing overlay is visible to the user.
 * Overlay rendering stays off the Compose/main thread.
 */
internal object GeometryReviewStore {
    private val lock = Any()
    private val pending = ArrayList<GeometryReviewItem>()
    private val _state = MutableStateFlow(GeometryReviewState())
    val state: StateFlow<GeometryReviewState> = _state.asStateFlow()

    suspend fun recordStructural(source: Bitmap, plan: FloorPlan) {
        val overlay = renderOverlay(source, plan)
        synchronized(lock) {
            if (pending.isEmpty()) {
                // Hide the previous project as soon as a new geometry session has real output.
                _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
            }
            pending += GeometryReviewItem(
                floorIndex = pending.size,
                overlay = overlay,
                plan = plan,
            )
        }
    }

    suspend fun recordFinal(source: Bitmap, plan: FloorPlan) {
        val overlay = renderOverlay(source, plan)
        synchronized(lock) {
            if (pending.isEmpty()) {
                _state.value = GeometryReviewState(revision = _state.value.revision + 1L)
                pending += GeometryReviewItem(
                    floorIndex = 0,
                    overlay = overlay,
                    plan = plan,
                )
                return
            }
            val last = pending.last()
            if (!last.overlay.isRecycled) last.overlay.recycle()
            pending[pending.lastIndex] = last.copy(
                overlay = overlay,
                plan = plan,
            )
        }
    }

    fun commitFailure(plan: FloorPlan) {
        synchronized(lock) {
            if (pending.isEmpty()) return
            val last = pending.last()
            pending[pending.lastIndex] = last.copy(plan = plan)
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
                    plan = orderedPlans[index],
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
