package app.offlinevoice.callprobe

/** The one decision that protects your real calls. Refuses unless every condition holds. */
object AnswerGate {
    /** Returns why the AI must leave the call alone, or null if it may answer it. */
    fun refusal(
        enabled: Boolean,
        emergency: Boolean,
        incoming: Boolean,
        ringing: Boolean,
        otherCalls: Int,
    ): String? = when {
        !enabled -> "assistant is off"
        emergency -> "emergency call"
        !incoming -> "not an incoming call"
        !ringing -> "not ringing"
        otherCalls > 0 -> "another call is in progress"
        else -> null
    }
}
