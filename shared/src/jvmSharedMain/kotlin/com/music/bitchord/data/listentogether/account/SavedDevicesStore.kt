package com.music.bitchord.data.listentogether.account

import kotlinx.serialization.json.Json

/**
 * Persists known devices discovered over the account channel so they survive
 * app restarts, brief outages, or offline sessions without disappearing from the Connect UI.
 */
class SavedDevicesStore(
    private val loadRaw: () -> String?,
    private val saveRaw: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    fun load(): List<AccountDevice> {
        val raw = loadRaw()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<AccountDevice>>(raw)
        }.getOrDefault(emptyList())
    }

    fun save(devices: List<AccountDevice>) {
        runCatching {
            // Keep up to 32 recent known devices, persisting basic metadata without volatile links
            val serializable = devices
                .filter { !it.hidden }
                .take(32)
                .map {
                    it.copy(
                        connected = false,
                        controlledBy = null,
                        controlling = null,
                        partyCode = null,
                    )
                }
            saveRaw(json.encodeToString(serializable))
        }
    }
}
