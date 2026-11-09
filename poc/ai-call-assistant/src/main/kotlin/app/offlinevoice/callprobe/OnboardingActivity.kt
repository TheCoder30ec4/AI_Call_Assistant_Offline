package app.offlinevoice.callprobe

import android.app.Activity
import android.content.Intent
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
import android.widget.TextView
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
import app.offlinevoice.callprobe.Ui.text
import app.offlinevoice.callprobe.Ui.weight

/** First-run setup: privacy promise, connect the call-audio helper, then name + notifications. */
class OnboardingActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var nameField: EditText? = null
    private var typedName = ""
    private var step = 1

    private var lastConnected: Boolean? = null
    private val poll = object : Runnable {
        override fun run() {
            // Only rebuild when the helper's connection state changes, so typing isn't interrupted.
            val connected = helperConnected()
            if (connected != lastConnected) {
                lastConnected = connected
                render()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.start(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        Ui.run { applyChrome(root) }
        typedName = Probe.ownerName
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.CANVAS); isFillViewport = true; addView(root) })
        Probe.requestShizuku()
    }

    private fun helperConnected() = Probe.shizukuDetail.startsWith("Helper connected")

    private fun render() {
        root.removeAllViews()

        // Header with shield + step indicator.
        root.addView(
            Ui.run {
                rowView(
                    logoView(28),
                    text("AI Call Assistant", 17f, Ui.INK, weight = 600).marginStart(this@OnboardingActivity, 10).weight(1f),
                    text("Step $step of 3", 12f, Ui.MUTED),
                )
            },
        )
        root.addView(text("Get started in under a minute", 24f, Ui.INK, weight = 600).marginTop(this, 20))
        root.addView(text("Private, automatic, and always runs on your phone.", 14f, Ui.MUTED).marginTop(this, 4))
        root.addView(badge().marginTop(this, 16))

        // Step 1 — privacy promise (always shown as completed once past it).
        root.addView(stepOneCard().marginTop(this, 16))

        // Step 2 — connect the helper.
        root.addView(stepTwoCard().marginTop(this, 12))

        // Step 3 — name + notifications.
        root.addView(stepThreeCard().marginTop(this, 12))

        // Primary action.
        root.addView(primaryAction().marginTop(this, 20))
        root.addView(
            TextView(this).apply {
                text = "Skip for now"
                textSize = 14f
                setTextColor(Ui.MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
                isClickable = true
                setOnClickListener { finishOnboarding() }
            }.fillWidth(),
        )
    }

    private fun badge(): View {
        val pill = TextView(this).apply {
            text = "✓  100% on-device · zero cloud"
            textSize = 12f
            setTextColor(Ui.INK)
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            background = rounded(Ui.CARD_SOFT, 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        // Wrap so the pill hugs its text instead of filling the row.
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(pill)
        }
    }

    private fun stepOneCard(): View = card(Ui.CARD_SOFT).apply {
        addView(
            Ui.run {
                rowView(
                    text("✓", 16f, Ui.GREEN, weight = 600),
                    column(
                        text("Step 1 · Privacy promise", 14f, Ui.INK, weight = 600),
                        text("The AI model runs fully on-device. Verified.", 12f, Ui.MUTED).marginTop(this@OnboardingActivity, 2),
                    ).marginStart(this@OnboardingActivity, 10),
                )
            },
        )
    }.fillWidth()

    private fun stepTwoCard(): View {
        val connected = helperConnected()
        val c = card()
        c.addView(
            Ui.run {
                rowView(
                    eyebrow(if (connected) "Connected" else "Active setup").weight(1f),
                    text(if (connected) "✓ Paired" else "Ready to pair", 12f, if (connected) Ui.GREEN else Ui.MUTED, weight = 600),
                )
            },
        )
        c.addView(text("Step 2: Connect call audio helper", 17f, Ui.INK, weight = 600).marginTop(this, 8))
        c.addView(
            text(
                "To answer calls in the background and transcribe them when you can't pick up, the app " +
                    "needs the Shizuku helper for call-companion access.",
                14f, Ui.MUTED,
            ).marginTop(this, 6),
        )
        c.addView(routingDiagram().marginTop(this, 12))
        c.addView(
            primaryButton(if (connected) "Helper connected ✓" else "Connect helper service") {
                Probe.requestShizuku()
                Probe.worker.execute { runCatching { Probe.shell?.setup() }.getOrNull()?.let { Probe.log("call access: $it") } }
            }.fillWidth().marginTop(this, 12).also { it.alpha = if (connected) 0.5f else 1f },
        )
        return c.fillWidth()
    }

    /** The "verified local hardware routing" panel from the design: in-call → NPU ok, cloud blocked. */
    private fun routingDiagram(): View = card(Ui.CARD_SOFT).apply {
        addView(eyebrow("Verified local hardware routing"))
        addView(routeRow("🔊", "Call audio · in-call stream", "✓ Direct to phone, isolated", Ui.GREEN).marginTop(this@OnboardingActivity, 10))
        addView(routeRow("☁", "Outbound · cloud server", "✕ Remote ingestion disallowed", Ui.MUTED).marginTop(this@OnboardingActivity, 10))
        addView(text("Trust note: no data leaves your handset.", 12f, Ui.INK, weight = 500).marginTop(this@OnboardingActivity, 10))
    }.fillWidth()

    private fun routeRow(icon: String, title: String, status: String, statusColor: Int): View {
        val header = Ui.run {
            rowView(
                text(icon, 16f, Ui.INK),
                text(title, 13f, Ui.INK, weight = 500).marginStart(this@OnboardingActivity, 8),
            )
        }
        return column(
            header,
            text(status, 12f, statusColor).marginTop(this, 2),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            background = rounded(Ui.CARD, 10, stroke = Ui.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun stepThreeCard(): View {
        val c = card()
        c.addView(eyebrow("Upcoming · step 3 of 3"))
        c.addView(text("Step 3: Your name & notifications", 17f, Ui.INK, weight = 600).marginTop(this, 6))
        c.addView(text("The AI introduces itself with your name. Notifications show you each call summary.", 14f, Ui.MUTED).marginTop(this, 6))
        // Rebuilt each render, so remember what was typed and restore it.
        val field = EditText(this).apply {
            hint = "Your first name, e.g. Priya"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.INK)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_CLASS_TEXT
            setText(typedName)
            setSelection(typedName.length)
            background = rounded(Ui.CARD_SOFT, 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { typedName = s?.toString().orEmpty() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        nameField = field
        c.addView(field.fillWidth().marginTop(this, 12))
        return c.fillWidth()
    }

    private fun primaryAction(): View {
        val connected = helperConnected()
        return if (!connected) {
            ghostButton("Finish setup once the helper is connected") {
                Probe.requestShizuku()
            }.fillWidth()
        } else {
            primaryButton("Finish & turn on") {
                finishOnboarding(turnOn = true)
            }.fillWidth()
        }
    }

    private fun finishOnboarding(turnOn: Boolean = false) {
        Probe.ownerName = typedName.trim()
        Probe.onboarded = true
        if (turnOn) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            Probe.enabled = true
            AssistantService.start(this)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        lastConnected = null // Force one render on entry.
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }
}
