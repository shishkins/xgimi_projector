package os.z7.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Английская плашка поверх китайской «一键画面校正中» на экране автотрапеции.
 *
 * Калибровочный экран рисует com.xgimi.systemui (AK2Window, окно «trapezoidAdj», Lottie
 * assets/ak.json) — текст зашит в анимацию, перевести нельзя. Наше окно выше по слою
 * (accessibility overlay над application overlay) и закрывает плашку той же формы на том же месте.
 * Узоры для камеры не трогаем: плашка у XGIMI и так стоит на этом месте.
 */
class AkCover(private val service: Context) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val context = UiLanguage.wrap(service)
        val v = PillView(context)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }
        wm.addView(v, lp)
        view = v
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private class PillView(context: Context) : View(context) {
        private val label = context.getString(R.string.auto_keystone_running)
        private val icon = context.getDrawable(R.drawable.ic_keystone)!!
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ak_pill) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.text_primary)
            isFakeBoldText = true
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            // координаты плашки XGIMI на панели 1920×1080, с запасом, чтобы не просвечивали края
            val sx = width / Gmpf.PANEL_W.toFloat()
            val sy = height / Gmpf.PANEL_H.toFloat()
            rect.set(PILL_LEFT * sx, PILL_TOP * sy, PILL_RIGHT * sx, PILL_BOTTOM * sy)
            val r = rect.height() / 2
            canvas.drawRoundRect(rect, r, r, fill)

            text.textSize = rect.height() * TEXT_SCALE
            val iconSize = (rect.height() * ICON_SCALE).toInt()
            val gap = rect.height() * 0.18f
            val content = iconSize + gap + text.measureText(label)
            var x = rect.centerX() - content / 2
            val iconTop = (rect.centerY() - iconSize / 2).toInt()
            icon.setBounds(x.toInt(), iconTop, x.toInt() + iconSize, iconTop + iconSize)
            icon.draw(canvas)
            x += iconSize + gap
            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, x, baseline, text)
        }
    }

    private companion object {
        const val PILL_LEFT = 764f
        const val PILL_TOP = 918f
        const val PILL_RIGHT = 1156f
        const val PILL_BOTTOM = 1034f
        const val TEXT_SCALE = 0.30f
        const val ICON_SCALE = 0.36f
    }
}
