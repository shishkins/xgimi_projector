package os.z7.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Настройки картинки поверх видео — изменения видно сразу. Открывается плиткой «Изображение»
 * в панели (долгое «меню»). Слева — пункты на прозрачном градиенте, справа идёт видео.
 * Окно TYPE_ACCESSIBILITY_OVERLAY из [KeyService], как и сама панель.
 */
class PictureOverlay(private val service: Context) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }
    private var root: View? = null

    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val context = UiLanguage.wrap(service)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 28), Ui.dp(context, 28), Ui.dp(context, 28))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(context, context.getString(R.string.section_picture), 26f),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(Ui.text(context, context.getString(R.string.picture_overlay_back), 15f, secondary = true))
        column.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = Ui.dp(context, 16) })

        val controls = PictureControls(context, column,
            params = { LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = Ui.dp(context, 8) } },
            onError = { Toast.makeText(context, R.string.hardware_unavailable, Toast.LENGTH_SHORT).show() })
        // поверх видео строки непрозрачные — читаются на любой картинке
        controls.rows.forEach { it.background = context.getDrawable(R.drawable.card_solid_bg) }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(column)
        }
        val rootView = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                restartAutoHide()
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(SHADE_LEFT, SHADE_MID, SHADE_END))
            }, FrameLayout.LayoutParams(Ui.dp(context, SHADE_DP), MATCH, Gravity.START))
            addView(scroll, FrameLayout.LayoutParams(Ui.dp(context, PANEL_DP), MATCH, Gravity.START))
        }

        val params = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }

        root = rootView
        rootView.alpha = 0f
        scroll.translationX = -Ui.dp(context, OPEN_SHIFT_DP).toFloat()
        wm.addView(rootView, params)
        rootView.animate().alpha(1f).setDuration(OPEN_MS).start()
        scroll.animate().translationX(0f).setDuration(OPEN_MS).setInterpolator(DecelerateInterpolator(2f)).start()
        controls.first.requestFocus()
        controls.reload()
        restartAutoHide()
    }

    fun hide() {
        val view = root ?: return
        root = null
        main.removeCallbacks(autoHide)
        view.animate().alpha(0f).setDuration(CLOSE_MS).withEndAction {
            try { wm.removeView(view) } catch (_: Exception) {}
        }.start()
    }

    /** Убрать сразу (служба выключается). */
    fun dismiss() {
        val view = root ?: return
        root = null
        main.removeCallbacks(autoHide)
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    private fun restartAutoHide() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PANEL_DP = 500
        const val SHADE_DP = 820
        const val OPEN_SHIFT_DP = 28
        const val OPEN_MS = 160L
        const val CLOSE_MS = 160L
        const val AUTO_HIDE_MS = 60_000L
        const val SHADE_LEFT = 0xE6040710.toInt()
        const val SHADE_MID = 0xA6040710.toInt()
        const val SHADE_END = 0x00040710
    }
}
