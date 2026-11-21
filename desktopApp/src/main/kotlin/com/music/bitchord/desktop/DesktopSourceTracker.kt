package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Report of an individual audio source resolution attempt for the active track.
 */
internal data class DesktopSourceAttempt(
    val sourceId: String,
    val displayName: String,
    val status: String,
    val isWinner: Boolean = false,
)

/**
 * Tracks what every source returned during track resolution and quality upgrade passes.
 * Mirrors Android's SourceResolutionTracker for DesktopAudioPipelineDialog.
 */
internal object DesktopSourceTracker {
    private val lock = Any()
    private val reports = LinkedHashMap<String, DesktopSourceAttempt>()
    private val _state = MutableStateFlow<List<DesktopSourceAttempt>>(emptyList())
    val state: StateFlow<List<DesktopSourceAttempt>> = _state.asStateFlow()

    fun reset(sources: List<DesktopSourceConfig>) {
        synchronized(lock) {
            reports.clear()
            for (s in sources.filter { it.enabled }) {
                reports[s.id] = DesktopSourceAttempt(
                    sourceId = s.id,
                    displayName = s.displayName.ifBlank { s.kind.label },
                    status = "Queued",
                    isWinner = false,
                )
            }
            _state.value = reports.values.toList()
        }
    }

    fun record(sourceId: String, displayName: String, status: String, isWinner: Boolean = false) {
        synchronized(lock) {
            val existing = reports[sourceId]
            reports[sourceId] = DesktopSourceAttempt(
                sourceId = sourceId,
                displayName = displayName.ifBlank { existing?.displayName ?: sourceId },
                status = status,
                isWinner = isWinner,
            )
            _state.value = reports.values.toList()
        }
    }

    fun setWinner(sourceId: String, status: String) {
        synchronized(lock) {
            val existing = reports[sourceId]
            reports[sourceId] = DesktopSourceAttempt(
                sourceId = sourceId,
                displayName = existing?.displayName ?: sourceId,
                status = status,
                isWinner = true,
            )
            for ((id, report) in reports.entries) {
                if (id != sourceId && report.isWinner) {
                    reports[id] = report.copy(isWinner = false)
                }
            }
            _state.value = reports.values.toList()
        }
    }
}
