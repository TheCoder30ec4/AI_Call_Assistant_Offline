package app.offlinevoice.callprobe

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One answered call, kept in the app's private storage: transcript, summary and a recording of both
 * sides (caller on the left channel, AI on the right).
 */
class CallRecord(val dir: File) {
    val recording = File(dir, "recording.wav")
    private val transcriptFile = File(dir, "transcript.txt")
    private val summaryFile = File(dir, "summary.txt")
    private val labelFile = File(dir, "label.txt")

    val startedAt: Long get() = dir.name.toLongOrNull() ?: 0

    var label: String
        get() = labelFile.takeIf { it.exists() }?.readText().orEmpty()
        set(value) = labelFile.writeText(value)

    val transcript: String get() = transcriptFile.takeIf { it.exists() }?.readText().orEmpty()

    /** Null while the call is in progress or the summary is still being written. */
    var summary: String?
        get() = summaryFile.takeIf { it.exists() }?.readText()
        set(value) = summaryFile.writeText(value.orEmpty())

    /** HIGH, MEDIUM or LOW, read from the summary's "Urgency:" line. Defaults to MEDIUM. */
    val urgency: Urgency
        get() {
            val line = summary.orEmpty().lineSequence().firstOrNull { it.trim().startsWith("Urgency", ignoreCase = true) }
            return when {
                line == null -> Urgency.MEDIUM
                line.contains("HIGH", ignoreCase = true) -> Urgency.HIGH
                line.contains("LOW", ignoreCase = true) -> Urgency.LOW
                else -> Urgency.MEDIUM
            }
        }

    /** The incoming number, kept only in this private record so you can call back. */
    var number: String
        get() = File(dir, "number.txt").takeIf { it.exists() }?.readText().orEmpty()
        set(value) = File(dir, "number.txt").writeText(value)

    /** The caller's name as saved in your contacts, or blank if the number isn't saved. */
    var contact: String
        get() = File(dir, "contact.txt").takeIf { it.exists() }?.readText().orEmpty()
        set(value) = File(dir, "contact.txt").writeText(value)

    /** The summary as fixed fields, whatever labels the model actually used. */
    val memo: Memo get() = Memo.parse(summary.orEmpty())

    /** Length of the recording in seconds (16 kHz stereo PCM16 after a 44-byte header). */
    val durationSeconds: Int get() = ((recording.length() - 44).coerceAtLeast(0) / (16000 * 4)).toInt()

    @Synchronized
    fun add(speaker: String, text: String) {
        if (text.isNotBlank()) transcriptFile.appendText("$speaker: $text\n")
    }

    fun callerSpoke(): Boolean = transcript.lineSequence().any { it.startsWith("Caller:") }

    fun title(): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(startedAt)) +
        label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

/** Call urgency, used to colour the notification and the call card. */
enum class Urgency(val label: String, val color: Int, val tint: Int) {
    HIGH("Urgent", 0xFFD93025.toInt(), 0xFFFCE8E6.toInt()), // red
    MEDIUM("Normal", 0xFF2F6BFF.toInt(), 0xFFE8EEFF.toInt()), // blue
    LOW("Low priority", 0xFF1B7A3D.toInt(), 0xFFE6F4EA.toInt()), // green
}

/** The summary's fields. A field is null when the caller didn't give it. */
data class Memo(val caller: String?, val reason: String?, val message: String?, val callback: String?) {
    /** Digits to dial from the callback line, if it holds a phone number. */
    val callbackDigits: String? get() = callback?.let { phoneRun.find(it)?.value }
        ?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 5 }

    companion object {
        // The phone number in "98480 22338 after 6 pm": digits with spaces or dashes, not the time.
        private val phoneRun = Regex("\\+?\\d[\\d -]{3,}\\d")

        // Placeholder values only ("none", "not given"), never real content like "No rush, tomorrow is fine".
        private val empty = Regex(
            "(none|nil|n/?a|unknown|not (given|provided|mentioned|stated|specified)|no (number|callback|phone number)( given| provided)?|-+)",
            RegexOption.IGNORE_CASE,
        )

        fun parse(summary: String): Memo {
            val fields = HashMap<String, String>()
            for (line in summary.lines()) {
                val parts = line.replace("*", "").split(":", limit = 2)
                if (parts.size != 2) continue
                val key = when (parts[0].trim().lowercase()) {
                    "caller", "name", "caller name", "from" -> "caller"
                    "reason", "purpose", "topic", "about" -> "reason"
                    "message", "note", "notes" -> "message"
                    "callback", "call back", "phone", "number", "callback number" -> "callback"
                    else -> continue
                }
                val value = parts[1].trim().trimEnd('.').trim()
                if (value.isNotEmpty() && !empty.matches(value)) fields.putIfAbsent(key, value)
            }
            // Not in the expected format (e.g. "The caller didn't say anything."): show it as the message.
            if (fields.isEmpty() && summary.isNotBlank()) return Memo(null, null, summary.trim(), null)
            return Memo(fields["caller"], fields["reason"], fields["message"], fields["callback"])
        }
    }
}

object CallLog {
    fun newCall(root: File, label: String): CallRecord {
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        return CallRecord(dir).also { it.label = label }
    }

    fun recent(root: File, limit: Int): List<CallRecord> =
        root.listFiles { file -> file.isDirectory && file.name.toLongOrNull() != null }.orEmpty()
            .sortedByDescending { it.name.toLong() }
            .take(limit)
            .map(::CallRecord)
}
