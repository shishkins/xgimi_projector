package os.z7.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Ручная трапеция — свой экран вместо китайского. Окно на весь экран: его края и есть края
 * изображения (прошивка искажает весь вывод), поэтому рамка и угловые метки показывают,
 * куда уедет картинка. OK — следующий угол, стрелки — сдвиг угла, «Меню» — сброс, «Назад» — готово.
 */
object KeystoneScreen {

    private var root: View? = null

    /** Открыть поверх всего; false — служба кнопок не запущена (окно рисует только она). */
    fun show(context: Context): Boolean {
        val service = KeyService.instance ?: return false
        if (root != null) return true
        Screen(service).open()
        return true
    }

    private class Screen(private val service: Context) {
        private val context = UiLanguage.wrap(service)
        private val wm = service.getSystemService(WindowManager::class.java)
        private val frame = FrameView(context)
        private val cornerLabel = Ui.text(context, "", 20f, secondary = true)

        private var corners: IntArray? = null
        private var lastGood: IntArray? = null
        private var selected = 0
        private var pending: IntArray? = null
        private var busy = false

        private val view = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent) = onKey(event)
        }

        private val lp = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }

        fun open() {
            // лёгкое затемнение: рамка и ориентир читаются поверх любой картинки, а она видна
            frame.setBackgroundColor(context.getColor(R.color.keystone_scrim))
            view.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
            // карточка внизу: центр экрана занят ориентиром
            view.addView(card(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = Ui.dp(context, CARD_BOTTOM_DP)
            })
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.alpha = 0f
            wm.addView(view, lp)
            root = view
            view.requestFocus()
            view.animate().alpha(1f).setDuration(FADE_MS).start()
            select(0)
            Gmpf.run(service, { it.corners() }) { c ->
                if (c == null) cornerLabel.setText(R.string.action_failed) else {
                    corners = c
                    lastGood = c.copyOf()
                }
            }
        }

        private fun close() {
            if (root !== view) return
            root = null
            // пока окно гаснет, клавиши уже идут экрану под ним
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(view, lp)
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                try { wm.removeView(view) } catch (_: Exception) {}
            }.start()
        }

        private fun onKey(event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> if (!down) close()
                KeyEvent.KEYCODE_MENU -> if (down && event.repeatCount == 0) reset()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (down && event.repeatCount == 0) select((selected + 1) % 4)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(-1, 0, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(1, 0, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_UP -> if (down) move(0, -1, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(0, 1, event.repeatCount)
                else -> return false
            }
            return true
        }

        private fun select(index: Int) {
            selected = index
            cornerLabel.text = context.getString(CORNER_NAMES[index])
            frame.selected = index
        }

        /** Сдвиг выбранного угла; зажатая кнопка ускоряется. */
        private fun move(dx: Int, dy: Int, repeat: Int) {
            val c = corners?.copyOf() ?: return
            val speed = 1 + minOf(repeat, MAX_ACCEL_REPEATS) / ACCEL_EVERY
            val i = selected * 2
            c[i] = (c[i] + dx * STEP_X * speed).coerceIn(0, Gmpf.PANEL_W - 1)
            c[i + 1] = (c[i + 1] + dy * STEP_Y * speed).coerceIn(0, Gmpf.PANEL_H - 1)
            corners = c
            submit(c)
        }

        private fun submit(c: IntArray) {
            pending = c
            if (!busy) pump()
        }

        /** В прошивку уходит только последнее положение; недопустимое — откатываем. */
        private fun pump() {
            val c = pending ?: return
            pending = null
            busy = true
            Gmpf.run(service, { it.setCorners(c) }) { ok ->
                busy = false
                if (ok == true) {
                    lastGood = c
                } else if (pending == null) {
                    corners = lastGood?.copyOf()
                    frame.flash()
                }
                pump()
            }
        }

        private fun reset() {
            pending = null
            Gmpf.run(service, { it.resetKeystone(); it.corners() }) { c ->
                if (c != null) {
                    corners = c
                    lastGood = c.copyOf()
                }
            }
        }

        /** Одна строка: слева название и выбранный угол, справа подсказки по кнопкам. */
        private fun card(): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.panel_bg))
                cornerRadius = Ui.dp(context, 22).toFloat()
            }
            setPadding(Ui.dp(context, 28), Ui.dp(context, 16), Ui.dp(context, 22), Ui.dp(context, 16))
            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(Ui.text(context, context.getString(R.string.quick_keystone), 22f))
            titles.addView(cornerLabel.apply { textSize = 15f })
            addView(titles, LinearLayout.LayoutParams(Ui.dp(context, TITLE_DP), WRAP))
            for ((key, label) in HINTS) {
                addView(hint(context.getString(key), context.getString(label)))
            }
        }

        private fun hint(key: String, label: String) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            addView(TextView(context).apply {
                text = key
                setTextColor(context.getColor(R.color.text_primary))
                textSize = 14f
                background = context.getDrawable(R.drawable.key_cap)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 4))
            })
            addView(Ui.text(context, label, 15f, secondary = true).apply { setPadding(Ui.dp(context, 8), 0, 0, 0) })
        }
    }

    /** Рамка по краям окна, сетка для выравнивания и метки углов. */
    private class FrameView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 3).toFloat()
            color = context.getColor(R.color.text_primary)
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 1).toFloat()
            color = context.getColor(R.color.grid_line)
        }
        private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.handle_idle) }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 3).toFloat()
            color = context.getColor(R.color.text_primary)
        }
        private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 3).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.text_primary)
        }
        private val targetThin = Paint(target).apply {
            strokeWidth = Ui.dp(context, 2).toFloat()
            color = context.getColor(R.color.handle_idle)
        }
        private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
        private val dot = Ui.dp(context, 5).toFloat()
        private val error = context.getColor(R.color.error)
        private val accent = context.getColor(R.color.accent)
        private val radius = Ui.dp(context, 16).toFloat()
        private val inset = Ui.dp(context, 36).toFloat()
        private var pulse = 0f

        var selected = 0
            set(v) { field = v; invalidate() }

        private val pulser = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); pulser.start() }
        override fun onDetachedFromWindow() { pulser.cancel(); super.onDetachedFromWindow() }

        /** Угол упёрся: прошивка не принимает такую форму. */
        fun flash() {
            active.color = error
            postDelayed({ active.color = accent; invalidate() }, FLASH_MS)
            invalidate()
        }

        /**
         * Ориентир в центре: на ровной картинке круг круглый, а перекрестие прямое.
         * Сплющенный круг или косой крест сразу показывают, куда тянет изображение.
         */
        private fun drawTarget(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.drawCircle(cx, cy, r, target)
            canvas.drawCircle(cx, cy, r * INNER_RING, targetThin)
            val gap = r * CROSS_GAP
            val reach = r * CROSS_REACH
            canvas.drawLine(cx - reach, cy, cx - gap, cy, target)
            canvas.drawLine(cx + gap, cy, cx + reach, cy, target)
            canvas.drawLine(cx, cy - reach, cx, cy - gap, target)
            canvas.drawLine(cx, cy + gap, cx, cy + reach, target)
            canvas.drawCircle(cx, cy, dot, center)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val half = line.strokeWidth / 2
            for (k in 1 until GRID) {
                if (k * 2 == GRID) continue // через центр идёт перекрестие ориентира
                canvas.drawLine(w * k / GRID, 0f, w * k / GRID, h, grid)
                canvas.drawLine(0f, h * k / GRID, w, h * k / GRID, grid)
            }
            canvas.drawRect(half, half, w - half, h - half, line)
            drawTarget(canvas, w / 2, h / 2, h * TARGET_RADIUS)
            val points = listOf(inset to inset, w - inset to inset, w - inset to h - inset, inset to h - inset)
            points.forEachIndexed { i, (x, y) ->
                if (i == selected) {
                    canvas.drawCircle(x, y, radius * (1.15f + pulse * 0.25f), active)
                    canvas.drawCircle(x, y, radius * (1.15f + pulse * 0.25f), ring)
                } else {
                    canvas.drawCircle(x, y, radius * 0.7f, idle)
                }
            }
        }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val FADE_MS = 180L
    private const val PULSE_MS = 700L
    private const val FLASH_MS = 300L
    private const val GRID = 4
    private const val CARD_BOTTOM_DP = 48
    private const val TITLE_DP = 230

    /** Ориентир: радиус круга — доля высоты экрана, остальное — доли радиуса. */
    private const val TARGET_RADIUS = 0.26f
    private const val INNER_RING = 0.35f
    private const val CROSS_GAP = 0.12f
    private const val CROSS_REACH = 1.18f

    /** Шаг сдвига угла в пикселях панели (как у XGIMI: 6 × 3.375). */
    private const val STEP_X = 6
    private const val STEP_Y = 4
    private const val MAX_ACCEL_REPEATS = 15
    private const val ACCEL_EVERY = 3

    private val CORNER_NAMES = intArrayOf(
        R.string.corner_top_left, R.string.corner_top_right, R.string.corner_bottom_right, R.string.corner_bottom_left,
    )
    private val HINTS = listOf(
        R.string.key_ok to R.string.hint_next_corner,
        R.string.key_arrows to R.string.hint_move,
        R.string.key_menu to R.string.hint_reset,
        R.string.key_back to R.string.hint_done,
    )
}
