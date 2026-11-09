package app.offlinevoice.callprobe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app's monochrome design system, matching the Stitch screens. Built in code (no XML) so the
 * whole look lives in one place and the screens stay short.
 */
object Ui {
    // Zinc monochrome palette, straight from the Stitch Tailwind theme.
    const val CANVAS = 0xFFF9F9F9.toInt() // surface
    const val CARD = 0xFFFFFFFF.toInt() // white card
    const val CARD_SOFT = 0xFFF3F3F4.toInt() // surface-container-low, for nested panels
    const val INK = 0xFF1A1C1C.toInt() // on-surface
    const val MUTED = 0xFF6B7682.toInt() // zinc-500
    const val FAINT = 0xFF9AA0A6.toInt() // zinc-400
    const val LINE = 0xFFE2E2E2.toInt() // surface-variant / borders
    const val BLACK = 0xFF000000.toInt() // primary
    const val GREEN = 0xFF1B7A3D.toInt() // live/ok dot
    const val AMBER = 0xFFB4690E.toInt() // loading dot
    const val RED = 0xFFBA1A1A.toInt() // error dot

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Light canvas with dark status-bar icons, and top padding so content clears the status bar. */
    fun android.app.Activity.applyChrome(content: View) {
        window.statusBarColor = CANVAS
        window.decorView.setBackgroundColor(CANVAS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(28)
        content.setPadding(content.paddingLeft, content.paddingTop + statusBarHeight, content.paddingRight, content.paddingBottom)
    }

    fun Context.text(
        content: CharSequence,
        size: Float,
        color: Int = INK,
        weight: Int = 400,
    ) = TextView(this).apply {
        text = content
        textSize = size
        setTextColor(color)
        if (weight >= 600) setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        else if (weight >= 500) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    /** The app logo as an ImageView, sized in dp. */
    fun Context.logoView(sizeDp: Int) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_logo)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Small uppercase label used for section eyebrows, e.g. "SYSTEM DIAGNOSTIC". */
    fun Context.eyebrow(content: String) =
        text(content.uppercase(), 11f, MUTED, weight = 600).apply {
            letterSpacing = 0.08f
            setPadding(dp(2), 0, 0, 0) // Nudge in so it lines up with card content.
        }

    fun Context.column(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        children.forEach(::addView)
    }

    fun Context.rowView(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        children.forEach(::addView)
    }

    fun Context.card(fill: Int = CARD, radius: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(fill, radius)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    /** A rounded rectangle, optionally outlined, for cards and nested panels. */
    fun Context.rounded(fill: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** A small coloured status dot followed by text, as on the diagnostic rows. */
    fun Context.statusRow(dotColor: Int, title: CharSequence, subtitle: CharSequence?): LinearLayout {
        val dot = TextView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { topMargin = dp(6); marginEnd = dp(12) }
        }
        val lines = column(text(title, 15f, INK, weight = 500)).apply {
            if (subtitle != null) addView(text(subtitle, 12f, MUTED).marginTop(this@statusRow, 2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dot)
            addView(lines)
        }
    }

    /** A filled black pill button, the app's primary action style. */
    fun Context.primaryButton(label: CharSequence, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        background = rounded(BLACK, 14)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A bordered white pill button, the secondary action style. */
    fun Context.ghostButton(label: CharSequence, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(INK)
        gravity = Gravity.CENTER
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        background = rounded(CARD, 14, stroke = LINE)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---- layout-param helpers, so callers stay terse ----

    fun <T : View> T.marginTop(context: Context, value: Int): T = apply {
        lp().topMargin = context.dp(value)
    }

    fun <T : View> T.marginStart(context: Context, value: Int): T = apply {
        lp().marginStart = context.dp(value)
    }

    fun <T : View> T.fillWidth(): T = apply { lp().width = MATCH_PARENT }

    fun <T : View> T.weight(value: Float): T = apply {
        val p = lp()
        p.width = 0
        p.weight = value
    }

    private fun View.lp(): LinearLayout.LayoutParams {
        val existing = layoutParams as? LinearLayout.LayoutParams
        return existing ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { layoutParams = it }
    }

    /** Standard vertical gap between stacked cards. */
    fun gap(context: Context): ViewGroup.MarginLayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(12) }

    /**
     * A screen scaffold: scrollable content with a fixed bottom nav bar (Calls / Assistant /
     * Settings). [selected] is 0, 1 or 2. Returns the content column to fill.
     */
    fun android.app.Activity.scaffold(selected: Int): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CANVAS)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(24))
        }
        applyChrome(content)
        outer.addView(
            android.widget.ScrollView(this).apply {
                isFillViewport = true
                addView(content)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            },
        )
        outer.addView(bottomNav(selected))
        setContentView(outer)
        return content
    }

    private fun android.app.Activity.bottomNav(selected: Int): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(0, dp(8), 0, dp(10))
            // A hairline top border.
            val line = View(this@bottomNav).apply {
                setBackgroundColor(LINE)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
            }
            addView(line)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = listOf("📞" to "Calls", "🤖" to "Assistant", "⚙" to "Settings")
        tabs.forEachIndexed { index, (icon, label) ->
            val active = index == selected
            val tab = column(
                text(icon, 18f, if (active) INK else FAINT).apply { gravity = Gravity.CENTER },
                text(label, 11f, if (active) INK else FAINT, weight = if (active) 600 else 400).apply {
                    gravity = Gravity.CENTER
                }.marginTop(this@bottomNav, 2),
            ).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener { onTab(index, selected) }
            }
            row.addView(tab)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
            addView(View(this@bottomNav).apply {
                setBackgroundColor(LINE)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
            })
            setPadding(0, dp(8), 0, dp(10))
            addView(row)
        }
    }

    private fun android.app.Activity.onTab(index: Int, current: Int) {
        if (index == current) return
        val target = when (index) {
            0 -> MainActivity::class.java
            1 -> AssistantActivity::class.java
            2 -> SettingsActivity::class.java
            else -> return
        }
        startActivity(
            android.content.Intent(this, target)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        overridePendingTransition(0, 0)
    }

    /** A settings-style row: title + subtitle on the left, an optional control view on the right. */
    fun Context.settingRow(title: CharSequence, subtitle: CharSequence?, control: View? = null): View {
        val lines = column(text(title, 15f, INK, weight = 500)).apply {
            if (subtitle != null) addView(text(subtitle, 12f, MUTED).marginTop(this@settingRow, 2))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lines)
            if (control != null) addView(control)
        }
    }

    /** A segmented pill control. [options] are labels; calls [onPick] with the chosen index. */
    fun Context.segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(CARD_SOFT, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { index, label ->
            val active = index == selected
            container.addView(
                text(label, 13f, if (active) Color.WHITE else INK, weight = 500).apply {
                    gravity = Gravity.CENTER
                    background = if (active) rounded(BLACK, 9) else null
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    isClickable = true
                    setOnClickListener { onPick(index) }
                },
            )
        }
        return container
    }
}
