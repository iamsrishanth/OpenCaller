package io.github.iamsrishanth.opencaller.telecom

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap

data class AudioRouteOption(
    val id: String,
    val label: String,
    val type: Int,
    val selected: Boolean,
)

data class CallUiModel internal constructor(
    val id: String,
    val title: String,
    val address: String,
    val state: Int,
    val canHoldNow: Boolean,
    val supportsHold: Boolean,
    val supportsMute: Boolean,
    internal val platformCall: Call,
)

data class CallUiState(
    val calls: List<CallUiModel> = emptyList(),
    val muted: Boolean = false,
    val audioRoutes: List<AudioRouteOption> = emptyList(),
)

/** Process-local bridge between Telecom's service callbacks and the Compose call screen. */
object CallSession {
    private val lock = Any()
    private val callsById = linkedMapOf<String, Call>()
    private val idsByCall = IdentityHashMap<Call, String>()
    private var service: OpenCallerInCallService? = null
    private val _state = MutableStateFlow(CallUiState())
    private val dtmfHandler = Handler(Looper.getMainLooper())

    val state: StateFlow<CallUiState> = _state.asStateFlow()

    internal fun attach(service: OpenCallerInCallService) {
        synchronized(lock) { this.service = service }
    }

    internal fun detach(service: OpenCallerInCallService) {
        synchronized(lock) {
            if (this.service === service) this.service = null
        }
    }

    internal fun add(call: Call): String = synchronized(lock) {
        idsByCall[call]?.let { return@synchronized it }
        val base = System.identityHashCode(call).toString()
        var id = base
        var suffix = 1
        while (callsById.containsKey(id)) id = "$base-${suffix++}"
        callsById[id] = call
        idsByCall[call] = id
        publishLocked()
        id
    }

    internal fun refresh(call: Call) = synchronized(lock) {
        if (idsByCall.containsKey(call)) publishLocked()
    }

    internal fun remove(call: Call): String? = synchronized(lock) {
        val id = idsByCall.remove(call) ?: return@synchronized null
        callsById.remove(id)
        publishLocked()
        id
    }

    internal fun findId(call: Call): String? = synchronized(lock) { idsByCall[call] }

    internal fun setAudioRoutes(routes: List<AudioRouteOption>) {
        _state.value = _state.value.copy(audioRoutes = routes)
    }

    @Suppress("DEPRECATION")
    internal fun updateCallAudioState(audioState: CallAudioState?) {
        if (audioState == null) return
        val routes = buildList {
            val choices = listOf(
                CallAudioState.ROUTE_EARPIECE to "Earpiece",
                CallAudioState.ROUTE_SPEAKER to "Speaker",
                CallAudioState.ROUTE_WIRED_HEADSET to "Wired headset",
                CallAudioState.ROUTE_BLUETOOTH to "Bluetooth",
            )
            choices.forEach { (route, label) ->
                if (audioState.supportedRouteMask and route != 0) {
                    add(
                        AudioRouteOption(
                            id = "legacy:$route",
                            label = label,
                            type = route,
                            selected = audioState.route == route,
                        ),
                    )
                }
            }
        }
        _state.value = _state.value.copy(muted = audioState.isMuted, audioRoutes = routes)
    }

    fun answer(callId: String) {
        findCall(callId)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject(callId: String) {
        findCall(callId)?.reject(false, null)
    }

    fun disconnect(callId: String) {
        findCall(callId)?.disconnect()
    }

    fun toggleHold(callId: String) {
        val call = findCall(callId) ?: return
        val capabilities = call.details.callCapabilities
        when {
            call.state == Call.STATE_ACTIVE && capabilities and Call.Details.CAPABILITY_HOLD != 0 -> call.hold()
            call.state == Call.STATE_HOLDING && capabilities and Call.Details.CAPABILITY_SUPPORT_HOLD != 0 -> call.unhold()
        }
    }

    fun toggleMute() {
        val snapshot = _state.value
        if (snapshot.calls.any { it.supportsMute }) {
            synchronized(lock) { service }?.setMuted(!snapshot.muted)
        }
    }

    fun selectAudioRoute(routeId: String) {
        synchronized(lock) { service }?.selectAudioRoute(routeId)
    }

    fun sendDtmf(callId: String, digit: Char) {
        if (digit !in "0123456789*#") return
        val call = findCall(callId) ?: return
        runCatching {
            call.stopDtmfTone()
            call.playDtmfTone(digit)
            dtmfHandler.postDelayed({ runCatching { call.stopDtmfTone() } }, 160L)
        }
    }

    internal fun modelFor(call: Call): CallUiModel? = synchronized(lock) {
        val id = idsByCall[call] ?: return@synchronized null
        createModel(id, call)
    }

    private fun findCall(callId: String): Call? = synchronized(lock) { callsById[callId] }

    private fun publishLocked() {
        val models = callsById.mapNotNull { (id, call) -> createModel(id, call) }
            .sortedWith(
                compareBy<CallUiModel> {
                    when (it.state) {
                        Call.STATE_RINGING -> 0
                        Call.STATE_ACTIVE -> 1
                        Call.STATE_DIALING, Call.STATE_CONNECTING -> 2
                        Call.STATE_HOLDING -> 3
                        else -> 4
                    }
                }.thenBy { it.id },
            )
        _state.value = _state.value.copy(calls = models)
    }

    private fun createModel(id: String, call: Call): CallUiModel? = runCatching {
        val details = call.details
        val address = details.handle?.schemeSpecificPart.orEmpty()
        val networkName = details.callerDisplayName?.toString().orEmpty()
        val capabilities = details.callCapabilities
        CallUiModel(
            id = id,
            title = networkName.ifBlank { address.ifBlank { "Unknown caller" } },
            address = address,
            state = call.state,
            canHoldNow = capabilities and Call.Details.CAPABILITY_HOLD != 0,
            supportsHold = capabilities and Call.Details.CAPABILITY_SUPPORT_HOLD != 0,
            supportsMute = capabilities and Call.Details.CAPABILITY_MUTE != 0,
            platformCall = call,
        )
    }.getOrNull()
}
