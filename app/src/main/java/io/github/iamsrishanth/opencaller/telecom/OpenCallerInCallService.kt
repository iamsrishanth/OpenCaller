package io.github.iamsrishanth.opencaller.telecom

import android.annotation.SuppressLint
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import java.util.IdentityHashMap

/** Receives and controls calls handed to the user's selected default phone app. */
class OpenCallerInCallService : InCallService() {
    private val callbacks = IdentityHashMap<Call, Call.Callback>()

    @Suppress("NewApi")
    private var availableEndpoints: List<CallEndpoint> = emptyList()

    @Suppress("NewApi")
    private var currentEndpoint: CallEndpoint? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        CallSession.attach(this)
        CallSession.updateCallAudioState(callAudioState)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallSession.add(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = refreshCall(call)
            override fun onDetailsChanged(call: Call, details: Call.Details) = refreshCall(call)
            override fun onParentChanged(call: Call, parent: Call?) = refreshCall(call)
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refreshCall(call)
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        refreshCall(call)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        CallSession.remove(call)?.let { CallNotifications.cancel(this, it) }
        super.onCallRemoved(call)
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallSession.updateCallAudioState(audioState)
    }

    @SuppressLint("NewApi")
    override fun onAvailableCallEndpointsChanged(endpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(endpoints)
        availableEndpoints = endpoints.toList()
        publishEndpointOptions()
    }

    @SuppressLint("NewApi")
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        currentEndpoint = callEndpoint
        publishEndpointOptions()
    }

    internal fun selectAudioRoute(routeId: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            requestEndpointApi34(routeId)
        } else {
            selectLegacyRoute(routeId)
        }
    }

    private fun refreshCall(call: Call) {
        CallSession.refresh(call)
        CallSession.modelFor(call)?.let { CallNotifications.update(this, it) }
    }

    @SuppressLint("NewApi")
    @RequiresApi(34)
    private fun requestEndpointApi34(routeId: String) {
        val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == routeId } ?: return
        requestCallEndpointChange(
            endpoint,
            mainExecutor,
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(error: CallEndpointException) = Unit
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun selectLegacyRoute(routeId: String) {
        val requestedRoute = routeId.removePrefix("legacy:").toIntOrNull() ?: return
        val currentAudio = callAudioState ?: return
        if (currentAudio.supportedRouteMask and requestedRoute == 0) return
        setAudioRoute(requestedRoute)
    }

    @SuppressLint("NewApi")
    private fun publishEndpointOptions() {
        if (Build.VERSION.SDK_INT < 34) return
        val selectedId = currentEndpoint?.identifier?.toString()
        val options = availableEndpoints.map { endpoint ->
            AudioRouteOption(
                id = endpoint.identifier.toString(),
                label = endpoint.endpointName.toString(),
                type = endpoint.endpointType,
                selected = endpoint.identifier.toString() == selectedId,
            )
        }
        CallSession.setAudioRoutes(options)
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        CallSession.detach(this)
        super.onDestroy()
    }
}
