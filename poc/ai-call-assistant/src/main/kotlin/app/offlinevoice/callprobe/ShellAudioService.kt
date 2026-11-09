package app.offlinevoice.callprobe

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// The call-audio approach follows sparkcyf/android-voice-sip-agent (GPL-3.0). Treat this file as
// GPL-3.0 if it is ever distributed.

private const val TAG = "CallAssistantShell"

/** Where push_models.sh leaves the models for the app to import. Readable by the shell user only. */
private const val MODEL_IMPORT_DIR = "/data/local/tmp/ai-call-assistant/models"
private const val SHELL_UID = 2000
private const val SHELL_PACKAGE = "com.android.shell"
private const val METHOD_AUTO = 0
private const val METHOD_REDIRECT = 1
private const val METHOD_DIRECT = 2

/** Hidden values used by the framework's own call-audio helpers. */
private const val USAGE_CALL_ASSISTANT = 17
private const val CALL_REDIRECT_PSTN = 1
private const val CALL_MODE_WAIT_MS = 3000L

/** Audio APIs check who is calling, so the helper must identify as the shell package it runs as. */
private class ShellContext(base: Context) : ContextWrapper(base) {
    private val source = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    override fun getPackageName() = SHELL_PACKAGE
    override fun getOpPackageName() = SHELL_PACKAGE
    override fun getAttributionSource() = source
    override fun getApplicationContext(): Context = this
    override fun getDeviceId() = 0
}

/** Created by Shizuku in a separate process running as the shell user. */
class ShellAudioService(appContext: Context) : IShellProbe.Stub() {
    private val appUid = appContext.applicationInfo.uid
    private val ctx: Context = ShellContext(systemContext() ?: appContext)
    private val audio: AudioManager = AudioManager::class.java
        .getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }
        .newInstance(ctx)
    private var tap: CallTap? = null

    private fun enforceCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid) throw SecurityException("caller uid $caller not allowed")
    }

    override fun destroy() {
        // Runs when Shizuku unbinds. Closing the tap restores call audio before the process exits.
        runCatching { closeTap() }
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun setup(): String {
        enforceCaller()
        val pkg = BuildConfig.APPLICATION_ID
        val appop = run("appops", "set", pkg, "MANAGE_ONGOING_CALLS", "allow")
        // Without this, Telecom never binds our InCallService, so calls are never offered to the AI.
        // It is a plain list that resets on reboot, so clear any stale entries first, then add one.
        repeat(companionOverrides(pkg)) { run("cmd", "telecom", "add-or-remove-call-companion-app", pkg, "0") }
        val companion = run("cmd", "telecom", "add-or-remove-call-companion-app", pkg, "1")
        return "appop=$appop companion=$companion overrides=${companionOverrides(pkg)}"
    }

    override fun undoSetup(): String {
        enforceCaller()
        val pkg = BuildConfig.APPLICATION_ID
        repeat(companionOverrides(pkg)) { run("cmd", "telecom", "add-or-remove-call-companion-app", pkg, "0") }
        return run("appops", "set", pkg, "MANAGE_ONGOING_CALLS", "default")
    }

    /** How many times this package is listed as a call-companion override (Telecom keeps a plain list). */
    private fun companionOverrides(pkg: String): Int =
        run("dumpsys", "telecom").split("override $pkg").size - 1

    override fun capabilities(): String {
        enforceCaller()
        return JSONObject().apply {
            put("uid", Process.myUid())
            put("interceptable", isInterceptable() ?: JSONObject.NULL)
            put("appOp", run("appops", "get", BuildConfig.APPLICATION_ID, "MANAGE_ONGOING_CALLS").trim())
            for (name in listOf("CALL_AUDIO_INTERCEPTION", "CAPTURE_AUDIO_OUTPUT", "MODIFY_PHONE_STATE", "RECORD_AUDIO")) {
                put(name, ctx.checkPermission("android.permission.$name", Process.myPid(), Process.myUid()) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
        }.toString()
    }

    @Synchronized
    override fun open(sampleRate: Int, method: Int): String {
        enforceCaller()
        require(sampleRate == 8000 || sampleRate == 16000) { "unsupported sample rate $sampleRate" }
        require(method in METHOD_AUTO..METHOD_DIRECT) { "unknown method $method" }
        closeTap()
        val opened = CallTap(ctx, audio, sampleRate, method)
        tap = opened
        try {
            opened.open()
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
        return opened.statusJson()
    }

    @Synchronized
    override fun status(): String {
        enforceCaller()
        return tap?.statusJson() ?: JSONObject().put("tap", "none").put("audioMode", audio.mode).toString()
    }

    @Synchronized
    override fun close() {
        enforceCaller()
        closeTap()
    }

    private fun closeTap() {
        tap?.close()
    }

    override fun playSpeech(pcm16k: ByteArray) {
        enforceCaller()
        activeTap().play(pcm16k)
    }

    override fun openCallerStream(): ParcelFileDescriptor {
        enforceCaller()
        return activeTap().openCallerStream()
    }

    override fun isSpeaking(): Boolean {
        enforceCaller()
        return activeTap().isSpeaking
    }

    override fun recordCall(file: ParcelFileDescriptor) {
        enforceCaller()
        activeTap().recordCall(file)
    }

    override fun endCall(): String {
        enforceCaller()
        return run("input", "keyevent", "KEYCODE_ENDCALL")
    }

    override fun listModelImport(): String {
        enforceCaller()
        val root = File(MODEL_IMPORT_DIR)
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .joinToString("\n") { it.relativeTo(root).path }
    }

    override fun openModelImport(path: String): ParcelFileDescriptor {
        enforceCaller()
        val root = File(MODEL_IMPORT_DIR).canonicalFile
        val file = File(root, path).canonicalFile
        // Only files inside the import folder: no "../" escapes to the rest of the shell's storage.
        require(file.path.startsWith(root.path + File.separator) && file.isFile) { "not an importable model file: $path" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun clearModelImport() {
        enforceCaller()
        File(MODEL_IMPORT_DIR).deleteRecursively()
    }

    @Synchronized
    private fun activeTap(): CallTap = tap ?: error("call audio is not open")

    private fun isInterceptable(): Boolean? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
        val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        service.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(service) as Boolean
    }.onFailure { Log.w(TAG, "isPstnCallAudioInterceptable failed: $it") }.getOrNull()

    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()

    /** Runs one fixed command. Arguments are never built from caller input. */
    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "timeout"
        }
        return "exit=${process.exitValue()} $output"
    }
}

/**
 * Moves audio in and out of one call: the caller's voice out through [openCallerStream], the AI's
 * voice in through [play], and a recording of both sides. PCM stays in memory; only levels are kept.
 */
private class CallTap(
    private val ctx: Context,
    private val audio: AudioManager,
    private val rate: Int,
    private val requestedMethod: Int,
) {
    private val frameBytes = rate / 50 * 2

    private lateinit var record: AudioRecord
    private lateinit var track: AudioTrack
    private var method = "none"
    private var failures = ""
    private var redirectModeSet = false
    private var restoreResult = ""

    @Volatile private var running = false
    private var closed = false
    @Volatile private var error: String? = null

    private val speechQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var speech: ByteArray? = null
    private var speechPosition = 0
    val isSpeaking: Boolean get() = speech != null || speechQueue.isNotEmpty()

    @Volatile private var callerStream: FileDescriptor? = null
    @Volatile private var callerDropped = 0L
    private var callerPipeWrite: ParcelFileDescriptor? = null

    @Volatile private var peak = 0
    @Volatile private var downFrames = 0L
    @Volatile private var upFrames = 0L
    @Volatile private var maxDownGapMs = 0L
    @Volatile private var maxUpGapMs = 0L

    fun open() {
        // Telecom reports the call as active slightly before the audio system enters call mode.
        awaitMode(CALL_MODE_WAIT_MS) { isCallMode(it) }
        check(isCallMode(audio.mode)) { "not in a cellular call: audio mode=${audio.mode}" }
        val plan = if (requestedMethod == METHOD_AUTO) listOf(METHOD_REDIRECT, METHOD_DIRECT) else listOf(requestedMethod)
        var opened = false
        for (candidate in plan) {
            try {
                if (candidate == METHOD_REDIRECT) openRedirection() else openDirect()
                opened = true
                break
            } catch (t: Throwable) {
                failures += "$candidate: ${describe(t)}; "
                releaseAudio()
            }
        }
        if (!opened) {
            error = "open failed: $failures"
            throw IllegalStateException(error)
        }
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "record did not start at $rate Hz" }
        track.write(ByteArray(frameBytes * 2), 0, frameBytes * 2, AudioTrack.WRITE_NON_BLOCKING)
        track.play()
        check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "track did not start at $rate Hz" }
        running = true
        thread("call-down", ::downLoop)
        thread("call-up", ::upLoop)
        thread("call-mode", ::watchMode)
        // ponytail: direct method deliberately leaves the earpiece on. Its stream mute can stay
        // stuck if this process dies, which is not worth the risk on a daily phone.
        if (method == "redirection") silenceHandset()
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    private fun format(channel: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(channel).build()

    /**
     * Same objects as AudioManager.getCallDownlinkExtractionAudioRecord and
     * getCallUplinkInjectionAudioTrack, but built with the shell context. Without these exact
     * attributes the record silently uses the mic and the track plays on the phone's speaker.
     */
    @SuppressLint("MissingPermission")
    private fun openRedirection() {
        val int = Int::class.javaPrimitiveType
        val recordAttributes = AudioAttributes.Builder().also {
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", int)
                .invoke(it, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        }.build()
        val recordBuilder = AudioRecord.Builder().setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setContext(ctx)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(recordBuilder, recordAttributes)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(recordBuilder, CALL_REDIRECT_PSTN)
        record = recordBuilder.build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "redirection record not initialized at $rate Hz" }

        val trackAttributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).also {
            AudioAttributes.Builder::class.java.getMethod("setSystemUsage", int).invoke(it, USAGE_CALL_ASSISTANT)
        }.build()
        val trackBuilder = AudioTrack.Builder().setAudioAttributes(trackAttributes)
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO)).setContext(ctx)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(trackBuilder, CALL_REDIRECT_PSTN)
        track = trackBuilder.build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "redirection track not initialized at $rate Hz" }
        method = "redirection"
    }

    @SuppressLint("MissingPermission")
    private fun openDirect() {
        val minRecord = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minRecord > 0) { "record buffer size $minRecord at $rate Hz" }
        record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(maxOf(minRecord, frameBytes * 10)).setContext(ctx).build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "VOICE_DOWNLINK record not initialized at $rate Hz" }

        val minTrack = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minTrack > 0) { "track buffer size $minTrack at $rate Hz" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(maxOf(minTrack, frameBytes * 10))
            .setTransferMode(AudioTrack.MODE_STREAM).setContext(ctx).build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "telephony track not initialized at $rate Hz" }
        val telephony = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: error("TYPE_TELEPHONY output unavailable")
        check(track.setPreferredDevice(telephony)) { "telephony preferred-device request rejected" }
        method = "direct"
    }

    private fun silenceHandset() {
        if (audio.mode != AudioManager.MODE_IN_CALL) return
        redirectModeSet = true
        audio.mode = AudioManager.MODE_CALL_REDIRECT
        awaitMode { it == AudioManager.MODE_CALL_REDIRECT }
    }

    private fun awaitMode(timeoutMs: Long = 1000, done: (Int) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!done(audio.mode) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
    }

    private fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_REDIRECT

    private fun watchMode() {
        while (running) {
            SystemClock.sleep(200)
            if (running && !isCallMode(audio.mode)) {
                error = "cellular audio mode lost: ${audio.mode}"
                close()
            }
        }
    }

    private fun downLoop() {
        val buffer = ByteArray(frameBytes)
        var offset = 0
        var last = SystemClock.elapsedRealtime()
        try {
            while (running) {
                val count = record.read(buffer, offset, frameBytes - offset, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (running) error = "record read $count at $rate Hz"
                    break
                }
                offset += count
                if (offset < frameBytes) continue
                offset = 0
                val now = SystemClock.elapsedRealtime()
                if (now - last > maxDownGapMs) maxDownGapMs = now - last
                last = now
                downFrames++
                trackPeak(buffer)
                sendToCallerStream(buffer)
                recordFrame(callerSide = true, buffer)
            }
        } catch (t: Throwable) {
            if (running) error = "down: ${describe(t)}"
        } finally {
            close()
        }
    }

    private fun trackPeak(frame: ByteArray) {
        for (sample in Audio.bytesToShorts(frame)) {
            val magnitude = kotlin.math.abs(sample.toInt())
            if (magnitude > peak) peak = magnitude
        }
    }

    private fun upLoop() {
        val frame = ByteArray(frameBytes)
        var last = SystemClock.elapsedRealtime()
        try {
            while (running) {
                fillOutput(frame)
                var written = 0
                while (written < frameBytes && running) {
                    val count = track.write(frame, written, frameBytes - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) {
                        if (running) error = "track write $count at $rate Hz"
                        return
                    }
                    written += count
                }
                recordFrame(callerSide = false, frame)
                val now = SystemClock.elapsedRealtime()
                if (now - last > maxUpGapMs) maxUpGapMs = now - last
                last = now
                upFrames++
            }
        } catch (t: Throwable) {
            if (running) error = "up: ${describe(t)}"
        } finally {
            close()
        }
    }

    /** Fills the next uplink frame from queued AI speech, or silence when there's none. */
    private fun fillOutput(frame: ByteArray) {
        frame.fill(0)
        var filled = 0
        while (filled < frameBytes) {
            val current = speech ?: speechQueue.poll()?.also { speech = it } ?: break
            val count = minOf(frameBytes - filled, current.size - speechPosition)
            System.arraycopy(current, speechPosition, frame, filled, count)
            filled += count
            speechPosition += count
            if (speechPosition >= current.size) {
                speechPosition = 0
                // Clear only after the next chunk is visible, so isSpeaking never flickers false.
                speech = speechQueue.poll()
            }
        }
    }

    fun play(pcm16k: ByteArray) {
        check(running) { "call audio is not open" }
        speechQueue.offer(if (rate == 8000) Audio.halveRate(pcm16k) else pcm16k)
    }

    fun openCallerStream(): ParcelFileDescriptor {
        check(running) { "call audio is not open" }
        val (read, write) = ParcelFileDescriptor.createPipe()
        // Never let a slow reader hold up the audio thread: frames are dropped and counted instead.
        Os.fcntlInt(write.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        callerPipeWrite?.close()
        callerPipeWrite = write
        callerStream = write.fileDescriptor
        return read
    }

    private fun sendToCallerStream(frame: ByteArray) {
        val stream = callerStream ?: return
        try {
            Os.write(stream, frame, 0, frameBytes)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) callerDropped++ else callerStream = null
        }
    }

    // ponytail: the recording is held in memory and written when the call ends, capped at 20 minutes
    // (about 77 MB). Stream it to the file as it goes if calls get longer.
    private val recordingLock = Any()
    private var recordingFile: ParcelFileDescriptor? = null
    private var recordedCaller: ByteArrayOutputStream? = null
    private var recordedAi: ByteArrayOutputStream? = null
    private val maxRecordingBytes get() = rate * 2 * 60 * 20

    fun recordCall(file: ParcelFileDescriptor) {
        synchronized(recordingLock) {
            runCatching { recordingFile?.close() }
            recordingFile = file
            recordedCaller = ByteArrayOutputStream()
            recordedAi = ByteArrayOutputStream()
        }
    }

    private fun recordFrame(callerSide: Boolean, frame: ByteArray) {
        synchronized(recordingLock) {
            val into = (if (callerSide) recordedCaller else recordedAi) ?: return
            if (into.size() < maxRecordingBytes) into.write(frame, 0, frameBytes)
        }
    }

    private fun flushRecording() {
        val (file, caller, ai) = synchronized(recordingLock) {
            val taken = Triple(recordingFile, recordedCaller, recordedAi)
            recordingFile = null
            recordedCaller = null
            recordedAi = null
            taken
        }
        if (file == null || caller == null || ai == null) return
        // Written off the audio threads so disk speed can't affect the call.
        Thread {
            runCatching {
                val stereo = Audio.interleave(caller.toByteArray(), ai.toByteArray())
                ParcelFileDescriptor.AutoCloseOutputStream(file).use {
                    it.write(Audio.wavHeader(stereo.size, rate, channels = 2))
                    it.write(stereo)
                }
            }.onFailure { Log.w(TAG, "recording write failed: $it") }
        }.start()
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        running = false
        flushRecording()
        callerStream = null
        runCatching { callerPipeWrite?.close() } // The reader sees end of stream and stops.
        runCatching { record.stop() }
        runCatching { track.pause(); track.flush(); track.stop() }
        releaseAudio()
        if (redirectModeSet) {
            // NORMAL withdraws this process's request and lets Telecom's call mode take over again.
            runCatching {
                audio.mode = AudioManager.MODE_NORMAL
                awaitMode { it != AudioManager.MODE_CALL_REDIRECT }
                restoreResult = "mode=${audio.mode}"
            }.onFailure { restoreResult = "failed: ${describe(it)}" }
        }
        Log.i(TAG, "tap closed: ${statusJson()}")
    }

    private fun releaseAudio() {
        if (::record.isInitialized) runCatching { record.release() }
        if (::track.isInitialized) runCatching { track.release() }
    }

    fun statusJson(): String = JSONObject().apply {
        put("tap", if (running) "open" else "closed")
        put("method", method)
        put("rate", rate)
        put("audioMode", audio.mode)
        put("recordDevice", runCatching { record.routedDevice?.type }.getOrNull() ?: JSONObject.NULL)
        put("trackDevice", runCatching { track.routedDevice?.type }.getOrNull() ?: JSONObject.NULL)
        put("silenced", runCatching { record.activeRecordingConfiguration?.isClientSilenced }.getOrNull() ?: JSONObject.NULL)
        put("downFrames", downFrames)
        put("upFrames", upFrames)
        put("peak", peak)
        put("speaking", isSpeaking)
        put("callerDropped", callerDropped)
        put("maxDownGapMs", maxDownGapMs)
        put("maxUpGapMs", maxUpGapMs)
        put("failures", failures)
        put("restore", restoreResult)
        put("error", error ?: JSONObject.NULL)
    }.toString()

    private fun describe(t: Throwable): String {
        var cause = t
        while (cause is java.lang.reflect.InvocationTargetException && cause.cause != null) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
