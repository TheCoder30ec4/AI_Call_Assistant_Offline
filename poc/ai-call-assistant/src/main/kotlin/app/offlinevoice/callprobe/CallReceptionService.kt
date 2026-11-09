package app.offlinevoice.callprobe

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.telephony.TelephonyManager

/**
 * Sees calls alongside Google Phone, which stays the dialer and the ringing UI. When a call rings
 * unanswered for [Probe.ringDelayMs], the AI picks it up. If you answer first, or hang up, or it's
 * any call the gate refuses, the AI never touches it.
 */
class CallReceptionService : InCallService() {
    private val main = Handler(Looper.getMainLooper())
    private var aiCall: Call? = null
    private var mutedByUs = false

    /** Fires if the call is still ringing after the delay, meaning you didn't pick up. */
    private val answerRunnable = HashMap<Call, Runnable>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call === aiCall && state == Call.STATE_ACTIVE) startAi()
            // You picked up, or the caller gave up, before the AI's timer fired.
            if (call !== aiCall && state != Call.STATE_RINGING) cancelPending(call, "you answered or it ended")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Probe.start(this)
        Probe.onShellLost = {
            Probe.agent?.endCall()
            main.post { restoreMic("helper lost") }
        }
    }

    override fun onCallAdded(call: Call) {
        val refusal = refusalFor(call)
        if (refusal != null) {
            Probe.log("left alone: $refusal")
            return
        }
        call.registerCallback(callCallback)
        val runnable = Runnable { answerIfStillRinging(call) }
        answerRunnable[call] = runnable
        main.postDelayed(runnable, Probe.ringDelayMs)
        Probe.log("ringing; AI will pick up in ${Probe.ringDelaySeconds}s if unanswered")
    }

    private fun answerIfStillRinging(call: Call) {
        answerRunnable.remove(call)
        val refusal = refusalFor(call)
        if (refusal != null) {
            Probe.log("not answering: $refusal")
            return
        }
        Probe.whenConnected(5000) { shell ->
            when {
                shell == null -> Probe.log("not answering: assistant isn't ready, is Shizuku running?")
                refusalFor(call) != null -> Probe.log("not answering: ${refusalFor(call)}")
                else -> {
                    aiCall = call
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    Probe.log("AI answering")
                }
            }
        }
    }

    private fun cancelPending(call: Call, why: String) {
        answerRunnable.remove(call)?.let {
            main.removeCallbacks(it)
            Probe.log("stand down: $why")
        }
        runCatching { call.unregisterCallback(callCallback) }
    }

    private fun refusalFor(call: Call): String? {
        val details = call.details
        return AnswerGate.refusal(
            enabled = Probe.enabled,
            emergency = isEmergency(call, details.handle?.schemeSpecificPart),
            incoming = details.callDirection == Call.Details.DIRECTION_INCOMING,
            ringing = details.state == Call.STATE_RINGING,
            otherCalls = calls.count { it !== call },
        )
    }

    /** Fails safe: if this can't be determined, the call counts as an emergency and is left alone. */
    private fun isEmergency(call: Call, number: String?): Boolean = runCatching {
        val details = call.details
        details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
            details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) ||
            (number != null && getSystemService(TelephonyManager::class.java).isEmergencyNumber(number))
    }.getOrDefault(true)

    private fun startAi() {
        @Suppress("DEPRECATION")
        if (callAudioState?.isMuted == false) {
            setMuted(true) // Keeps the handset mic out of the call on the direct audio method.
            mutedByUs = true
        }
        // Let the AI hang up the call when the caller says goodbye.
        Probe.onHangUp = {
            main.post {
                aiCall?.let { call ->
                    Probe.log("AI hanging up the call")
                    call.disconnect()
                    // Backup: if Telecom ignored the disconnect, press the system end-call key.
                    main.postDelayed({
                        if (aiCall === call) {
                            Probe.log("disconnect ignored; using end-call key")
                            Probe.worker.execute { runCatching { Probe.shell?.endCall() } }
                        }
                    }, 2500)
                }
            }
        }
        val number = aiCall?.details?.handle?.schemeSpecificPart.orEmpty()
        Probe.worker.execute {
            val shell = Probe.shell ?: run { Probe.log("helper lost before audio opened"); return@execute }
            runCatching {
                shell.open(16000, 0)
                val call = Probe.newCall("")
                call.number = number // Private call record only, for the Call back button. Never logged.
                val contact = Probe.contactName(number)
                contact?.let { call.contact = it }
                Probe.log(if (contact != null) "caller is a saved contact" else "caller not in contacts")
                ParcelFileDescriptor.open(
                    call.recording,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { shell.recordCall(it) }
                val agent = Probe.agent
                if (agent != null) {
                    // Its opening line discloses the AI and the recording, and checks a saved contact's name.
                    agent.startCall(shell, call, contact, Probe.contactBias())
                } else {
                    shell.playSpeech(Probe.disclosure())
                    call.summary = "The AI wasn't loaded, so only the recorded disclosure played."
                }
            }.onFailure {
                Probe.log("open failed: ${it.message}")
                main.post { restoreMic("audio open failed") } // Hand the call back to you.
            }
        }
    }

    private fun restoreMic(why: String) {
        if (mutedByUs) {
            setMuted(false)
            mutedByUs = false
            Probe.log("mic mute restored: $why")
        }
    }

    override fun onCallRemoved(call: Call) {
        cancelPending(call, "call ended")
        if (call !== aiCall) return
        runCatching { call.unregisterCallback(callCallback) }
        aiCall = null
        Probe.onHangUp = null
        Probe.agent?.endCall()
        Probe.worker.execute {
            runCatching { Probe.shell?.close() }.onFailure { Probe.log("close failed: ${it.message}") }
            Probe.log("call ended")
        }
        restoreMic("call ended")
    }

    override fun onDestroy() {
        answerRunnable.values.forEach(main::removeCallbacks)
        answerRunnable.clear()
        Probe.onShellLost = null
        super.onDestroy()
    }
}
