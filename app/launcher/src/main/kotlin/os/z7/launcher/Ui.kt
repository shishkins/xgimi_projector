package os.z7.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Общие элементы наших экранов (центр управления, настройки, Wi-Fi): карточки, строки, ползунки.
 * Один стиль везде, как в меню игровой консоли: в покое — тёмное стекло, в фокусе карточка
 * становится белой, а текст и значки на ней — тёмными; лёгкое увеличение.
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

    /** Текст на карточке: цвет следует за фокусом карточки (белый → тёмный). */
    fun cardText(context: Context, value: CharSequence, sizeSp: Float, secondary: Boolean = false) =
        text(context, value, sizeSp).apply {
            setTextColor(context.getColorStateList(if (secondary) R.color.on_card_secondary else R.color.on_card))
            isDuplicateParentStateEnabled = true
        }

    fun icon(context: Context, res: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    /** Значок на карточке: перекрашивается вместе с текстом. */
    fun cardIcon(context: Context, res: Int, sizeDp: Int) = icon(context, res, sizeDp).apply {
        imageTintList = context.getColorStateList(R.color.on_card)
        isDuplicateParentStateEnabled = true
    }

    /** Погасить строку: не выбирается пультом и полупрозрачна. */
    fun setActive(view: View, active: Boolean) {
        view.isFocusable = active
        view.isClickable = active
        view.animate().alpha(if (active) 1f else INACTIVE_ALPHA).setDuration(FOCUS_ANIM_MS).start()
    }

    fun light(view: TextView) = view.apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }

    /** Фокусируемая карточка с мягким увеличением в фокусе. */
    fun focusable(view: View, scale: Float = FOCUS_SCALE) = view.apply {
        isFocusable = true
        isClickable = true
        background = context.getDrawable(R.drawable.card_bg)
        setOnFocusChangeListener { v, focused -> onFocus(v, focused, scale) }
    }

    /** Увеличение в фокусе; родителям разрешаем рисовать карточку за своими краями. */
    fun onFocus(v: View, focused: Boolean, scale: Float) {
        if (focused) unclipAncestors(v)
        val s = if (focused) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * Увеличенная карточка выходит за края родителя, а ViewGroup по умолчанию обрезает детей
     * по своим отступам — рамка фокуса выглядела срезанной. Прокрутку не трогаем: без обрезки
     * она рисовала бы уехавшие строки поверх заголовка.
     */
    fun unclipAncestors(view: View) {
        var p = view.parent
        while (p is ViewGroup) {
            if (p is ScrollView || p is HorizontalScrollView) {
                p.clipToPadding = false
                break
            }
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
        }
    }

    /** Квадратная плитка действия: значок сверху, подпись снизу, необязательная строка состояния. */
    class Tile(context: Context, iconRes: Int, label: String, status: String? = null) : LinearLayout(context) {
        private val statusView: TextView? = status?.let { cardText(context, it, 13f, secondary = true) }

        init {
            orientation = VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 12))
            addView(cardIcon(context, iconRes, 30))
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(cardText(context, label, 16f))
            statusView?.let { addView(it) }
            focusable(this)
        }

        fun setStatus(value: String) { statusView?.text = value }
    }

    /** Строка списка: значок, подпись, значение справа. */
    open class Row(context: Context, iconRes: Int?, label: String, value: String? = null) : LinearLayout(context) {
        private val labelView = cardText(context, label, 18f)
        protected val valueView = cardText(context, value ?: "", 16f, secondary = true).apply {
            setPadding(dp(context, 16), 0, 0, 0)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 60)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            if (iconRes != null) {
                addView(cardIcon(context, iconRes, 26).apply {
                    (layoutParams as LayoutParams).marginEnd = dp(context, 16)
                })
            }
            addView(labelView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
            focusable(this, ROW_FOCUS_SCALE)
        }

        fun setLabel(label: String) { labelView.text = label }

        open fun setValue(value: String) { valueView.text = value }
    }

    /**
     * Выбор из списка прямо в строке: влево/вправо и OK перебирают варианты, значение
     * применяется сразу. [onPick] получает ключ выбранного варианта.
     */
    class Choice(
        context: Context,
        iconRes: Int?,
        label: String,
        private val options: List<Pair<Int, String>>,
        private val onPick: (Int) -> Unit,
    ) : Row(context, iconRes, label, "—") {

        private var index = -1

        init {
            setOnClickListener { step(1) }
            setOnKeyListener { _, keyCode, event ->
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN) step(delta)
                true
            }
        }

        /** Показать текущее значение (прочитанное из железа); неизвестный ключ — «—». */
        fun show(key: Int?) {
            index = options.indexOfFirst { it.first == key }
            valueView.text = if (index >= 0) decorate(options[index].second) else "—"
        }

        private fun step(delta: Int) {
            if (options.isEmpty()) return
            index = if (index < 0) 0 else (index + delta + options.size) % options.size
            valueView.text = decorate(options[index].second)
            onPick(options[index].first)
        }

        private fun decorate(name: String) = "‹  $name  ›"
    }

    /** Переключатель: OK меняет состояние; [onChange] получает новое значение. */
    class Toggle(
        context: Context,
        iconRes: Int?,
        label: String,
        hint: String? = null,
        private val onChange: (Boolean) -> Unit,
    ) : LinearLayout(context) {

        private val switch = SwitchView(context)
        var checked: Boolean? = null
            private set

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 60)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            if (iconRes != null) {
                addView(cardIcon(context, iconRes, 26).apply {
                    (layoutParams as LayoutParams).marginEnd = dp(context, 16)
                })
            }
            val column = LinearLayout(context).apply { orientation = VERTICAL; isDuplicateParentStateEnabled = true }
            column.addView(cardText(context, label, 18f))
            if (hint != null) column.addView(cardText(context, hint, 14f, secondary = true).apply { maxLines = 2 })
            addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch, LayoutParams(dp(context, 52), dp(context, 30)).apply { marginStart = dp(context, 16) })
            focusable(this, ROW_FOCUS_SCALE)
            setOnClickListener {
                val now = checked ?: return@setOnClickListener
                set(!now)
                onChange(!now)
            }
        }

        /** Показать состояние; null — значение ещё не прочитано, переключать нельзя. */
        fun set(value: Boolean?) {
            checked = value
            switch.on = value == true
            switch.alpha = if (value == null) 0.4f else 1f
        }
    }

    /** Ползунок-«таблетка» переключателя. Цвета следуют за фокусом строки. */
    class SwitchView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var on = false
            set(v) { field = v; invalidate() }

        init { isDuplicateParentStateEnabled = true }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val focused = drawableState.contains(android.R.attr.state_focused)
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = when {
                on -> context.getColor(R.color.accent)
                focused -> context.getColor(R.color.bar_track_focused)
                else -> context.getColor(R.color.switch_off)
            }
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = context.getColor(R.color.text_primary)
            val knob = r - dp(context, 3)
            val cx = if (on) width - r else r
            canvas.drawCircle(cx, r, knob, paint)
        }
    }

    /**
     * Ползунок: влево/вправо на строке меняют значение сразу, без отдельного экрана.
     * [write] — запись значения; [format] — подпись справа.
     */
    class Slider(
        context: Context,
        iconRes: Int,
        label: String,
        private var min: Int,
        private var max: Int,
        private val format: (Int) -> String,
        private val write: (Int) -> Unit,
        private val step: Int = 1,
        /**
         * Ползунки в ряд (центр управления): стрелки двигают фокус, а значение меняется только
         * после OK — режим регулировки; OK, вверх или вниз из него выходят.
         */
        private val engageOnClick: Boolean = false,
    ) : LinearLayout(context) {

        private val valueView = cardText(context, "", 15f, secondary = true)
        private val bar = LevelBar(context)
        var value = min
            private set
        private var engaged = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            addView(cardIcon(context, iconRes, 26).apply { (layoutParams as LayoutParams).marginEnd = dp(context, 16) })
            val column = LinearLayout(context).apply { orientation = VERTICAL; isDuplicateParentStateEnabled = true }
            val top = LinearLayout(context).apply { orientation = HORIZONTAL; isDuplicateParentStateEnabled = true }
            top.addView(cardText(context, label, 16f), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(valueView)
            column.addView(top)
            column.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6)).apply {
                topMargin = dp(context, 10)
            })
            addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            focusable(this, ROW_FOCUS_SCALE)
            if (engageOnClick) {
                setOnClickListener { engage(!engaged) }
                setOnFocusChangeListener { v, focused ->
                    onFocus(v, focused, ROW_FOCUS_SCALE)
                    if (!focused) engage(false)
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (engageOnClick && !engaged) return@setOnKeyListener false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -step
                    KeyEvent.KEYCODE_DPAD_RIGHT -> step
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        engage(false)
                        return@setOnKeyListener false
                    }
                    else -> return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN) set(value + delta, notify = true)
                true
            }
        }

        private fun engage(on: Boolean) {
            if (engaged == on) return
            engaged = on
            bar.engaged = on
            valueView.text = label(value)
        }

        private fun label(v: Int) = if (engaged) "‹  ${format(v)}  ›" else format(v)

        fun setRange(min: Int, max: Int) {
            this.min = min
            this.max = maxOf(min + 1, max)
            set(value, notify = false)
        }

        fun set(v: Int, notify: Boolean) {
            val clamped = v.coerceIn(min, max)
            if (notify && clamped == value) return
            value = clamped
            valueView.text = label(clamped)
            bar.level = (clamped - min).toFloat() / (max - min)
            if (notify) write(clamped)
        }
    }

    /** Полоса уровня ползунка; на белой карточке дорожка темнеет. */
    class LevelBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
        private val rect = RectF()

        init { isDuplicateParentStateEnabled = true }

        var level = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        /** Режим регулировки: полоса толще. */
        var engaged = false
            set(v) { field = v; invalidate() }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val focused = drawableState.contains(android.R.attr.state_focused)
            track.color = context.getColor(if (focused) R.color.bar_track_focused else R.color.bar_track)
            // в покое полоса вдвое тоньше выделенного ей места, в режиме регулировки — во всю высоту
            val h = if (engaged) height.toFloat() else height / 2f
            val top = (height - h) / 2f
            val r = h / 2f
            rect.set(0f, top, width.toFloat(), top + h)
            canvas.drawRoundRect(rect, r, r, track)
            rect.right = maxOf(h, width * level)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }

    const val INACTIVE_ALPHA = 0.35f
    const val FOCUS_SCALE = 1.05f
    const val ROW_FOCUS_SCALE = 1.02f
    const val FOCUS_ANIM_MS = 140L
}
