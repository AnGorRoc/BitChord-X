package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.account.HandoffForce
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The screens' way to ask the playback service to move the sound between the person's devices.
 *
 * The player lives in the service and the Connect sheet lives in the app, so the sheet cannot call
 * the speaker directly. What the sheet wants is said here and the service acts on it. What is
 * *happening* is not carried here: that is the account state, which both read.
 */
object DeviceLink {
    sealed interface Request {
        /** The person tapped a device. What that means depends on who is sounding (see HandoffRules). */
        data class Pick(val deviceId: String, val force: HandoffForce?) : Request

        /** The person tapped "This phone" while another device is the speaker. */
        data object ComeBack : Request

        /** The person adjusted internal playback volume for this device. */
        data class SetLocalVolume(val volume: Float) : Request

        /** The person adjusted volume for another device that may be the speaker. */
        data class SetRemoteVolume(val deviceId: String, val volume: Float) : Request
    }

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    private val _connecting = MutableStateFlow<String?>(null)

    /** The device a hand-over is on its way to, set by the playback service; null when none is. */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)

    /**
     * Whether the playback service is up. Commands from the person's other devices are carried out
     * there, so while it is not the device says it is not reachable instead of taking commands it
     * would ignore.
     */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _appInForeground = MutableStateFlow(true)

    /** Whether the app's UI is active in the foreground on this device. */
    val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    private val _remoteActive = MutableStateFlow(false)

    /** Whether another of the person's devices is the speaker and this phone is its remote. */
    val remoteActive: StateFlow<Boolean> = _remoteActive.asStateFlow()

    fun setRemoteActive(active: Boolean) {
        _remoteActive.value = active
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun setAppInForeground(inForeground: Boolean) {
        _appInForeground.value = inForeground
    }

    fun setConnecting(deviceId: String?) {
        _connecting.value = deviceId
    }

    fun pick(deviceId: String, force: HandoffForce? = null) {
        _requests.tryEmit(Request.Pick(deviceId, force))
    }

    fun comeBack() {
        _requests.tryEmit(Request.ComeBack)
    }

    fun setLocalVolume(volume: Float) {
        _requests.tryEmit(Request.SetLocalVolume(volume))
    }

    fun setRemoteVolume(deviceId: String, volume: Float) {
        _requests.tryEmit(Request.SetRemoteVolume(deviceId, volume))
    }
}
