package app.offlinevoice.callprobe

import android.os.ParcelFileDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The AI unit: hear the caller, understand them, reply in speech. Everything runs on the phone,
 * and this app has no INTERNET permission.
 *
 * Streaming: the caller's words are transcribed while they speak (shown live), the full text goes
 * to Gemma the moment they stop, Gemma's reply streams out, and each phrase is spoken as soon as
 * it's complete. One Gemma conversation lasts the whole call, so it remembers everything said and
 * never re-reads its instructions.
 */
// ponytail: one turn at a time, and the caller's speech is ignored while the AI talks. Barge-in
// (the caller interrupting) is the next thing to add once replies are fast.
class AiAgent(
    private val modelDir: File,
    private val cacheDir: File,
    ownerName: String,
    /** Google's on-device recognizer for the final transcript; Parakeet is the fallback and live preview. */
    private val google: GoogleAsr? = null,
) {
    private val owner = ownerName.trim()

    /** The caller's name as saved in your contacts, if their number is saved. Set per call. */
    @Volatile private var contact: String? = null

    /** Names the recognizer should expect: you, the caller's contact name, your contacts. */
    @Volatile var bias: List<String> = listOfNotNull(owner.takeIf(String::isNotBlank))

    private lateinit var vad: Vad
    private lateinit var recognizer: OfflineRecognizer
    private lateinit var tts: OfflineTts
    private var ttsRate = 16000
    private lateinit var engine: Engine
    private var speculativeDecoding = true

    /** Runs turns, opening and summary in order, so they never overlap on the conversation. */
    private val worker = Executors.newSingleThreadExecutor()

    /** Transcribes what the caller has said so far, for the live display. */
    private val partialWorker = Executors.newSingleThreadExecutor()
    private val partialRunning = AtomicBoolean(false)

    @Volatile private var conversation: Conversation? = null
    @Volatile private var record: CallRecord? = null
    @Volatile private var active = false
    @Volatile private var busy = false

    /** The caller's words so far in the current sentence, updated as they speak. */
    @Volatile var hearing = ""
        private set

    /** Loads every model. Takes several seconds, so it's done before the call. */
    fun load() {
        val started = now()
        val missing = REQUIRED_FILES.filterNot { File(modelDir, it).exists() }
        check(missing.isEmpty()) { "Model files missing from $modelDir: $missing" }

        vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path("silero_vad.onnx"),
                    threshold = 0.5f,
                    minSilenceDuration = END_OF_SPEECH_SECONDS,
                    minSpeechDuration = 0.25f,
                    maxSpeechDuration = MAX_SPEECH_SECONDS,
                ),
                sampleRate = SAMPLE_RATE,
            ),
        )
        recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("parakeet/encoder.int8.onnx"),
                        decoder = path("parakeet/decoder.int8.onnx"),
                        joiner = path("parakeet/joiner.int8.onnx"),
                    ),
                    tokens = path("parakeet/tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = 4,
                ),
            ),
        )
        val afterAsr = now()

        // The owner's name may not be in the voice's dictionary (Piper silently drops unknown words),
        // so add a phonetic spelling for it before loading the voice.
        val lexicon = NamePhonemes.lexiconWithName(File(modelDir, "piper/lexicon-en-us.txt"), File(cacheDir, "lexicon.txt"), owner)
        tts = OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = path("piper/en_US-hfc_female-medium.onnx"),
                        lexicon = lexicon,
                        tokens = path("piper/tokens.txt"),
                    ),
                    numThreads = 2,
                ),
            ),
        )
        ttsRate = tts.sampleRate() // e.g. 22050 for hfc_female; resampled to the 16 kHz call rate.
        val afterTts = now()

        engine = loadLanguageModel()
        val afterLlm = now()
        warmUp()
        Probe.log(
            JSONObject()
                .put("aiLoaded", "gemma4 + parakeet")
                .put("warmUpMs", now() - afterLlm)
                .put("speechMs", afterAsr - started)
                .put("voiceMs", afterTts - afterAsr)
                .put("llmMs", afterLlm - afterTts)
                .put("rssMb", residentMb())
                .toString(),
        )
    }

    private fun loadLanguageModel(): Engine {
        val model = path(MODEL_FILE)
        val gpu = runCatching {
            Engine(EngineConfig(modelPath = model, backend = Backend.GPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
                .also { it.initialize() }
        }
        gpu.getOrNull()?.let { return it }
        Probe.log("GPU unavailable, using CPU: ${gpu.exceptionOrNull()?.message}")
        return Engine(EngineConfig(modelPath = model, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
            .also { it.initialize() }
    }

    /**
     * The first reply after loading was about 2 seconds slower than the rest, because the GPU sets
     * itself up on first use. Doing one throwaway reply here moves that wait to before the call.
     */
    private fun warmUp() {
        runCatching {
            val scratch = newCallConversation("Hello.")
            try {
                ask(scratch, "Say hi.")
                transcribe(FloatArray(SAMPLE_RATE))
                tts.generate("Hi.", 0, 1.0f)
            } finally {
                scratch.close()
            }
        }.onFailure { Probe.log("warm-up failed: ${it.message}") }
    }

    /** One conversation for the whole call. Its instructions are read in as soon as it's created. */
    private fun newCallConversation(opening: String): Conversation {
        fun create(speculative: Boolean) = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Persona.systemPrompt(owner, contact)),
                initialMessages = listOf(Message.model(opening)),
                prefillPrefaceOnInit = true,
                maxOutputToken = MAX_OUTPUT_TOKENS,
                enableSpeculativeDecoding = speculative,
            ),
        )
        if (speculativeDecoding) {
            val result = runCatching { create(true) }
            result.getOrNull()?.let { return it }
            speculativeDecoding = false
            Probe.log("speculative decoding unavailable: ${result.exceptionOrNull()?.message}")
        }
        return create(false)
    }

    /** Begins the conversation on the current call, opening with the AI and recording disclosure. */
    fun startCall(shell: IShellProbe, call: CallRecord, callerContact: String? = null, contactNames: List<String> = emptyList()) {
        endCall()
        record = call
        contact = callerContact?.trim()?.takeIf(String::isNotBlank)
        bias = (listOfNotNull(owner.takeIf(String::isNotBlank), contact) + contactNames).distinct()
        val stream = shell.openCallerStream()
        speakingProbe = { runCatching { shell.isSpeaking }.getOrDefault(false) }
        vad.reset()
        hearing = ""
        active = true
        busy = true
        val opening = Persona.opening(owner, contact)
        worker.execute {
            runTurn("opening", shell) {
                speakTo(shell, opening)
                call.add("AI", opening)
                // Built while the opening plays, so the first reply doesn't wait for it.
                val started = now()
                conversation = newCallConversation(opening)
                Probe.log(JSONObject().put("conversationReadyMs", now() - started).put("speculative", speculativeDecoding).toString())
            }
        }
        thread(name = "ai-listen", isDaemon = true) { listen(shell, stream) }
    }

    /** Ends the call: stops listening, then writes the summary from the whole conversation. */
    fun endCall() {
        val call = record ?: return
        record = null
        active = false
        hearing = ""
        speakingProbe = null
        google?.reset()
        val knownAs = contact
        runCatching { conversation?.cancelProcess() }
        worker.execute {
            val ending = conversation
            conversation = null
            try {
                if (ending != null && call.callerSpoke()) {
                    val started = now()
                    call.summary = ask(ending, Persona.summaryRequest(owner, knownAs)).ifBlank { "No summary was produced." }
                    Probe.log(JSONObject().put("summaryMs", now() - started).toString())
                } else {
                    call.summary = "The caller didn't say anything."
                }
            } catch (t: Throwable) {
                call.summary = "Summary failed: ${t.message}"
            } finally {
                runCatching { ending?.close() }
                Probe.notifySummary(call)
            }
        }
    }

    private fun listen(shell: IShellProbe, stream: ParcelFileDescriptor) {
        val bytes = ByteArray(640)
        val window = FloatArray(VAD_WINDOW)
        var fill = 0
        var wasBusy = true
        val sentence = FloatBuffer()
        var windowsSincePartial = 0
        try {
            ParcelFileDescriptor.AutoCloseInputStream(stream).use { input ->
                while (active) {
                    val count = input.read(bytes)
                    if (count <= 0) break
                    if (busy) {
                        wasBusy = true
                        fill = 0
                        continue
                    }
                    if (wasBusy) {
                        vad.reset()
                        sentence.clear()
                        wasBusy = false
                    }
                    var i = 0
                    while (i + 1 < count) {
                        val sample = (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)
                        window[fill++] = sample.toShort() / 32768f
                        i += 2
                        if (fill < VAD_WINDOW) continue
                        fill = 0
                        vad.acceptWaveform(window)
                        if (vad.isSpeechDetected()) {
                            sentence.add(window)
                            if (++windowsSincePartial >= PARTIAL_EVERY_WINDOWS) {
                                windowsSincePartial = 0
                                showPartial(sentence.copy())
                            }
                        }
                        while (!vad.empty()) {
                            val speech = vad.front().samples
                            vad.pop()
                            sentence.clear()
                            if (!busy) {
                                busy = true
                                worker.execute { runTurn("turn", shell) { answer(shell, speech) } }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (active) Probe.log("listening stopped: ${t.message}")
        }
        Probe.log("listening ended")
    }

    /** Transcribes the sentence so far in the background, skipping if the last one is still running. */
    private fun showPartial(soFar: FloatArray) {
        if (!partialRunning.compareAndSet(false, true)) return
        partialWorker.execute {
            try {
                if (active && !busy) transcribe(soFar)?.let { hearing = it }
            } catch (_: Throwable) {
                // The final transcript is what counts; a failed preview is just skipped.
            } finally {
                partialRunning.set(false)
            }
        }
    }

    /** Runs one turn and always returns to listening, so an error can't leave the AI deaf. */
    private fun runTurn(name: String, shell: IShellProbe, body: () -> Unit) {
        try {
            if (active) body()
            if (active) awaitSilence(shell)
        } catch (t: Throwable) {
            if (active) Probe.log("$name failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            busy = false
        }
    }

    private fun answer(shell: IShellProbe, speech: FloatArray) {
        val timing = JSONObject().put("speechSeconds", round(speech.size.toDouble() / SAMPLE_RATE))
        val start = now()
        replyTo(speech, timing, start) { phrase -> speakTo(shell, phrase) }
        Probe.log(timing.put("rssMb", residentMb()).toString())
    }

    fun transcribe(speech: FloatArray): String? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(Audio.cleanForAsr(speech), SAMPLE_RATE)
            recognizer.decode(stream)
            return Transcript.clean(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    /**
     * The transcript the AI acts on. Google's recognizer (Indian English, biased toward expected
     * names) first; Parakeet if it's unavailable or fails, so a sentence is never lost.
     */
    fun transcribeFinal(speech: FloatArray, timing: JSONObject? = null): String? {
        val asr = google?.takeIf { it.available }
        if (asr != null) {
            val text = asr.transcribe(Audio.cleanForAsr(speech), bias)
            if (text != null) {
                timing?.put("asr", "google")
                return Transcript.clean(text)
            }
        }
        timing?.put("asr", "parakeet")
        return transcribe(speech)
    }

    /** Splits a long stretch of audio into sentences, as the live call does. Not for use during a call. */
    fun speechSegments(samples: FloatArray): List<FloatArray> {
        check(!active) { "the VAD is in use by a call" }
        vad.reset()
        val out = mutableListOf<FloatArray>()
        var i = 0
        while (i + VAD_WINDOW <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + VAD_WINDOW))
            while (!vad.empty()) { out += vad.front().samples; vad.pop() }
            i += VAD_WINDOW
        }
        vad.flush()
        while (!vad.empty()) { out += vad.front().samples; vad.pop() }
        vad.reset()
        return out
    }

    /** Transcribes one sentence, then streams Gemma's reply out phrase by phrase, timing each stage. */
    private fun replyTo(speech: FloatArray, timing: JSONObject, start: Long, speak: (String) -> Unit) {
        val heard = transcribeFinal(speech, timing)
        timing.put("asrMs", now() - start)
        hearing = ""
        if (heard == null) {
            timing.put("skipped", "no words")
            return
        }
        record?.add("Caller", heard)
        val turn = conversation ?: error("conversation not ready yet")

        val splitter = PhraseSplitter()
        val spoken = StringBuilder()
        var phrases = 0
        fun say(phrase: String) {
            if (phrases == 0) timing.put("firstPhraseMs", now() - start)
            speak(phrase)
            if (phrases == 0) timing.put("firstAudioMs", now() - start)
            phrases++
            spoken.append(phrase).append(' ')
        }

        runBlocking {
            turn.sendMessageAsync(heard, NO_THINKING).collect { message ->
                if (!timing.has("firstTokenMs")) timing.put("firstTokenMs", now() - start)
                splitter.add(message.toString()).forEach(::say)
            }
        }
        splitter.finish()?.let(::say)
        if (phrases == 0) {
            // Never leave the caller in silence: the model can finish without producing any words.
            timing.put("emptyReply", true)
            say(EMPTY_REPLY)
        }
        timing.put("replyMs", now() - start).put("phrases", phrases)
        val reply = spoken.toString().trim()
        record?.add("AI", reply)

        // If the caller is wrapping up, end the call after the AI's goodbye has finished playing,
        // so the caller doesn't have to hang up themselves.
        if (Goodbye.callerIsLeaving(heard) || Goodbye.aiSaidGoodbye(reply)) {
            Probe.log("caller said goodbye; hanging up after the AI finishes")
            awaitSpeechDone()
            Probe.hangUp()
        }
    }

    /** Blocks until the shell has finished speaking the queued audio. Used before hanging up. */
    private var speakingProbe: (() -> Boolean)? = null
    private fun awaitSpeechDone() {
        val isSpeaking = speakingProbe ?: return
        val deadline = now() + 8000 // Safety cap so a stuck queue can't hold the line open.
        while (active && isSpeaking() && now() < deadline) Thread.sleep(50)
        Thread.sleep(400) // Let the last words clear the line before disconnecting.
    }

    private fun ask(turn: Conversation, request: String): String {
        val text = StringBuilder()
        runBlocking { turn.sendMessageAsync(request, NO_THINKING).collect { text.append(it.toString()) } }
        return text.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    private fun speakTo(shell: IShellProbe, phrase: String) {
        val samples = tts.generate(phrase, 0, 1.0f).samples
        // The call runs at 16 kHz; the voice may be 22.05 kHz, so resample before injecting.
        val pcm = Audio.floatsToPcm(Audio.resample(samples, ttsRate, SAMPLE_RATE))
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + MAX_BINDER_BYTES, pcm.size)
            shell.playSpeech(pcm.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun awaitSilence(shell: IShellProbe) {
        while (active && shell.isSpeaking) Thread.sleep(50)
        Thread.sleep(250) // Lets the last audio clear the call before listening again.
    }

    /** Runs a whole conversation with synthetic speech and no call, ending with a summary. */
    fun selfTest(questions: List<String>, call: CallRecord) {
        val opening = Persona.opening(owner)
        call.add("AI", opening)
        val started = now()
        val turn = newCallConversation(opening)
        conversation = turn
        record = call
        Probe.log(JSONObject().put("conversationReadyMs", now() - started).put("speculative", speculativeDecoding).toString())
        try {
            for (question in questions) {
                val timing = JSONObject().put("selfTest", true)
                val speech = Audio.resample(tts.generate(question, 0, 1.0f).samples, ttsRate, SAMPLE_RATE)
                val start = now()
                replyTo(speech, timing, start) { phrase -> tts.generate(phrase, 0, 1.0f) }
                Probe.log(timing.put("rssMb", residentMb()).toString())
            }
            val summaryStart = now()
            call.summary = ask(turn, Persona.summaryRequest(owner))
            Probe.log(JSONObject().put("summaryMs", now() - summaryStart).toString())
            Probe.notifySummary(call)
        } finally {
            record = null
            conversation = null
            runCatching { turn.close() }
        }
    }

    private fun path(name: String) = File(modelDir, name).path

    private fun now() = System.nanoTime() / 1_000_000

    private fun round(value: Double) = Math.round(value * 100) / 100.0

    private fun residentMb(): Long = runCatching {
        File("/proc/self/statm").readText().split(" ")[1].toLong() * 4096 / (1024 * 1024)
    }.getOrDefault(-1)

    companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512
        const val END_OF_SPEECH_SECONDS = 0.5f
        const val MAX_SPEECH_SECONDS = 15f
        const val PARTIAL_EVERY_WINDOWS = 20 // About 0.6 seconds of speech.
        const val MAX_TOKENS = 4096 // The whole call has to fit, since nothing is forgotten mid-call.
        const val MAX_OUTPUT_TOKENS = 160
        const val MAX_BINDER_BYTES = 200_000
        const val MODEL_FILE = "gemma4.litertlm"
        const val EMPTY_REPLY = "Sorry, I didn't catch that. Could you say it again?"
        val NO_THINKING = mapOf<String, Any>("enable_thinking" to false)
        val REQUIRED_FILES = listOf(
            "silero_vad.onnx",
            "parakeet/encoder.int8.onnx",
            "parakeet/decoder.int8.onnx",
            "parakeet/joiner.int8.onnx",
            "parakeet/tokens.txt",
            "piper/en_US-hfc_female-medium.onnx",
            "piper/lexicon-en-us.txt",
            "piper/tokens.txt",
            MODEL_FILE,
        )
    }
}

/** A growable float buffer, so each window doesn't allocate a new list. */
private class FloatBuffer {
    private var data = FloatArray(16000)
    private var size = 0
    fun add(values: FloatArray) {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        System.arraycopy(values, 0, data, size, values.size)
        size += values.size
    }
    fun copy(): FloatArray = data.copyOf(size)
    fun clear() {
        size = 0
    }
}

/** How the assistant talks. Kept apart from the plumbing so it's easy to tune. */
object Persona {
    /** The first sentence of every call: discloses the AI and recording, and asks who's calling. */
    fun opening(owner: String, contact: String? = null): String {
        val whose = if (owner.isBlank()) "the owner of this phone" else owner
        // A saved contact is probably who's calling, but someone else may have their phone: confirm.
        val ask = contact?.let { "Is this ${firstName(it)}?" } ?: "May I ask who's calling?"
        return "Hi, you've reached $whose's AI assistant, and this call is recorded. " +
            "$whose can't get to the phone right now. $ask"
    }

    private fun firstName(contact: String) = contact.trim().split(" ").first()

    fun systemPrompt(owner: String, contact: String? = null): String {
        val name = owner.ifBlank { "the owner" }
        val callerId = if (contact != null) {
            "\n\nCaller ID: this number is saved in $name's contacts as \"$contact\". You opened by asking if it's ${firstName(contact)}. " +
                "If they say yes, use that name and spell it exactly like that; don't ask for their name again. " +
                "If they say no, ask who's calling."
        } else {
            ""
        }
        return """
            You are $name's personal AI assistant, answering $name's phone because $name can't pick up. This is a live phone call; the caller hears everything you say, spoken aloud by a text-to-speech voice.

            How you speak:
            - Warm, friendly and natural, like a helpful receptionist.
            - One short sentence per reply, two at most. Around fifteen words.
            - Plain spoken English with contractions (I'm, they're, don't). No lists, symbols or formatting.
            - Say the caller's name once you know it, and refer to the owner as "$name".
            - Never use stiff words like assist, provide, kindly, regarding or "how may I help".

            Your goal, in order:
            1. Get the caller's name. If you don't have it yet, politely ask ("May I ask who's calling?").
            2. Once you know their name, greet them by it and ask what the call is about ("Thanks, [name]. What's this about?").
            3. Take their message. Repeat the key point back in your own words to confirm, and say you'll let $name know.
            4. If they give a phone number, read it back digit by digit to check it.
            5. If asked something about $name's plans, schedule or whereabouts: you genuinely don't know and can't check or promise anything, so say so and offer to pass on a message.
            6. When they're done or say goodbye, give a short friendly goodbye — the call ends automatically after that, so don't keep talking.

            Important:
            - You are talking TO the caller. Messages are for $name, never for the caller.
            - Only ask one thing at a time. Don't repeat a question they've already answered.
            - If you didn't catch a name or number, ask them to say it again.

            Understanding the caller:
            - Most callers are in India. They speak Indian English and often mix in Hindi or Telugu words (for example "haan", "nahi", "acha", "bhai", "anna", "akka", "ledu", "cheppu", "jaldi", "paisa"). Understand those words, but always reply in simple English.
            - What the caller says reaches you through speech recognition on a phone line, so words can be misheard, especially names, numbers and Indian words. Read past obvious mistakes using the context of the whole call.
            - Any word that sounds like "$name" (for example with a different first letter or vowel) means $name.
            - The caller may be $name's family or friend. If they say "my brother", "my son", "my friend" and then name $name, they mean $name, not someone else. Keep track of who is who.
            - If the caller's name sounds unusual or unclear, check it once: "Sorry, was that [name]?" Once confirmed, keep using exactly that spelling.
            - Numbers may come as words ("nine eight four") or digits; keep them as digits.$callerId
        """.trimIndent()
    }

    fun summaryRequest(owner: String, contact: String? = null): String {
        val name = owner.ifBlank { "the owner" }
        val saved = contact?.let { "The caller's number is saved in $name's contacts as \"$it\"; if they confirmed that's them, use that exact name. " }.orEmpty()
        return "The call has ended. Write a summary for $name using only what the caller actually said. $saved" +
            "Hindi or Telugu words the caller used should be translated into English. Speech " +
            "recognition may have misheard words; use the whole conversation to work out what they meant. Anyone the " +
            "caller describes who is really $name (for example \"my brother $name\") is $name; say so, e.g. " +
            "\"Your brother\". Write to $name as \"you\".\n" +
            "Reply with exactly these five lines and nothing else, each starting with the label shown:\n" +
            "Caller: their name, plus how they know $name if they said (e.g. \"Ravi, your brother\"). Write unknown if not given.\n" +
            "Reason: why they called, in under ten words.\n" +
            "Message: one or two sentences: what they want you to know or do.\n" +
            "Callback: any phone number as digits exactly as given, and any time. Write none if not given.\n" +
            "Urgency: exactly one word, HIGH, MEDIUM or LOW, based on how urgent the caller made it sound."
    }
}

/** Decides when the caller is ending the call, so the AI can hang up for them. */
object Goodbye {
    // Clear "I'm done / end the call" cues, and plain farewells.
    private val phrases = listOf(
        "bye", "goodbye", "good bye", "see you", "talk later", "talk to you later", "take care",
        "that's all", "that's it", "that is all", "nothing else", "i'm done", "im done", "we're done",
        "cut the call", "end the call", "hang up", "disconnect", "gotta go", "have to go", "i'll go",
        "thanks bye", "ok bye", "okay bye", "thank you bye",
    )

    // Explicit "end it now" requests, matched anywhere in the sentence.
    private val requests = listOf("hang up", "hangup", "disconnect", "that's all", "nothing else")

    // "Cut the call" as speech recognition actually hears it on a phone line: "cut the corn",
    // "cut the kal", "end the col", "cut this line"...
    private val endRequest = Regex("\\b(cut|end|close|drop|stop)\\s+(the|this|my)\\s+(c|k)\\w*|\\b(cut|end)\\s+(the|this)\\s+(line|phone|conversation)")

    // How the AI's own reply ends when it is saying goodbye. It's told the call ends after that.
    private val farewell = Regex(
        "\\b(good ?bye|bye|take care|talk soon|thanks for calling|thank you for calling|" +
            "have a (good|great|nice|lovely|wonderful) (day|evening|night|one|weekend|afternoon))\\b",
    )

    fun callerIsLeaving(said: String): Boolean {
        val text = said.lowercase().trim().trimEnd('.', '!', '?', ' ')
        if (text.isEmpty()) return false
        if (requests.any { text.contains(it) } || endRequest.containsMatchIn(text)) return true
        // A farewell at the END of the message ("ok thanks bye"), or the whole message being one.
        // Not a prefix, so "goodbye party tomorrow" doesn't count.
        return phrases.any { text == it || text.endsWith(" $it") }
    }

    /** True when the AI's reply finishes with a farewell, so the conversation is over. */
    fun aiSaidGoodbye(reply: String): Boolean {
        val last = reply.lowercase().split('.', '!', '?').lastOrNull { it.isNotBlank() } ?: return false
        return farewell.containsMatchIn(last)
    }
}

object Transcript {
    /** Removes the recogniser's non-speech markers such as [BLANK_AUDIO]. Returns null if no words remain. */
    fun clean(raw: String): String? {
        val text = raw.replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
        return text.takeIf { value -> value.count(Char::isLetter) >= 2 }
    }
}
