package app.offlinevoice.callprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.offlinevoice.callprobe.Ui.card
import app.offlinevoice.callprobe.Ui.column
import app.offlinevoice.callprobe.Ui.dp
import app.offlinevoice.callprobe.Ui.eyebrow
import app.offlinevoice.callprobe.Ui.fillWidth
import app.offlinevoice.callprobe.Ui.ghostButton
import app.offlinevoice.callprobe.Ui.marginStart
import app.offlinevoice.callprobe.Ui.marginTop
import app.offlinevoice.callprobe.Ui.primaryButton
import app.offlinevoice.callprobe.Ui.rounded
import app.offlinevoice.callprobe.Ui.rowView
import app.offlinevoice.callprobe.Ui.statusRow
import app.offlinevoice.callprobe.Ui.text
import app.offlinevoice.callprobe.Ui.weight
import java.io.File

/** Home screen: turn the assistant on, see its status, and browse the calls it handled. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var toggle: Switch
    private lateinit var heroCard: LinearLayout
    private lateinit var heroLine: TextView
    private lateinit var diagnostics: LinearLayout
    private lateinit var owner: EditText
    private lateinit var callsList: LinearLayout
    private var callsShown = ""

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.start(this)

        if (!Probe.onboarded && !intent.getBooleanExtra("selftest", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        val content = Ui.run { scaffold(selected = 0) }

        content.addView(header())
        content.addView(badge().marginTop(this, 16))
        content.addView(text("Answers the calls you don't pick up — right on your phone.", 14f, Ui.MUTED).fillWidth().marginTop(this, 16))

        heroCard = card()
        toggle = Switch(this).apply {
            text = "  Answer unanswered calls"
            textSize = 17f
            setTextColor(Ui.INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isChecked = Probe.enabled
            setOnCheckedChangeListener { _, on -> setEnabled(on) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        heroLine = text("", 13f, Ui.MUTED).marginTop(this, 8)
        heroCard.addView(toggle)
        heroCard.addView(heroLine)
        content.addView(heroCard.fillWidth().marginTop(this, 16))

        content.addView(diagnosticsCard().marginTop(this, 12))
        content.addView(personaCard().marginTop(this, 12))

        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Calls", 20f, Ui.INK, weight = 600).weight(1f))
            }.marginTop(this, 28),
        )
        callsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(callsList.fillWidth())

        Probe.requestShizuku()
        if (Probe.enabled) AssistantService.start(this) else Probe.loadAi()
        askForSpeechAndContacts()
        Probe.prepareSpeechPacks()
        maybeSelfTest(intent)
    }

    /**
     * Microphone: Android requires it to use the speech recognizer, even though we feed it the call
     * audio and never open the mic. Contacts: to know who's calling and spell names right.
     */
    private fun askForSpeechAndContacts() {
        val missing = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_CONTACTS)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    private fun header(): View {
        val logo = Ui.run { logoView(44) }
        val titles = column(
            text("AI Call Assistant", 22f, Ui.INK, weight = 600),
            text("Local Phone Guard", 13f, Ui.MUTED).marginTop(this, 1),
        ).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) } }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(logo)
            addView(titles)
        }
    }

    private fun badge(): View {
        val pill = TextView(this).apply {
            text = "✓  100% On-Device · No Cloud"
            textSize = 12f
            setTextColor(Ui.INK)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            background = rounded(Ui.CARD_SOFT, 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(pill)
        }
    }

    private fun diagnosticsCard(): LinearLayout {
        val card = card()
        card.addView(eyebrow("System diagnostic"))
        diagnostics = column().apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        card.addView(diagnostics.marginTop(this, 10))
        card.addView(
            ghostButton("Connect Shizuku") {
                Probe.requestShizuku()
                Probe.worker.execute { runCatching { Probe.shell?.setup() }.getOrNull()?.let { Probe.log("call access: $it") } }
            }.fillWidth().marginTop(this, 14),
        )
        return card.fillWidth()
    }

    private fun personaCard(): LinearLayout {
        val card = card()
        card.addView(eyebrow("Persona identity"))
        card.addView(text("The AI introduces itself with this name.", 13f, Ui.MUTED).marginTop(this, 2))
        owner = EditText(this).apply {
            hint = "Your first name, e.g. Priya"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.INK)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_CLASS_TEXT
            setText(Probe.ownerName)
            background = rounded(Ui.CARD_SOFT, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(owner.fillWidth().marginTop(this, 10))
        card.addView(
            primaryButton("Save name") {
                Probe.ownerName = owner.text.toString().trim()
                toast("Saved. It applies from the next call.")
            }.fillWidth().marginTop(this, 10),
        )
        return card.fillWidth()
    }

    private fun setEnabled(on: Boolean) {
        Probe.enabled = on
        Probe.ownerName = owner.text.toString().trim()
        if (on) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            AssistantService.start(this)
        } else {
            AssistantService.stop(this)
        }
        render()
    }

    private fun render() {
        val on = Probe.enabled
        heroCard.background = rounded(if (on) Ui.CARD_SOFT else Ui.CARD, 16).also {
            if (!on) it.setStroke(dp(1), Ui.LINE)
        }
        heroLine.text = if (on) {
            "A call ringing ${Probe.ringDelaySeconds}s unanswered is answered by the AI."
        } else {
            "When off, calls ring as normal with no pickup."
        }

        val helperOk = Probe.shizukuDetail.startsWith("Helper connected")
        val aiReady = Probe.aiDetail == "AI ready"
        diagnostics.removeAllViews()
        diagnostics.addView(
            statusRow(
                if (aiReady) Ui.GREEN else Ui.AMBER,
                if (aiReady) "Assistant ready" else "Assistant loading",
                "Gemma 4 · on-device, 4-bit",
            ),
        )
        diagnostics.addView(
            statusRow(
                if (helperOk) Ui.GREEN else Ui.RED,
                if (helperOk) "Call access connected" else "Call access not connected",
                if (helperOk) "Telecom companion · low-latency bridge" else Probe.shizukuDetail,
            ).marginTop(this, 10),
        )

        Probe.worker.execute {
            val recent = Probe.recentCalls(20)
            val shown = recent.joinToString { it.dir.name + ":" + (it.summary?.length ?: -1) }
            if (shown == callsShown) return@execute
            runOnUiThread {
                callsShown = shown
                showCalls(recent)
            }
        }
    }

    private fun showCalls(recent: List<CallRecord>) {
        callsList.removeAllViews()
        if (recent.isEmpty()) {
            callsList.addView(
                card().apply {
                    addView(text("No calls answered yet.", 15f, Ui.INK, weight = 500))
                    addView(text("When the AI handles a call, it appears here with a summary and recording.", 13f, Ui.MUTED).marginTop(this@MainActivity, 4))
                }.fillWidth(),
            )
            return
        }
        for (call in recent) {
            val memo = call.memo
            val summaryLine = if (call.summary == null) "Summary is being written…"
                else memo.reason ?: memo.message ?: "No message left."
            val urgency = call.urgency
            val cardView = card().apply {
                addView(
                    rowView(
                        text(memo.caller ?: "Unknown caller", 15f, Ui.INK, weight = 600).weight(1f),
                        text(urgency.label, 11f, urgency.color, weight = 600).apply {
                            background = rounded(urgency.tint, 6)
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                        },
                    ),
                )
                addView(text(summaryLine, 14f, Ui.INK).marginTop(this@MainActivity, 6))
                addView(text(call.title(), 12f, Ui.MUTED).marginTop(this@MainActivity, 8))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CallDetailActivity::class.java).putExtra("dir", call.dir.absolutePath))
                }
            }
            callsList.addView(cardView.fillWidth().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) })
        }
    }

    /** Dev-only: scripted conversation with no real call, fired only by an explicit adb intent extra. */
    private fun maybeSelfTest(request: Intent) {
        if (!BuildConfig.DEBUG || !request.getBooleanExtra("selftest", false)) return
        request.removeExtra("selftest")
        Probe.ownerName = owner.text.toString().trim()
        Probe.loadAi { agent ->
            runCatching {
                agent.selfTest(
                    listOf(
                        "Hi, who is this?",
                        "This is Ravi. Can you tell them the payment is due on Friday?",
                        "Sorry, what was my message again?",
                        "Okay, thanks. Bye.",
                    ),
                    Probe.newCall("self-test"),
                )
                Probe.log("self-test finished")
            }.onFailure { Probe.log("self-test failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeSelfTest(intent)
    }

    private fun toast(message: String) = Toast.makeText(this, message.take(300), Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        callsShown = "" // Force a refresh in case a call finished while we were away.
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }
}
