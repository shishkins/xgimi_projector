package os.z7.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Наш экран ожидания Miracast поверх стокового WifiDisplayActivity (com.xgimi.wirelessscreen).
 * Приём Windows ведёт сток (его служба и окно MirCastActivity с картинкой), мы только закрываем
 * его китайский экран своим. Окно — accessibility overlay без фокуса и касаний: кнопки пульта
 * идут стоку, «Назад» закрывает режим как раньше. Когда Windows подключилась (MirCastActivity)
 * или пользователь ушёл в другое приложение — экран снимаем.
 */
class MiracastCover(private val service: Context) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var pulse: ValueAnimator? = null

    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val ctx = UiLanguage.wrap(service)
        val ring = PulseView(ctx)
        val root = FrameLayout(ctx).apply {
            background = ctx.getDrawable(R.drawable.bg_wallpaper)
            addView(content(ctx, ring), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(Ui.text(ctx, ctx.getString(R.string.miracast_footer), 15f, secondary = true).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = Ui.dp(ctx, 48) })
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { windowAnimations = 0 }
        wm.addView(root, lp)
        view = root
        root.animate().alpha(1f).setDuration(FADE_MS).start()
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { ring.phase = it.animatedValue as Float }
            start()
        }
    }

    fun hide() {
        pulse?.cancel()
        pulse = null
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun content(ctx: Context, ring: PulseView) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val iconBox = FrameLayout(ctx).apply {
            addView(ring, FrameLayout.LayoutParams(Ui.dp(ctx, RING_DP), Ui.dp(ctx, RING_DP)))
            addView(Ui.icon(ctx, R.drawable.ic_cast, 72), FrameLayout.LayoutParams(
                Ui.dp(ctx, 72), Ui.dp(ctx, 72), Gravity.CENTER))
        }
        addView(iconBox, LinearLayout.LayoutParams(Ui.dp(ctx, RING_DP), Ui.dp(ctx, RING_DP)))
        addView(Ui.text(ctx, ctx.getString(R.string.cast_from_windows), 40f).apply {
            paint.isFakeBoldText = true
        }, wrap(ctx, top = 8))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, ctx.getString(R.string.miracast_step_press), 22f, secondary = true))
            addView(keyCap(ctx, "Win"), wrap(ctx, start = 14))
            addView(Ui.text(ctx, "+", 22f, secondary = true), wrap(ctx, start = 10))
            addView(keyCap(ctx, "K"), wrap(ctx, start = 10))
            addView(Ui.text(ctx, ctx.getString(R.string.miracast_step_choose), 22f, secondary = true), wrap(ctx, start = 14))
        }, wrap(ctx, top = 28))
        addView(Ui.text(ctx, deviceName(ctx), 30f).apply {
            paint.isFakeBoldText = true
            background = ctx.getDrawable(R.drawable.key_cap)
            setPadding(Ui.dp(ctx, 28), Ui.dp(ctx, 10), Ui.dp(ctx, 28), Ui.dp(ctx, 12))
        }, wrap(ctx, top = 20))
    }

    private fun keyCap(ctx: Context, label: String): TextView = Ui.text(ctx, label, 20f).apply {
        paint.isFakeBoldText = true
        background = ctx.getDrawable(R.drawable.key_cap)
        setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 6))
    }

    private fun wrap(ctx: Context, top: Int = 0, start: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = Ui.dp(ctx, top)
        marginStart = Ui.dp(ctx, start)
    }

    /** Имя, под которым сток объявляет проектор в Win+K (настройки XGIMI), иначе — наше по умолчанию. */
    private fun deviceName(ctx: Context): String = try {
        ctx.contentResolver.query(Uri.parse(XGIMI_SETTINGS), arrayOf(NAME_COLUMN), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    } catch (_: Exception) {
        null
    } ?: DEFAULT_NAME

    /** Мягкие расходящиеся круги вокруг значка: «жду подключения». */
    private class PulseView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(context, 2).toFloat()
            color = context.getColor(R.color.accent)
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tile_idle) }

        var phase = 0f
            set(value) { field = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val max = minOf(cx, cy) - paint.strokeWidth
            val min = max * 0.48f
            canvas.drawCircle(cx, cy, min, core)
            for (i in 0 until RINGS) {
                val t = (phase + i.toFloat() / RINGS) % 1f
                paint.alpha = ((1f - t) * 200).toInt()
                canvas.drawCircle(cx, cy, min + (max - min) * t, paint)
            }
        }
    }

    private companion object {
        const val XGIMI_SETTINGS = "content://com.xgimi.appdbsettings/Settings"
        const val NAME_COLUMN = "XgimiDeviceName"
        const val DEFAULT_NAME = "XGIMI Projector"
        const val RING_DP = 220
        const val RINGS = 3
        const val PULSE_MS = 2_400L
        const val FADE_MS = 220L
    }
}
