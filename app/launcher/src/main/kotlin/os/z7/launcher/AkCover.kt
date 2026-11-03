package os.z7.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Английские плашки поверх китайских подписей автотрапеции XGIMI (com.xgimi.systemui):
 * - [Mode.RUNNING] — «一键画面校正中» на калибровочном экране (AK2Window, текст зашит в Lottie assets/ak.json);
 * - [Mode.DONE] — тост «画面校正完成，按 OK 手动校正» после калибровки (ToastDialog).
 * Перевести их нельзя, поэтому наше окно (accessibility overlay, выше по слою) закрывает их
 * плашкой той же формы на том же месте. Узоры для камеры не трогаем: плашка XGIMI и так там.
 */
class AkCover(private val service: Context) {

    enum class Mode { RUNNING, DONE }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }
    private var view: View? = null

    var mode: Mode? = null
        private set

    fun show(mode: Mode) {
        hide()
        val v = PillView(UiLanguage.wrap(service), mode)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }
        wm.addView(v, lp)
        view = v
        this.mode = mode
        // конца калибровки система не сообщает: снимаем по таймеру, если раньше не пришёл тост
        main.postDelayed(autoHide, if (mode == Mode.RUNNING) RUNNING_MAX_MS else DONE_MS)
    }

    fun hide() {
        main.removeCallbacks(autoHide)
        mode = null
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private class PillView(context: Context, private val mode: Mode) : View(context) {
        private val running = mode == Mode.RUNNING
        private val label = context.getString(if (running) R.string.auto_keystone_running else R.string.auto_keystone_done)
        private val hint = context.getString(R.string.auto_keystone_done_hint)
        private val icon: Drawable = context.getDrawable(R.drawable.ic_keystone)!!
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(if (running) R.color.ak_pill else R.color.ak_toast)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.text_primary)
            isFakeBoldText = running
        }
        private val dim = Paint(text).apply { color = context.getColor(R.color.text_secondary) }
        private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary) }
        private val capText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.ak_toast)
            isFakeBoldText = true
        }
        private val rect = RectF()
        private val capRect = RectF()

        override fun onDraw(canvas: Canvas) {
            // координаты плашек XGIMI на панели 1920×1080, с запасом, чтобы не просвечивали края
            val sx = width / Gmpf.PANEL_W.toFloat()
            val sy = height / Gmpf.PANEL_H.toFloat()
            val box = if (running) RUNNING_BOX else DONE_BOX
            rect.set(box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy)
            val h = rect.height()
            text.textSize = h * TEXT_SCALE
            dim.textSize = text.textSize
            capText.textSize = text.textSize * 0.62f
            val gap = h * 0.16f
            val iconSize = (h * ICON_SCALE).toInt()
            val capH = text.textSize * 1.15f
            val capW = capText.measureText(CAP) + capH * 0.6f
            val content = if (running) {
                iconSize + gap + text.measureText(label)
            } else {
                text.measureText(label + SEP) + capW + gap + dim.measureText(hint)
            }
            // не уже плашки XGIMI, а под длинный (русский) текст — шире, от того же центра
            val half = maxOf(rect.width(), content + h) / 2
            val cx = rect.centerX()
            rect.left = cx - half
            rect.right = cx + half
            canvas.drawRoundRect(rect, h / 2, h / 2, fill)

            val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2
            var x = cx - content / 2
            if (running) {
                val top = (rect.centerY() - iconSize / 2).toInt()
                icon.setBounds(x.toInt(), top, x.toInt() + iconSize, top + iconSize)
                icon.draw(canvas)
                x += iconSize + gap
                canvas.drawText(label, x, baseline, text)
            } else {
                // «Picture corrected · [OK] adjust manually»
                val capLabel = CAP
                val sep = SEP
                canvas.drawText(label + sep, x, baseline, text)
                x += text.measureText(label + sep)
                capRect.set(x, rect.centerY() - capH / 2, x + capW, rect.centerY() + capH / 2)
                canvas.drawRoundRect(capRect, capH / 2, capH / 2, cap)
                val capBase = capRect.centerY() - (capText.descent() + capText.ascent()) / 2
                canvas.drawText(capLabel, capRect.centerX() - capText.measureText(capLabel) / 2, capBase, capText)
                x += capW + gap
                canvas.drawText(hint, x, baseline, dim)
            }
        }
    }

    private companion object {
        /** Плашка «校正中»: left, top, right, bottom. */
        val RUNNING_BOX = floatArrayOf(764f, 918f, 1156f, 1034f)
        /** Тост «校正完成». */
        val DONE_BOX = floatArrayOf(700f, 936f, 1220f, 1042f)
        const val CAP = "OK"
        const val SEP = "  ·  "
        const val TEXT_SCALE = 0.30f
        const val ICON_SCALE = 0.36f
        const val RUNNING_MAX_MS = 25_000L
        /** Тост XGIMI держится ~3 с — наша плашка чуть дольше, чтобы его не было видно. */
        const val DONE_MS = 4_000L
    }
}
