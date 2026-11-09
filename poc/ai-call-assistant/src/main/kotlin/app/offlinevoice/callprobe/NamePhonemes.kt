package app.offlinevoice.callprobe

import java.io.File

/**
 * The text-to-speech voice silently drops words not in its pronunciation dictionary — including many
 * personal names. This adds the owner's name with an approximate phonetic spelling so it's spoken.
 *
 * The spelling is a rough grapheme-to-phoneme guess tuned for the names this app actually says. It
 * won't be perfect for every name, but it's far better than the name vanishing from speech.
 */
object NamePhonemes {
    /**
     * Returns the path to a lexicon that includes [name]. If the name is blank or already present,
     * the original lexicon path is returned unchanged.
     */
    fun lexiconWithName(original: File, writable: File, name: String): String {
        val word = name.trim().lowercase().takeWhile { it.isLetter() }
        if (word.length < 2) return original.path
        return runCatching {
            // Already present? Keep using the original.
            if (original.useLines { lines -> lines.any { it.startsWith("$word ||") || it.startsWith("$word  ") } }) {
                return original.path
            }
            original.copyTo(writable, overwrite = true)
            writable.appendText("\n$word || ${phonemes(word)}\n")
            writable.path
        }.getOrDefault(original.path)
    }

    /** Very rough English letter-to-sound for a single lowercase word, output as space-separated IPA. */
    fun phonemes(word: String): String {
        val out = StringBuilder()
        var i = 0
        val vowels = "aeiou"
        while (i < word.length) {
            val c = word[i]
            val next = word.getOrNull(i + 1)
            val two = if (next != null) "$c$next" else ""
            val mapped = when {
                two == "ch" -> "tʃ".also { i++ }
                two == "sh" -> "ʃ".also { i++ }
                two == "th" -> "θ".also { i++ }
                two == "ph" -> "f".also { i++ }
                two == "oo" -> "uː".also { i++ }
                two == "ee" -> "iː".also { i++ }
                two == "ai" || two == "ay" -> "eɪ".also { i++ }
                two == "ou" || two == "ow" -> "aʊ".also { i++ }
                c == 'a' -> if (atEnd(word, i)) "ə" else "ɑː"
                c == 'e' -> if (atEnd(word, i)) "" else "ɛ"
                c == 'i' -> "ɪ"
                c == 'o' -> "oʊ"
                c == 'u' -> "ʌ"
                c == 'y' -> if (i == 0) "j" else "iː"
                c == 'c' -> if (next in listOf('e', 'i', 'y')) "s" else "k"
                c == 'g' -> "ɡ"
                c == 'j' -> "dʒ"
                c == 'q' -> "k"
                c == 'x' -> "k s"
                c == 'r' -> "ɹ"
                c == 'v' -> "v"
                c == 'w' -> "w"
                c in "bdfhklmnpstz" -> c.toString()
                else -> ""
            }
            if (mapped.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(mapped)
            }
            i++
        }
        // Stress the first vowel so it doesn't sound flat.
        return "ˈ " + out.toString().ifBlank { "ə" }
    }

    private fun atEnd(word: String, i: Int) = i == word.length - 1
}
