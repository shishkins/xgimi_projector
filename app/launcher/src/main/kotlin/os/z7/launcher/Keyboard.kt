package os.z7.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Экранная клавиатура под пульт (стрелки + OK) — вместо китайской Sogou из прошивки.
 * Буквы, заглавные, цифры и символы; ввод отдаётся в [onText], удаление — в [onDelete].
 */
class Keyboard(
    context: Context,
    private val onText: (String) -> Unit,
    private val onDelete: () -> Unit,
    private val onDone: () -> Unit,
    doneLabel: String,
) : LinearLayout(context) {

    private var shift = false
    private var symbols = false
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(rows, LayoutParams(WRAP, WRAP))
        val special = row()
        special.addView(key(context.getString(R.string.key_shift), WIDE) { shift = !shift; render() })
        special.addView(key(context.getString(R.string.key_symbols), WIDE) { symbols = !symbols; render() })
        special.addView(key(context.getString(R.string.key_space), SPACE) { onText(" ") })
        special.addView(key("⌫", WIDE) { onDelete() })
        special.addView(key(doneLabel, DONE) { onDone() }.apply { setTextColor(context.getColor(R.color.accent)) })
        addView(special, LayoutParams(WRAP, WRAP))
        render()
    }

    /** Фокус на первую клавишу — чтобы сразу набирать. */
    fun focusFirst() {
        ((rows.getChildAt(0) as? LinearLayout)?.getChildAt(0))?.requestFocus()
    }

    private fun render() {
        val focusedIndex = focusedKeyPosition()
        rows.removeAllViews()
        for (line in if (symbols) SYMBOLS else LETTERS) {
            val r = row()
            for (ch in line) {
                val label = if (shift && !symbols) ch.uppercaseChar().toString() else ch.toString()
                r.addView(key(label, KEY) { onText(label) })
            }
            rows.addView(r, LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        focusedIndex?.let { (row, col) ->
            ((rows.getChildAt(row) as? LinearLayout)?.let { it.getChildAt(minOf(col, it.childCount - 1)) })?.requestFocus()
        }
    }

    private fun focusedKeyPosition(): Pair<Int, Int>? {
        for (r in 0 until rows.childCount) {
            val line = rows.getChildAt(r) as LinearLayout
            for (c in 0 until line.childCount) if (line.getChildAt(c).isFocused) return r to c
        }
        return null
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun key(label: String, widthDp: Int, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(context.getColorStateList(R.color.on_card))
        Ui.focusable(this, KEY_FOCUS_SCALE)
        setOnClickListener { action() }
        layoutParams = LayoutParams(Ui.dp(context, widthDp), Ui.dp(context, KEY_HEIGHT)).apply {
            val m = Ui.dp(context, 3)
            setMargins(m, m, m, m)
        }
    }

    private companion object {
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val KEY = 56
        const val WIDE = 96
        const val SPACE = 200
        const val DONE = 150
        const val KEY_HEIGHT = 52
        const val KEY_FOCUS_SCALE = 1.12f
        val LETTERS = listOf("1234567890", "qwertyuiop", "asdfghjkl@", "zxcvbnm.-_")
        val SYMBOLS = listOf("1234567890", "!@#$%^&*()", "+=[]{}<>/\\", "'\";:,.?~`|")
    }
}
