package com.manzl.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordingUiState(
    val isRecording: Boolean = false,
    val lastSavedUri: String? = null,
)

/** In-process state bridge between the foreground recorder service and Compose UI. */
internal object TourRecordingState {
    private val mutable = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = mutable.asStateFlow()

    fun setRecording(recording: Boolean) {
        mutable.value = mutable.value.copy(isRecording = recording)
    }

    fun finish(savedUri: String?) {
        mutable.value = RecordingUiState(isRecording = false, lastSavedUri = savedUri)
    }

    fun clearSavedUri() {
        mutable.value = mutable.value.copy(lastSavedUri = null)
    }
}
