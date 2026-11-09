package app.offlinevoice.callprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLogicTest {
    private fun refusal(
        enabled: Boolean = true,
        emergency: Boolean = false,
        incoming: Boolean = true,
        ringing: Boolean = true,
        otherCalls: Int = 0,
    ) = AnswerGate.refusal(enabled, emergency, incoming, ringing, otherCalls)

    @Test fun answersAnUnansweredIncomingCallWhenEnabled() = assertNull(refusal())

    @Test fun leavesEveryOtherCallAlone() {
        assertEquals("assistant is off", refusal(enabled = false))
        assertEquals("emergency call", refusal(emergency = true))
        assertEquals("not an incoming call", refusal(incoming = false))
        assertEquals("not ringing", refusal(ringing = false)) // You already answered, or it ended.
        assertEquals("another call is in progress", refusal(otherCalls = 1))
    }

    @Test fun emergencyWinsEvenWhenEnabled() {
        assertEquals("emergency call", refusal(emergency = true, otherCalls = 0))
    }

    private fun split(vararg chunks: String): List<String> {
        val splitter = PhraseSplitter()
        val phrases = chunks.flatMap { splitter.add(it) }.toMutableList()
        splitter.finish()?.let { phrases += it }
        return phrases
    }

    @Test fun splitsStreamedTextIntoPhrases() {
        assertEquals(
            listOf("Hello there.", "How can I help?", "Take care"),
            split("Hel", "lo there. How ", "can I help? Take", " care"),
        )
    }

    @Test fun neverSpeaksThinking() {
        assertEquals(listOf("Yes."), split("<think>", "\nHe wants. A reply, now.\n", "</think>", "\n\nYes."))
        assertEquals(listOf("Yes."), split("</think>\n\nYes."))
        assertEquals(emptyList<String>(), split("<think>Never finished. Thinking"))
    }

    @Test fun keepsNumbersAndShortPhrasesTogether() {
        assertEquals(listOf("It costs 3.50 dollars."), split("It costs 3.50 dollars."))
        assertEquals(listOf("Yes, of course."), split("Yes, of course."))
        assertEquals(listOf("When you get back,", "call me."), split("When you get back, call me."))
    }

    @Test fun openingDisclosesTheAiAndTheRecording() {
        for (owner in listOf("", "Asha")) {
            val opening = Persona.opening(owner)
            assertTrue(opening, opening.contains("AI assistant"))
            assertTrue(opening, opening.contains("recorded"))
        }
        assertTrue(Persona.opening("Asha").contains("Asha's AI assistant"))
        // A saved contact is confirmed by first name, still with the full disclosure.
        val known = Persona.opening("Varun", "Ravi Kumar")
        assertTrue(known, known.endsWith("Is this Ravi?") && known.contains("recorded"))
        assertTrue(Persona.systemPrompt("Varun", "Ravi Kumar").contains("\"Ravi Kumar\""))
    }

    @Test fun cleansRecogniserMarkers() {
        assertEquals("Hello there", Transcript.clean(" [BLANK_AUDIO] Hello  there (coughs) "))
        assertNull(Transcript.clean("[BLANK_AUDIO]"))
        assertNull(Transcript.clean(" . "))
    }

    @Test fun generatedSpeechClipsInsteadOfWrapping() {
        val pcm = Audio.bytesToShorts(Audio.floatsToPcm(floatArrayOf(2f, -2f, 0f)))
        assertEquals(32767, pcm[0].toInt())
        assertEquals(-32767, pcm[1].toInt())
        assertEquals(0, pcm[2].toInt())
    }

    @Test fun stereoRecordingKeepsEachSideOnItsOwnChannel() {
        val caller = byteArrayOf(1, 0, 2, 0)
        val ai = byteArrayOf(9, 0)
        val stereo = Audio.bytesToShorts(Audio.interleave(caller, ai)).map { it.toInt() }
        assertEquals(listOf(1, 9, 2, 0), stereo) // The shorter side is padded with silence.
        val header = Audio.bytesToShorts(Audio.wavHeader(8, 16000, channels = 2))
        assertEquals(2, header[11].toInt()) // Channel count.
        assertEquals(4, header[16].toInt()) // Bytes per stereo frame.
    }

    @Test fun cleanForAsrRemovesDcAndLiftsQuietAudio() {
        // A quiet 400 Hz tone riding on a +0.3 DC offset: the kind of baseline a phone line adds.
        val n = 1600
        val dirty = FloatArray(n) { 0.3f + 0.05f * kotlin.math.sin(2 * Math.PI * 400 * it / 16000).toFloat() }
        val clean = Audio.cleanForAsr(dirty)
        // DC gone: the cleaned signal swings around zero, not around 0.3.
        val mean = clean.average().toFloat()
        assertTrue("mean $mean should be near zero", kotlin.math.abs(mean) < 0.02f)
        // Quiet tone lifted toward full scale.
        val peak = clean.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak should be lifted near 0.9", peak > 0.6f)
    }

    @Test fun cleanForAsrLeavesNearSilenceAlone() {
        // Near-silence must not be amplified into noise.
        val quiet = FloatArray(800) { 0.005f }
        val out = Audio.cleanForAsr(quiet)
        assertTrue(out.maxOf { kotlin.math.abs(it) } < 0.02f)
        assertTrue(Audio.cleanForAsr(FloatArray(0)).isEmpty())
    }

    @Test fun namePhonemesProduceSomethingSpeakable() {
        // Not checking exact IPA, just that known-problem names yield non-empty, stressed output.
        for (name in listOf("varun", "priya", "chandra")) {
            val p = NamePhonemes.phonemes(name)
            assertTrue("$name -> $p", p.contains("ˈ") && p.trim().length > 2)
        }
    }

    @Test fun lexiconUnchangedForBlankOrKnownName() {
        val lex = kotlin.io.path.createTempFile().toFile()
        lex.writeText("ravi || ɹ ɑː v iː\n")
        val out = kotlin.io.path.createTempFile().toFile()
        try {
            // Blank name: original returned.
            assertEquals(lex.path, NamePhonemes.lexiconWithName(lex, out, ""))
            // Known name already in the lexicon: original returned.
            assertEquals(lex.path, NamePhonemes.lexiconWithName(lex, out, "Ravi"))
            // New name: a new file that contains it.
            val result = NamePhonemes.lexiconWithName(lex, out, "Varun")
            assertEquals(out.path, result)
            assertTrue(out.readText().contains("varun ||"))
        } finally {
            lex.delete(); out.delete()
        }
    }

    @Test fun detectsWhenTheCallerIsLeaving() {
        for (said in listOf("Okay, thanks. Bye.", "bye", "that's all, thank you", "You can cut the call now", "hang up please", "Alright, gotta go")) {
            assertTrue(said, Goodbye.callerIsLeaving(said))
        }
    }

    @Test fun doesNotHangUpMidConversation() {
        for (said in listOf("Hi, who is this?", "Can you tell him the payment is due?", "My number is nine eight four", "goodbye party is tomorrow", "")) {
            assertFalse(said, Goodbye.callerIsLeaving(said))
        }
    }

    @Test fun hearsCutTheCallEvenWhenMisrecognised() {
        // "Okay, cut the corn" is what the recogniser produced on a real call for "okay, cut the call".
        for (said in listOf("Okay, cut the corn", "cut the call", "please end the call", "you can cut this kal", "end the conversation")) {
            assertTrue(said, Goodbye.callerIsLeaving(said))
        }
    }

    @Test fun hangsUpAfterTheAiSaysGoodbye() {
        for (reply in listOf("Thanks for calling, Apoll. Have a good day.", "Okay, I'll tell Varun. Bye!", "Take care.")) {
            assertTrue(reply, Goodbye.aiSaidGoodbye(reply))
        }
        // A thank-you that carries on into a question is not the end.
        for (reply in listOf("Thanks for calling, Ravi. What's this about?", "I'll let Varun know right away.", "")) {
            assertFalse(reply, Goodbye.aiSaidGoodbye(reply))
        }
    }

    @Test fun summaryIsParsedIntoFixedFieldsWhateverTheModelCalledThem() {
        val memo = Memo.parse("Name: Apoll\nReason: Brother owes money.\nMessage: Ask brother to call back right now.\nCallback: None\nUrgency: HIGH")
        assertEquals("Apoll", memo.caller)
        assertEquals("Brother owes money", memo.reason)
        assertEquals("Ask brother to call back right now", memo.message)
        assertNull(memo.callback) // "None" is not a number to show.
        val withNumber = Memo.parse("**Caller:** Ravi, your brother\nCallback: 98765 43210 after 6 pm\nMessage: No rush, tomorrow is fine.")
        assertEquals("Ravi, your brother", withNumber.caller)
        assertEquals("9876543210", withNumber.callbackDigits)
        assertEquals("No rush, tomorrow is fine", withNumber.message) // Real content starting with "No" is kept.
        // Free text (no labels) becomes the message, so nothing is lost.
        assertEquals("The caller didn't say anything.", Memo.parse("The caller didn't say anything.").message)
    }

    @Test fun callerNameSplitsIntoNameAndRelation() {
        assertEquals(Who("Ravi", "Your brother", "R"), Who.of("Ravi, your brother"))
        assertEquals(Who("Sarah Lin", null, "SL"), Who.of("Sarah Lin"))
        assertEquals(Who("Unknown caller", null, "?"), Who.of(null))
    }

    @Test fun callRecordKeepsTranscriptAndSummary() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val call = CallLog.newCall(root, "test")
            assertFalse(call.callerSpoke())
            call.add("AI", "Who's calling?")
            call.add("Caller", "It's Ravi.")
            call.add("Caller", " ") // Blank lines are ignored.
            assertEquals("AI: Who's calling?\nCaller: It's Ravi.\n", call.transcript)
            assertTrue(call.callerSpoke())
            assertNull(call.summary)
            call.summary = "Ravi called."
            assertEquals("Ravi called.", CallLog.recent(root, 5).single().summary)
        } finally {
            root.deleteRecursively()
        }
    }
}
