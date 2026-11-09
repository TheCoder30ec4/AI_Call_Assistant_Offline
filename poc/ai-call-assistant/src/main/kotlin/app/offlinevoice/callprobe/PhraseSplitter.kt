package app.offlinevoice.callprobe

/**
 * Turns the language model's streamed text into phrases ready to speak, so the voice can start
 * before the whole reply exists. Removes the model's <think> block, which must never be spoken.
 */
class PhraseSplitter(private val minWordsBeforeComma: Int = 3) {
    private val text = StringBuilder()

    /** Adds streamed text and returns any phrases that are now complete. */
    fun add(chunk: String): List<String> {
        text.append(chunk)
        if (removeThinking()) return emptyList()
        val phrases = mutableListOf<String>()
        var start = 0
        for (i in 0 until text.length - 1) {
            val endsPhrase = text[i] in ".?!;:" || (text[i] == ',' && wordCount(start, i) >= minWordsBeforeComma)
            if (endsPhrase && text[i + 1].isWhitespace()) {
                val phrase = text.substring(start, i + 1).trim()
                if (phrase.any(Char::isLetterOrDigit)) phrases += phrase
                start = i + 1
            }
        }
        text.delete(0, start)
        return phrases
    }

    /** Returns whatever remains once the stream has ended. */
    fun finish(): String? {
        if (removeThinking()) text.delete(text.indexOf(THINK_BEGIN), text.length)
        val rest = text.toString().trim()
        text.clear()
        return rest.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** Removes complete thinking blocks. Returns true if one is still open. */
    private fun removeThinking(): Boolean {
        while (true) {
            val begin = text.indexOf(THINK_BEGIN)
            val end = text.indexOf(THINK_END)
            when {
                // The opening tag can be supplied by the prompt, leaving only the closing tag.
                end >= 0 && (begin < 0 || end < begin) -> text.delete(0, end + THINK_END.length)
                begin < 0 -> return false
                end < 0 -> return true
                else -> text.delete(begin, end + THINK_END.length)
            }
        }
    }

    private fun wordCount(start: Int, end: Int): Int =
        text.substring(start, end).trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private companion object {
        const val THINK_BEGIN = "<think>"
        const val THINK_END = "</think>"
    }
}
