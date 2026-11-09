package app.offlinevoice.callprobe

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.offlinevoice.callprobe.Ui.card
import app.offlinevoice.callprobe.Ui.column
import app.offlinevoice.callprobe.Ui.dp
import app.offlinevoice.callprobe.Ui.eyebrow
import app.offlinevoice.callprobe.Ui.fillWidth
import app.offlinevoice.callprobe.Ui.ghostButton
import app.offlinevoice.callprobe.Ui.logoView
import app.offlinevoice.callprobe.Ui.marginStart
import app.offlinevoice.callprobe.Ui.marginTop
import app.offlinevoice.callprobe.Ui.primaryButton
import app.offlinevoice.callprobe.Ui.rounded
import app.offlinevoice.callprobe.Ui.rowView
import app.offlinevoice.callprobe.Ui.scaffold
import app.offlinevoice.callprobe.Ui.statusRow
import app.offlinevoice.callprobe.Ui.text
import app.offlinevoice.callprobe.Ui.weight

/** The Assistant hub: turn it on, set the persona, and see what it does. Second bottom-nav tab. */
class AssistantActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private lateinit var toggle: Switch
    private lateinit var heroCard: LinearLayout
    private lateinit var heroLine: TextView
    private lateinit var diagnostics: LinearLayout
    private lateinit var owner: EditText

    private val refresh = object : Runnable {
        override fun run() {
            renderDynamic()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.start(this)
        container = Ui.run { scaffold(selected = 1) }
        build()
        Probe.requestShizuku()
        if (Probe.enabled) AssistantService.start(this) else Probe.loadAi()
    }

    private fun build() {
        val c = container

        c.addView(
            Ui.run {
                rowView(
                    logoView(44),
                    column(
                        text("Assistant", 22f, Ui.INK, weight = 600),
                        text("Your on-device call receptionist", 13f, Ui.MUTED).marginTop(this@AssistantActivity, 1),
                    ).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) } },
                )
            },
        )

        // On/off hero.
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
        c.addView(heroCard.fillWidth().marginTop(this, 16))

        // Status.
        val diag = card()
        diag.addView(eyebrow("Status"))
        diagnostics = Ui.run { column() }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        diag.addView(diagnostics.marginTop(this, 10))
        diag.addView(
            ghostButton("Connect Shizuku") {
                Probe.requestShizuku()
                Probe.worker.execute { runCatching { Probe.shell?.setup() }.getOrNull()?.let { Probe.log("call access: $it") } }
            }.fillWidth().marginTop(this, 14),
        )
        c.addView(diag.fillWidth().marginTop(this, 12))

        // Persona.
        val persona = card()
        persona.addView(eyebrow("Persona"))
        persona.addView(text("The AI introduces itself with this name.", 13f, Ui.MUTED).marginTop(this, 2))
        owner = EditText(this).apply {
            hint = "Your first name, e.g. Priya"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.INK)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_CLASS_TEXT
            setText(Probe.ownerName)
            background = rounded(Ui.CARD_SOFT, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        persona.addView(owner.fillWidth().marginTop(this, 10))
        persona.addView(
            primaryButton("Save name") {
                Probe.ownerName = owner.text.toString().trim()
                toast("Saved. It applies from the next call.")
            }.fillWidth().marginTop(this, 10),
        )
        c.addView(persona.fillWidth().marginTop(this, 12))

        // How it works.
        val how = card()
        how.addView(eyebrow("How it works"))
        listOf(
            "1 · A call rings and you don't pick up.",
            "2 · After ${Probe.ringDelaySeconds}s, the AI answers and says it's an assistant.",
            "3 · It talks with the caller and takes a message.",
            "4 · You get a summary, transcript and recording.",
        ).forEachIndexed { i, line ->
            how.addView(text(line, 14f, Ui.INK).marginTop(this, if (i == 0) 10 else 8))
        }
        c.addView(how.fillWidth().marginTop(this, 12))

        renderDynamic()
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
        renderDynamic()
    }

    private fun renderDynamic() {
        val on = Probe.enabled
        heroCard.background = rounded(if (on) Ui.CARD_SOFT else Ui.CARD, 16).also { if (!on) it.setStroke(dp(1), Ui.LINE) }
        heroLine.text = if (on) "A call ringing ${Probe.ringDelaySeconds}s unanswered is answered by the AI." else "When off, calls ring as normal."

        val helperOk = Probe.shizukuDetail.startsWith("Helper connected")
        val aiReady = Probe.aiDetail == "AI ready"
        diagnostics.removeAllViews()
        diagnostics.addView(statusRow(if (aiReady) Ui.GREEN else Ui.AMBER, if (aiReady) "Assistant ready" else "Assistant loading", "Gemma 4 · on-device"))
        diagnostics.addView(statusRow(if (helperOk) Ui.GREEN else Ui.RED, if (helperOk) "Call access connected" else "Call access not connected", if (helperOk) "Telecom companion bridge" else Probe.shizukuDetail).marginTop(this, 10))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }
}
