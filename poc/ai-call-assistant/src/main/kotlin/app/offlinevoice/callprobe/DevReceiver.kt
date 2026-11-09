package app.offlinevoice.callprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Developer commands, sent from the computer with `adb shell am broadcast`. Protected by the DUMP
 * permission, which only the adb shell holds, so no other app can trigger them. Results go to the
 * app's private storage.
 *
 *   am broadcast -n app.offlinevoice.callprobe/.DevReceiver -a app.offlinevoice.callprobe.ASR_PROBE
 *   am broadcast -n app.offlinevoice.callprobe/.DevReceiver -a app.offlinevoice.callprobe.ASR_COMPARE --ei calls 3
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return // Developer commands exist only in debug builds.
        Probe.start(context)
        when (intent.action) {
            "app.offlinevoice.callprobe.ASR_PROBE" -> probe()
            "app.offlinevoice.callprobe.ASR_COMPARE" -> compare(context, intent.getIntExtra("calls", 3))
        }
    }

    /** Tries the Google recognizer on one recorded sentence with different settings. */
    private fun probe() = Probe.loadAi { agent ->
        runCatching {
            val call = Probe.recentCalls(2).last()
            val sentence = agent.speechSegments(Audio.leftChannel(call.recording.readBytes()))[1]
            for (realtime in listOf(false, true)) for (language in listOf("en-IN", "en-US")) {
                val text = Probe.google.transcribe(Audio.cleanForAsr(sentence), emptyList(), language, realtime = realtime)
                Probe.log("asrprobe $language realtime=$realtime: ${text != null}")
            }
        }.onFailure { Probe.log("asrprobe failed: ${it.javaClass.simpleName}: ${it.message}") }
        Probe.log("asrprobe done")
    }

    /** Parakeet vs Google (Indian English, Hindi) on the caller side of recent recordings. */
    private fun compare(context: Context, calls: Int) {
        Probe.prepareSpeechPacks()
        Probe.loadAi { agent ->
            runCatching {
                val bias = (listOf(Probe.ownerName) + Probe.contactBias()).filter(String::isNotBlank)
                val out = StringBuilder("bias: ${bias.size} names, google available: ${Probe.google.available}\n")
                for (call in Probe.recentCalls(calls)) {
                    out.append("\n=== call ${call.dir.name}\n")
                    val caller = Audio.leftChannel(call.recording.readBytes())
                    for ((i, sentence) in agent.speechSegments(caller).withIndex()) {
                        val t0 = System.nanoTime()
                        val parakeet = agent.transcribe(sentence)
                        val t1 = System.nanoTime()
                        val english = Probe.google.transcribe(Audio.cleanForAsr(sentence), bias)
                        val t2 = System.nanoTime()
                        val hindi = Probe.google.transcribe(Audio.cleanForAsr(sentence), bias, language = "hi-IN")
                        val t3 = System.nanoTime()
                        out.append("#$i ${"%.1f".format(sentence.size / 16000f)}s\n")
                        out.append("  parakeet (${(t1 - t0) / 1_000_000}ms): $parakeet\n")
                        out.append("  en-IN    (${(t2 - t1) / 1_000_000}ms): $english\n")
                        out.append("  hi-IN    (${(t3 - t2) / 1_000_000}ms): $hindi\n")
                    }
                }
                File(context.filesDir, "asr-compare.txt").writeText(out.toString())
            }.onFailure { Probe.log("asr compare failed: ${it.javaClass.simpleName}: ${it.message}") }
            Probe.log("asr compare finished")
        }
    }
}
