package app.offlinevoice.callprobe

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.offlinevoice.callprobe.Ui.card
import app.offlinevoice.callprobe.Ui.dp
import app.offlinevoice.callprobe.Ui.eyebrow
import app.offlinevoice.callprobe.Ui.fillWidth
import app.offlinevoice.callprobe.Ui.marginTop
import app.offlinevoice.callprobe.Ui.primaryButton
import app.offlinevoice.callprobe.Ui.rounded
import app.offlinevoice.callprobe.Ui.scaffold
import app.offlinevoice.callprobe.Ui.segmented
import app.offlinevoice.callprobe.Ui.settingRow
import app.offlinevoice.callprobe.Ui.text

/** Settings & privacy, matching the Stitch screen. Backed by real app preferences. */
class SettingsActivity : Activity() {
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = Ui.run { scaffold(selected = 2) }
        build()
    }

    /** e.g. "Google on-device · English (India): installed · Hindi: installed · Telugu: downloading". */
    private fun speechSummary(): String {
        val names = mapOf("en-IN" to "English (India)", "hi-IN" to "Hindi", "te-IN" to "Telugu")
        val packs = Probe.speechPacks
        val languages = if (packs.isEmpty()) "Checking language packs…"
            else GoogleAsr.LANGUAGES.joinToString("\n") { "${names[it]}: ${packs[it] ?: "unknown"}" }
        val contacts = if (Contacts.allowed(this)) "Contacts connected for caller names" else "Contacts not allowed"
        return "Google on-device recognizer\n$languages\n$contacts"
    }

    private fun build() {
        container.removeAllViews()
        val c = container

        c.addView(text("Settings", 24f, Ui.INK, weight = 600))
        c.addView(text("Preferences & privacy", 14f, Ui.MUTED).marginTop(this, 2))

        c.addView(onDeviceBanner().marginTop(this, 16))

        // --- Assistant identity & voice ---
        c.addView(sectionTitle("Assistant identity & voice").marginTop(this, 24))
        val identity = card()
        val nameField = EditText(this).apply {
            setText(Probe.ownerName)
            setTextColor(Ui.INK)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_CLASS_TEXT
            background = rounded(Ui.CARD_SOFT, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        identity.addView(text("Your name", 12f, Ui.MUTED, weight = 600))
        identity.addView(text("Announced to callers as your identity", 12f, Ui.MUTED).marginTop(this, 2))
        identity.addView(nameField.fillWidth().marginTop(this, 8))
        identity.addView(primaryButton("Save name") {
            Probe.ownerName = nameField.text.toString().trim()
            toast("Saved. It applies from the next call.")
        }.fillWidth().marginTop(this, 10))
        identity.addView(divider())
        identity.addView(settingRow("AI voice", "Natural female · on-device neural voice", chevron()).marginTop(this, 12))
        identity.addView(divider())
        identity.addView(settingRow("Speech recognition", speechSummary(), null).marginTop(this, 12))
        identity.addView(divider())
        identity.addView(settingRow("AI & recording disclosure", "Always tells callers an AI is answering and recording.", lockedOnChip()).marginTop(this, 12))
        c.addView(identity.fillWidth().marginTop(this, 8))

        // --- Call answering conditions ---
        c.addView(sectionTitle("Call answering conditions").marginTop(this, 24))
        val conditions = card()
        conditions.addView(text("Who the AI answers for", 13f, Ui.MUTED, weight = 600))
        val modes = listOf("Everyone", "Only contacts", "Except contacts")
        conditions.addView(
            segmented(modes, selected = 0) { toast("Contact-based screening comes in the next version.") }
                .fillWidth().marginTop(this, 8),
        )
        conditions.addView(text("Answers any unanswered call. Emergency calls are always left alone.", 12f, Ui.MUTED).marginTop(this, 8))
        conditions.addView(divider())
        conditions.addView(text("Ring delay before the AI picks up", 13f, Ui.MUTED, weight = 600).marginTop(this, 12))
        val delays = listOf(10, 15, 20)
        val delayLabels = listOf("10s", "15s", "20s")
        val delaySub = text("Gives you ${Probe.ringDelaySeconds}s to pick up first.", 12f, Ui.MUTED)
        conditions.addView(
            segmented(delayLabels, selected = delays.indexOf(Probe.ringDelaySeconds).coerceAtLeast(0)) { index ->
                Probe.ringDelaySeconds = delays[index]
                delaySub.text = "Gives you ${Probe.ringDelaySeconds}s to pick up first."
            }.fillWidth().marginTop(this, 8),
        )
        conditions.addView(delaySub.marginTop(this, 8))
        c.addView(conditions.fillWidth().marginTop(this, 8))

        // --- Privacy & data ---
        c.addView(sectionTitle("Privacy & data storage").marginTop(this, 24))
        val privacy = card()
        privacy.addView(text("🔒  Local storage only", 15f, Ui.INK, weight = 600))
        privacy.addView(
            text(
                "All recordings and transcripts stay in this app's private storage on your phone. " +
                    "Nothing is ever sent to a server or the cloud.",
                13f, Ui.MUTED,
            ).marginTop(this, 6),
        )
        privacy.addView(divider())
        privacy.addView(settingRow("Storage used", storageSummary(), null).marginTop(this, 12))
        privacy.addView(divider())
        privacy.addView(
            TextView(this).apply {
                text = "Delete all call history & audio"
                textSize = 15f
                setTextColor(Ui.RED)
                setPadding(0, dp(12), 0, 0)
                isClickable = true
                setOnClickListener { confirmDelete() }
            },
        )
        privacy.addView(text("Permanently removes every recording and transcript from this phone. Can't be undone.", 12f, Ui.MUTED).marginTop(this, 2))
        c.addView(privacy.fillWidth().marginTop(this, 8))
    }

    private fun onDeviceBanner(): View = card(Ui.CARD_SOFT).apply {
        addView(text("🛡  100% on-device · zero cloud", 13f, Ui.INK, weight = 600))
        addView(text("Voice synthesis and transcription run inside your phone's hardware.", 12f, Ui.MUTED).marginTop(this@SettingsActivity, 4))
    }.fillWidth()

    private fun sectionTitle(title: String) = eyebrow(title)

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(12) }
    }

    private fun chevron() = text("›", 20f, Ui.FAINT)

    private fun lockedOnChip() = text("On", 12f, Ui.GREEN, weight = 600).apply {
        background = rounded(Ui.CARD_SOFT, 8)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun storageSummary(): String {
        val calls = Probe.recentCalls(Int.MAX_VALUE)
        val bytes = calls.sumOf { it.dir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        val mb = bytes / (1024.0 * 1024.0)
        return "${calls.size} calls · ${"%.1f".format(mb)} MB"
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete all call history?")
            .setMessage("This permanently removes every recording, transcript and summary from this phone. It can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                val removed = Probe.deleteCalls()
                toast("Deleted $removed calls.")
                build()
            }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        build()
    }
}
