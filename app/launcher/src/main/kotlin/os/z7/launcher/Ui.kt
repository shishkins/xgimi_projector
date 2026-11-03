package os.z7.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Общие элементы наших экранов (быстрая панель, настройки, Wi-Fi): карточки, строки, ползунки.
 * Один стиль везде — тёмное «стекло», в фокусе карточка светлеет и чуть увеличивается.
 */
object Ui {

    fun dp(context: Context, v: Int): Int = Tiles.dp(context, v)

    fun text(context: Context, value: CharSequence, sizeSp: Float, secondary: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(context.getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(context: Context, res: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    /** Фокусируемая карточка с мягким увеличением в фокусе. */
    fun focusable(view: View, scale: Float = FOCUS_SCALE) = view.apply {
        isFocusable = true
        isClickable = true
        background = context.getDrawable(R.drawable.card_bg)
        setOnFocusChangeListener { v, focused ->
            val s = if (focused) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Квадратная плитка действия: значок сверху, подпись снизу, необязательная строка состояния. */
    class Tile(context: Context, iconRes: Int, label: String, status: String? = null) : LinearLayout(context) {
        private val statusView: TextView? = status?.let { text(context, it, 13f, secondary = true) }

        init {
            orientation = VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 12))
            addView(icon(context, iconRes, 30))
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(text(context, label, 16f))
            statusView?.let { addView(it) }
            focusable(this)
        }

        fun setStatus(value: String) { statusView?.text = value }
    }

    /** Строка списка: значок, подпись, значение справа. */
    class Row(context: Context, iconRes: Int?, label: String, value: String? = null) : LinearLayout(context) {
        private val valueView = text(context, value ?: "", 16f, secondary = true)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 60)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            if (iconRes != null) {
                addView(icon(context, iconRes, 26).apply {
                    (layoutParams as LayoutParams).marginEnd = dp(context, 16)
                })
            }
            addView(text(context, label, 18f), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
            focusable(this, ROW_FOCUS_SCALE)
        }

        fun setValue(value: String) { valueView.text = value }
    }

    /**
     * Ползунок: влево/вправо на строке меняют значение сразу, без отдельного экрана.
     * [read] и [write] — источник значения; [format] — подпись справа.
     */
    class Slider(
        context: Context,
        iconRes: Int,
        label: String,
        private var min: Int,
        private var max: Int,
        private val format: (Int) -> String,
        private val write: (Int) -> Unit,
    ) : LinearLayout(context) {

        private val valueView = text(context, "", 15f, secondary = true)
        private val bar = LevelBar(context)
        var value = min
            private set

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            addView(icon(context, iconRes, 26).apply { (layoutParams as LayoutParams).marginEnd = dp(context, 16) })
            val column = LinearLayout(context).apply { orientation = VERTICAL }
            val top = LinearLayout(context).apply { orientation = HORIZONTAL }
            top.addView(text(context, label, 16f), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(valueView)
            column.addView(top)
            column.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6)).apply {
                topMargin = dp(context, 10)
            })
            addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            focusable(this, ROW_FOCUS_SCALE)
            setOnKeyListener { _, keyCode, event ->
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN) set(value + delta, notify = true)
                true
            }
        }

        fun setRange(min: Int, max: Int) {
            this.min = min
            this.max = maxOf(min + 1, max)
            set(value, notify = false)
        }

        fun set(v: Int, notify: Boolean) {
            val clamped = v.coerceIn(min, max)
            if (notify && clamped == value) return
            value = clamped
            valueView.text = format(clamped)
            bar.level = (clamped - min).toFloat() / (max - min)
            if (notify) write(clamped)
        }
    }

    /** Полоса уровня ползунка. */
    class LevelBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.bar_track) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
        private val rect = RectF()

        var level = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            rect.right = maxOf(height.toFloat(), width * level)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }

    const val FOCUS_SCALE = 1.05f
    const val ROW_FOCUS_SCALE = 1.02f
    const val FOCUS_ANIM_MS = 140L
}
