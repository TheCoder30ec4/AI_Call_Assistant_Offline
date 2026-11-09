package app.offlinevoice.callprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.offlinevoice.callprobe.Ui.column
import app.offlinevoice.callprobe.Ui.dp
import app.offlinevoice.callprobe.Ui.fillWidth
import app.offlinevoice.callprobe.Ui.marginStart
import app.offlinevoice.callprobe.Ui.marginTop
import app.offlinevoice.callprobe.Ui.rounded
import app.offlinevoice.callprobe.Ui.rowView
import app.offlinevoice.callprobe.Ui.text
import app.offlinevoice.callprobe.Ui.weight
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * One answered call in full, laid out like the Stitch "Call Summary" screen: disclosure, caller,
 * structured summary with urgency, the stereo recording, and the transcript.
 *
 * Every label on this screen is fixed text here. The model's summary is parsed into fields
 * ([Memo]), so its own wording never shows up as a heading.
 */
class CallDetailActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var call: CallRecord
    private var player: MediaPlayer? = null
    private var playIcon: ImageView? = null
    private var playLabel: TextView? = null
    private var wave: WaveformView? = null
    private var elapsed: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            showProgress()
            if (player != null) main.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra("dir")?.let(::File)
        if (dir == null || !dir.isDirectory) {
            finish()
            return
        }
        call = CallRecord(dir)
        val memo = call.memo
        // A saved contact's name is spelled right; the summary's is what the recognizer heard.
        val heard = Who.of(memo.caller)
        val who = call.contact.takeIf(String::isNotBlank)
            ?.let { Who.of(it).copy(relation = heard.relation ?: "In your contacts") } ?: heard

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            addView(disclosure().fillWidth())
            addView(callerCard(memo, who).fillWidth().marginTop(this@CallDetailActivity, 12))
            addView(memoCard(memo).fillWidth().marginTop(this@CallDetailActivity, 12))
            addView(recordingCard().fillWidth().marginTop(this@CallDetailActivity, 12))
            addView(transcript(who).fillWidth().marginTop(this@CallDetailActivity, 24))
        }
        val header = header()
        val bottom = bottomBar(memo)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.CANVAS)
            addView(header)
            addView(
                ScrollView(this@CallDetailActivity).apply {
                    addView(content)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                },
            )
            addView(bottom)
        }
        // Edge to edge: keep the top bar below the status bar and the actions above the nav bar.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(dp(16), bars.top + dp(10), dp(16), dp(10))
            bottom.setPadding(dp(16), dp(12), dp(16), bars.bottom + dp(12))
            insets
        }
        setContentView(root)
    }

    // ---- sections ----

    private fun header(): LinearLayout {
        val title = column(
            text("Call Summary", 16f, Ui.BLACK, weight = 600),
            text(whenText(), 12f, Ui.MUTED, weight = 500).marginTop(this, 1),
        ).weight(1f).marginStart(this, 12)
        return rowView(
            iconButton(R.drawable.ic_back, "Go back") { finish() },
            title,
            iconButton(R.drawable.ic_share, "Share summary") { share() },
        ).apply {
            setBackgroundColor(Color.WHITE)
            background = bottomHairline()
        }
    }

    private fun disclosure(): View {
        val tile = icon(R.drawable.ic_shield, 18, Color.WHITE).apply {
            background = rounded(Ui.BLACK, 8)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        val vault = rowView(
            icon(R.drawable.ic_lock, 12, Ui.MUTED),
            text("On this phone", 11f, Ui.MUTED).marginStart(this, 4),
        ).apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }
        val body = column(
            rowView(label("Legal & privacy disclosure", Ui.BLACK).weight(1f), vault),
            text(
                "The AI told the caller it's an automated assistant and that the call is recorded. " +
                    "The recording and transcript are stored only on this phone. No voice data left the device.",
                12f, 0xFF525252.toInt(),
            ).apply { setLineSpacing(0f, 1.25f) }.marginTop(this, 4),
        ).weight(1f).marginStart(this, 12)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFFF2F2F2.toInt(), 16, stroke = Ui.LINE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(tile)
            addView(body)
        }
    }

    private fun callerCard(memo: Memo, who: Who): View {
        val avatar = text(who.initials, 18f, Ui.BLACK, weight = 600).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFF5F5F5.toInt(), 12, stroke = Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        val number = call.number.takeIf { it.isNotBlank() }?.let(::formatNumber)
        val detail = listOfNotNull(who.relation, number).joinToString(" · ").ifBlank { "Number not available" }
        val name = column(
            text(who.name, 20f, Ui.BLACK, weight = 600),
            text(detail, 12f, Ui.MUTED, weight = 500).marginTop(this, 2),
        ).weight(1f).marginStart(this, 16)

        val pills = rowView(
            pill(durationText(call.durationSeconds), dark = false),
            pill("On-device AI", dark = true).marginStart(this, 8),
            View(this).weight(1f),
            pill("Incoming call", dark = false),
        ).marginTop(this, 16)
        return panel().apply {
            addView(rowView(avatar, name))
            addView(divider().marginTop(this@CallDetailActivity, 16))
            addView(pills.fillWidth())
        }
    }

    private fun memoCard(memo: Memo): View {
        val card = panel()
        val urgency = call.urgency
        val badge = rowView(
            View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(urgency.color) }
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            },
            text(urgency.label, 12f, urgency.color, weight = 600).marginStart(this, 6),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            background = rounded(urgency.tint, 6)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        card.addView(rowView(text("Summary", 16f, Ui.BLACK, weight = 600).weight(1f), badge))

        if (call.summary == null) {
            card.addView(text("The summary is being written…", 14f, Ui.MUTED).marginTop(this, 14))
            return card
        }
        val fields = listOfNotNull(
            memo.caller?.let { "Caller" to it },
            memo.reason?.let { "Reason for calling" to it },
            memo.message?.let { "Message" to it },
        )
        if (fields.isEmpty() && memo.callback == null) {
            card.addView(text("The caller didn't leave a message.", 14f, Ui.MUTED).marginTop(this, 14))
            return card
        }
        var first = true
        fun add(view: View) {
            card.addView(view.fillWidth().marginTop(this, if (first) 16 else 10))
            first = false
        }
        fields.take(1).forEach { (name, value) -> add(field(name, value)) }
        memo.callback?.let { add(callbackField(it, memo.callbackDigits)) }
        fields.drop(1).forEach { (name, value) -> add(field(name, value)) }
        return card
    }

    private fun recordingCard(): View {
        val card = panel()
        card.addView(
            rowView(
                text("Call recording", 16f, Ui.BLACK, weight = 600).weight(1f),
                pill("Stereo · on-device", dark = false),
            ),
        )
        if (call.recording.length() <= 44) {
            card.addView(text("No recording was saved for this call.", 14f, Ui.MUTED).marginTop(this, 14))
            return card
        }
        val legend = rowView(
            swatch(Ui.FAINT), text("Left: caller", 12f, 0xFF525252.toInt(), weight = 500).marginStart(this, 6),
            text("•", 12f, Ui.LINE).marginStart(this, 12),
            swatch(Ui.BLACK).marginStart(this, 12), text("Right: AI assistant", 12f, Ui.BLACK, weight = 500).marginStart(this, 6),
        ).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFFAFAFA.toInt(), 8, stroke = Ui.LINE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        card.addView(legend.marginTop(this, 14))

        val waveform = WaveformView(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(64)) }
        wave = waveform
        val file = call.recording
        thread(name = "waveform", isDaemon = true) {
            val bars = runCatching { WaveformView.bars(file, 40) }.getOrNull()
            if (bars != null) main.post { waveform.setBars(bars.first, bars.second) }
        }
        val now = text("0:00", 12f, Ui.MUTED).also { elapsed = it }
        val times = rowView(now, View(this).weight(1f), text(durationText(call.durationSeconds), 12f, Ui.MUTED))
        card.addView(
            column(waveform, times.marginTop(this, 8)).apply {
                background = rounded(0xFFFAFAFA.toInt(), 12, stroke = Ui.LINE)
                setPadding(dp(14), dp(14), dp(14), dp(10))
            }.fillWidth().marginTop(this, 10),
        )

        val playIconView = icon(R.drawable.ic_play, 20, Color.WHITE).also { playIcon = it }
        val playText = text("Play", 14f, Color.WHITE, weight = 500).marginStart(this, 6).also { playLabel = it }
        val play = rowView(playIconView, playText).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(44))
            gravity = Gravity.CENTER
            background = rounded(Ui.BLACK, 8)
            setPadding(dp(20), 0, dp(22), 0)
            isClickable = true
            contentDescription = "Play or pause the recording"
            setOnClickListener { togglePlayback() }
        }
        val controls = rowView(
            skipButton("−10s") { skip(-10_000) },
            play.marginStart(this, 16),
            skipButton("+10s") { skip(10_000) }.marginStart(this, 16),
        ).apply { gravity = Gravity.CENTER }
        card.addView(controls.marginTop(this, 14))
        return card
    }

    private fun transcript(who: Who): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val copy = rowView(
            icon(R.drawable.ic_copy, 14, Ui.INK),
            text("Copy", 12f, Ui.INK, weight = 500).marginStart(this, 6),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            background = rounded(Color.WHITE, 6, stroke = Ui.LINE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { copyTranscript() }
        }
        section.addView(
            rowView(
                column(
                    text("Conversation transcript", 16f, Ui.BLACK, weight = 600),
                    text("Transcribed on this phone · no cloud processing", 12f, Ui.MUTED).marginTop(this, 1),
                ).weight(1f),
                copy,
            ).apply { setPadding(dp(4), 0, 0, 0) },
        )
        val lines = call.transcript.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            section.addView(text("Nothing was said on this call.", 14f, Ui.MUTED).marginTop(this, 12).marginStart(this, 4))
            return section
        }
        for (line in lines) {
            val ai = line.startsWith("AI:")
            val said = line.substringAfter(":", line).trim()
            section.addView(bubble(ai, if (ai) "AI assistant" else who.name, said, who.initials).fillWidth().marginTop(this, 14))
        }
        return section
    }

    private fun bottomBar(memo: Memo): LinearLayout {
        val dial = memo.callbackDigits ?: call.number.takeIf { it.count(Char::isDigit) >= 5 }
        val share = action(R.drawable.ic_share, "Share transcript", primary = false) { share() }
        val bar = rowView(share.weight(1f)).apply {
            background = topHairline()
        }
        if (dial != null) {
            bar.addView(
                action(R.drawable.ic_call, "Call back", primary = true) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(dial))))
                }.weight(1f).marginStart(this, 12),
            )
        }
        return bar
    }

    // ---- building blocks ----

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 16, stroke = Ui.LINE)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    private fun label(value: String, color: Int = Ui.MUTED) =
        text(value.uppercase(Locale.getDefault()), 11f, color, weight = 600).apply { letterSpacing = 0.06f }

    private fun field(name: String, value: String): View = column(
        label(name),
        text(value, 14f, Ui.BLACK, weight = 500).apply { setLineSpacing(0f, 1.2f) }.marginTop(this, 4),
    ).apply {
        background = rounded(0xFFFAFAFA.toInt(), 12, stroke = Ui.LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun callbackField(value: String, digits: String?): View {
        val shown = digits?.let { value.replace(Regex("[+\\d][\\d\\s-]{4,}\\d"), formatNumber(it)) } ?: value
        val row = rowView(
            column(
                label("Callback number"),
                text(shown, 14f, Ui.BLACK, weight = 600).marginTop(this, 4),
            ).weight(1f),
        ).apply {
            background = rounded(0xFFFAFAFA.toInt(), 12, stroke = Ui.LINE)
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }
        if (digits != null) {
            row.addView(
                rowView(icon(R.drawable.ic_call, 14, Color.WHITE), text("Call", 12f, Color.WHITE, weight = 500).marginStart(this, 6)).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    background = rounded(Ui.BLACK, 6)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    isClickable = true
                    contentDescription = "Call $shown"
                    setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits)))) }
                }.marginStart(this, 8),
            )
        }
        return row
    }

    private fun bubble(ai: Boolean, speaker: String, said: String, initials: String): View {
        val avatar = (if (ai) logoAvatar() else text(initials.take(1), 11f, Ui.BLACK, weight = 600).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5E5E5.toInt()) }
        }).apply { layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) }
        val chip = text(if (ai) "R" else "L", 10f, if (ai) Ui.BLACK else 0xFF525252.toInt(), weight = 600).apply {
            background = rounded(if (ai) 0xFFE5E5E5.toInt() else 0xFFF5F5F5.toInt(), 4)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            contentDescription = if (ai) "Right channel" else "Left channel"
        }
        val name = text(speaker, 12f, Ui.BLACK, weight = 600)
        val meta = if (ai) rowView(avatar, name.marginStart(this, 8), chip.marginStart(this, 8))
        else rowView(chip, name.marginStart(this, 8), avatar.marginStart(this, 8))
        meta.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        val body = text(said, 14f, Ui.BLACK).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                val r = dp(16).toFloat()
                val tight = dp(4).toFloat()
                // A tight corner where the bubble meets its speaker, as in a chat.
                cornerRadii = if (ai) floatArrayOf(tight, tight, r, r, r, r, r, r) else floatArrayOf(r, r, tight, tight, r, r, r, r)
                setColor(if (ai) 0xFFF2F2F2.toInt() else Color.WHITE)
                setStroke(dp(1), Ui.LINE)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (ai) Gravity.START else Gravity.END
            // Bubbles take at most ~88% of the width, leaving the gap on the other speaker's side.
            if (ai) setPadding(0, 0, dp(40), 0) else setPadding(dp(40), 0, 0, 0)
            addView(meta.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), 0, dp(4), 0) })
            addView(body.apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }.marginTop(this@CallDetailActivity, 6))
        }
    }

    private fun logoAvatar(): View = icon(R.drawable.ic_shield, 14, Color.WHITE).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.BLACK) }
        setPadding(dp(5), dp(5), dp(5), dp(5))
    }

    private fun icon(res: Int, sizeDp: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit) = icon(res, 36, Ui.INK).apply {
        background = rounded(Color.WHITE, 8, stroke = Ui.LINE)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        contentDescription = description
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pill(value: String, dark: Boolean) = text(value, 12f, if (dark) Ui.BLACK else 0xFF404040.toInt(), weight = if (dark) 600 else 500).apply {
        background = rounded(if (dark) 0xFFE5E5E5.toInt() else 0xFFF5F5F5.toInt(), 6, stroke = Ui.LINE)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun swatch(color: Int) = View(this).apply {
        background = rounded(color, 2)
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }

    private fun skipButton(value: String, onClick: () -> Unit) = text(value, 13f, Ui.INK, weight = 500).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(40))
        background = rounded(Color.WHITE, 8, stroke = Ui.LINE)
        contentDescription = if (value.startsWith("+")) "Skip forward 10 seconds" else "Skip back 10 seconds"
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun action(res: Int, value: String, primary: Boolean, onClick: () -> Unit): View {
        val color = if (primary) Color.WHITE else Ui.BLACK
        return rowView(icon(res, 18, color), text(value, 14f, color, weight = 500).marginStart(this, 8)).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(46))
            background = if (primary) rounded(Ui.BLACK, 8) else rounded(Color.WHITE, 8, stroke = 0xFFBDBDBD.toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFF0F0F0.toInt())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    private fun bottomHairline() = android.graphics.drawable.LayerDrawable(
        arrayOf(android.graphics.drawable.ColorDrawable(Ui.LINE), android.graphics.drawable.ColorDrawable(Color.WHITE)),
    ).apply { setLayerInset(1, 0, 0, 0, dp(1)) }

    private fun topHairline() = android.graphics.drawable.LayerDrawable(
        arrayOf(android.graphics.drawable.ColorDrawable(Ui.LINE), android.graphics.drawable.ColorDrawable(Color.WHITE)),
    ).apply { setLayerInset(1, 0, dp(1), 0, 0) }

    // ---- text ----

    private fun whenText(): String {
        val time = DateFormat.getTimeFormat(this).format(call.startedAt)
        return when {
            DateUtils.isToday(call.startedAt) -> "Today at $time"
            DateUtils.isToday(call.startedAt + DateUtils.DAY_IN_MILLIS) -> "Yesterday at $time"
            else -> DateFormat.format("d MMM yyyy", call.startedAt).toString() + " at $time"
        }
    }

    private fun durationText(seconds: Int) = if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"

    private fun formatNumber(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

    private fun shareText(): String {
        val memo = call.memo
        val parts = listOfNotNull(
            "Call summary · ${whenText()}",
            memo.caller?.let { "Caller: $it" },
            memo.reason?.let { "Reason: $it" },
            memo.message?.let { "Message: $it" },
            memo.callback?.let { "Callback: $it" },
            "Urgency: ${call.urgency.label}",
            "",
            "Transcript",
            call.transcript.trim(),
        )
        return parts.joinToString("\n")
    }

    private fun share() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText()),
                "Share call summary",
            ),
        )
    }

    private fun copyTranscript() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Call transcript", call.transcript.trim()))
        Toast.makeText(this, "Transcript copied", Toast.LENGTH_SHORT).show()
    }

    // ---- playback ----

    private fun togglePlayback() {
        val current = player
        if (current != null) {
            if (current.isPlaying) current.pause() else current.start()
            showPlaying(current.isPlaying)
            if (current.isPlaying) main.post(tick)
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(call.recording.path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            showPlaying(true)
            main.post(tick)
        }.onFailure {
            player = null
            Toast.makeText(this, "Can't play this recording: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun skip(byMs: Int) {
        val current = player ?: return
        current.seekTo((current.currentPosition + byMs).coerceIn(0, current.duration))
        showProgress()
    }

    private fun showPlaying(playing: Boolean) {
        playIcon?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playLabel?.text = if (playing) "Pause" else "Play"
    }

    private fun showProgress() {
        val current = player ?: return
        val duration = current.duration.takeIf { it > 0 } ?: return
        wave?.progress = current.currentPosition.toFloat() / duration
        elapsed?.text = clock(current.currentPosition / 1000)
    }

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun stopPlayback() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        showPlaying(false)
        wave?.progress = 0f
        elapsed?.text = clock(0)
    }

    override fun onPause() {
        stopPlayback()
        super.onPause()
    }
}

/** The caller as shown on screen: "Ravi, your brother" becomes name "Ravi", relation "Your brother". */
data class Who(val name: String, val relation: String?, val initials: String) {
    companion object {
        fun of(caller: String?): Who {
            if (caller.isNullOrBlank()) return Who("Unknown caller", null, "?")
            val name = caller.substringBefore(",").substringBefore(" (").trim().ifBlank { caller.trim() }
            val relation = caller.substringAfter(",", "").ifBlank { caller.substringAfter(" (", "").trimEnd(')') }
                .trim().takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            val initials = name.split(" ").filter { it.firstOrNull()?.isLetter() == true }
                .take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
            return Who(name, relation, initials)
        }
    }
}

/** Bars for the stereo recording: height is loudness, black bars are the AI, grey bars the caller. */
class WaveformView(context: Context) : View(context) {
    private var heights = FloatArray(0)
    private var aiBars = BooleanArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 0 to 1: bars before this point are drawn solid, the rest faded. */
    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    fun setBars(heights: FloatArray, ai: BooleanArray) {
        this.heights = heights
        aiBars = ai
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = heights.size
        if (n == 0) return
        val slot = width.toFloat() / n
        val barWidth = slot * 0.55f
        val radius = barWidth / 3
        for (i in 0 until n) {
            val h = (heights[i] * height).coerceAtLeast(height * 0.06f)
            val x = i * slot + (slot - barWidth) / 2
            rect.set(x, (height - h) / 2, x + barWidth, (height + h) / 2)
            val played = (i + 0.5f) / n <= progress
            paint.color = if (aiBars[i]) Ui.BLACK else 0xFF9E9E9E.toInt()
            paint.alpha = if (played || progress == 0f) 255 else 90
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    companion object {
        /** Reads the recording once and returns [count] bar heights (0 to 1) and which side was louder. */
        fun bars(file: File, count: Int): Pair<FloatArray, BooleanArray> {
            val bytes = file.readBytes()
            val frames = (bytes.size - 44) / 4
            val left = FloatArray(count)
            val right = FloatArray(count)
            if (frames <= 0) return FloatArray(count) to BooleanArray(count)
            for (f in 0 until frames) {
                val o = 44 + f * 4
                val l = kotlin.math.abs(((bytes[o + 1].toInt() shl 8) or (bytes[o].toInt() and 0xff)).toShort().toInt())
                val r = kotlin.math.abs(((bytes[o + 3].toInt() shl 8) or (bytes[o + 2].toInt() and 0xff)).toShort().toInt())
                val b = (f.toLong() * count / frames).toInt()
                if (l > left[b]) left[b] = l.toFloat()
                if (r > right[b]) right[b] = r.toFloat()
            }
            val peak = (0 until count).maxOf { maxOf(left[it], right[it]) }.coerceAtLeast(1f)
            return FloatArray(count) { maxOf(left[it], right[it]) / peak } to BooleanArray(count) { right[it] > left[it] }
        }
    }
}
