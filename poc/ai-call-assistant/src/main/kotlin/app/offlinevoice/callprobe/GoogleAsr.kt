package app.offlinevoice.callprobe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Google's on-device speech recognizer (on Pixel: Android System Intelligence, which runs in the
 * isolated Private Compute Core). We feed it the call audio ourselves, so it never opens the mic, and
 * it runs offline. Chosen over Parakeet for Indian English, and because it can be biased toward the
 * names we expect (the owner, the caller's contact name, your contacts).
 */
class GoogleAsr(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Reports which of [languages] are installed, and starts a download for any that are supported
     * but missing. [done] gets a short human-readable status per language.
     */
    fun prepareLanguages(languages: List<String>, done: (Map<String, String>) -> Unit) {
        main.post {
            val checker = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrElse {
                done(languages.associateWith { "recognizer unavailable" })
                return@post
            }
            checker.checkRecognitionSupport(
                intentFor(languages.first()),
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.map(::norm)
                        val pending = support.pendingOnDeviceLanguages.map(::norm)
                        val supported = support.supportedOnDeviceLanguages.map(::norm)
                        Probe.log("on-device speech: installed=$installed pending=$pending supported=${supported.size}")
                        val status = languages.associateWith { language ->
                            when (norm(language)) {
                                in installed -> "installed"
                                in pending -> "downloading"
                                in supported -> "downloading".also { download(language) }
                                else -> "not offered on this phone"
                            }
                        }
                        main.post { checker.destroy() }
                        done(status)
                    }

                    override fun onError(error: Int) {
                        main.post { checker.destroy() }
                        done(languages.associateWith { "check failed ($error)" })
                    }
                },
            )
        }
    }

    /** Languages with a download in flight, so overlapping checks don't start a second one. */
    private val downloading = HashSet<String>()

    private fun download(language: String) = main.post {
        if (!downloading.add(language)) return@post
        runCatching {
            val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            downloader.triggerModelDownload(
                intentFor(language),
                executor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() = finish("downloaded")
                    override fun onScheduled() = finish("download scheduled (needs Wi-Fi/charging)")
                    override fun onError(error: Int) = finish("download failed ($error)")
                    private fun finish(what: String) {
                        Probe.log("speech pack $language: $what")
                        main.post {
                            downloading.remove(language)
                            downloader.destroy()
                        }
                    }
                },
            )
        }.onFailure {
            downloading.remove(language)
            Probe.log("speech pack $language: ${it.message}")
        }
    }

    /**
     * Transcribes one sentence of caller audio (16 kHz mono, -1..1). Blocks the calling thread, which
     * must not be the main thread. Returns null on error or timeout, so the caller can fall back.
     */
    fun transcribe(
        samples: FloatArray,
        bias: List<String>,
        language: String = "en-IN",
        timeoutMs: Long = 5000,
        realtime: Boolean = false,
    ): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "transcribe blocks; call it off the main thread" }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val done = CountDownLatch(1)
        var text: String? = null
        var failure: Int? = null
        val events = StringBuffer()
        var maxRms = -100f
        var lastPartial: String? = null
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val all = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                // Never the words themselves: the report holds measurements only.
                events.append("results(${all?.size}, partial=${lastPartial != null});")
                // With our own audio source, the final result arrives empty when the stream ends; the
                // last partial holds the full transcript, so use that.
                text = all?.firstOrNull()?.takeIf(String::isNotBlank) ?: lastPartial
                done.countDown()
            }

            override fun onError(error: Int) {
                events.append("error($error);")
                failure = error
                done.countDown()
            }

            override fun onReadyForSpeech(params: Bundle?) { events.append("ready;") }
            override fun onBeginningOfSpeech() { events.append("begin;") }
            override fun onRmsChanged(rmsdB: Float) { if (rmsdB > maxRms) maxRms = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { events.append("end;") }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf(String::isNotBlank)?.let { lastPartial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) { events.append("event($eventType);") }
        }
        val intent = intentFor(language).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            if (bias.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
        }
        main.post {
            runCatching {
                val current = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
                current.setRecognitionListener(listener)
                current.startListening(intent)
            }.onFailure {
                failure = -1
                done.countDown()
            }
        }
        // Feed the sentence, then a little silence so the recognizer knows it has ended, then EOF.
        thread(name = "asr-feed", isDaemon = true) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    val pcm = Audio.floatsToPcm(samples) + ByteArray(16000) // Plus 0.5 s of silence.
                    if (!realtime) {
                        out.write(pcm)
                    } else {
                        // 100 ms chunks at the pace a live call delivers them.
                        var offset = 0
                        while (offset < pcm.size) {
                            val end = minOf(offset + 3200, pcm.size)
                            out.write(pcm, offset, end - offset)
                            offset = end
                            Thread.sleep(100)
                        }
                    }
                }
            }.onFailure { events.append("feed failed: ${it.message};") }
        }
        val finished = done.await(timeoutMs + if (realtime) samples.size / 16L else 0L, TimeUnit.MILLISECONDS)
        runCatching { read.close() }
        if (!finished || failure != null || text == null) {
            Probe.log("google asr ${if (!finished) "timed out" else "no text"} [$events maxRms=$maxRms]; using fallback")
            reset()
            return null
        }
        return text
    }

    /** Drops the recognizer so the next sentence starts on a fresh one (after errors, or at call end). */
    fun reset() = main.post {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun intentFor(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    private fun norm(language: String) = language.replace('_', '-').lowercase()

    companion object {
        /** Indian English for the conversation, plus Hindi and Telugu packs. */
        val LANGUAGES = listOf("en-IN", "hi-IN", "te-IN")
    }
}
