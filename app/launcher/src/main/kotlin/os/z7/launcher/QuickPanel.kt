package os.z7.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Быстрая панель (долгое нажатие «меню» на пульте) — замена китайского быстрого меню XGIMI.
 * Рисуется окном TYPE_ACCESSIBILITY_OVERLAY из [KeyService]: поверх любого приложения,
 * без разрешения на оверлеи и без паузы видео под ней (activity поставила бы его на паузу).
 */
class QuickPanel(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var root: View? = null
    private var volumeValue: TextView? = null

    val isShown: Boolean get() = root != null

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        if (isShown) return
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        list.addView(TextView(context).apply {
            text = context.getString(R.string.quick_title)
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(12), 0, 0, dp(16))
        })

        list.addView(row(R.drawable.ic_focus, R.string.quick_auto_focus) { closeThen { Projector.autoFocus(context) } })
        list.addView(row(R.drawable.ic_keystone, R.string.quick_auto_keystone) { closeThen { Projector.autoKeystone(context) } })
        list.addView(row(R.drawable.ic_focus, R.string.quick_manual_focus) { closeThen { Projector.manualFocus(context) } })
        list.addView(row(R.drawable.ic_keystone, R.string.quick_manual_keystone) { stock(StockSettings.KEYSTONE) })
        list.addView(row(R.drawable.ic_zoom, R.string.quick_zoom) { stock(StockSettings.ZOOM) })
        list.addView(volumeRow())
        list.addView(row(R.drawable.ic_brightness, R.string.quick_brightness) { stock(StockSettings.BRIGHTNESS) })
        list.addView(row(R.drawable.ic_picture, R.string.quick_picture_mode) { stock(StockSettings.PICTURE_MODE) })
        list.addView(row(R.drawable.ic_wifi, R.string.wifi, wifiStatus()) { stock(StockSettings.WIFI) })
        list.addView(row(R.drawable.ic_bluetooth, R.string.quick_bluetooth) { stock(StockSettings.BLUETOOTH) })
        list.addView(row(R.drawable.ic_settings, R.string.quick_all_settings) { stock(StockSettings.MAIN) })

        val panel = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.panel_bg))
                cornerRadii = floatArrayOf(0f, 0f, r(), r(), r(), r(), 0f, 0f)
            }
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            })
        }

        val lp = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            dimAmount = 0.35f
        }
        wm.addView(panel, lp)
        root = panel
        panel.translationX = -dp(PANEL_WIDTH_DP).toFloat()
        panel.animate().translationX(0f).setDuration(ANIM_MS).start()
        list.getChildAt(1).requestFocus()
    }

    fun hide() {
        val view = root ?: return
        root = null
        volumeValue = null
        wm.removeView(view)
    }

    /** Действие, у которого свой экран: панель закрываем, чтобы не перекрывать его. */
    private fun closeThen(action: () -> Unit) {
        hide()
        action()
    }

    private fun stock(page: String) = closeThen { StockSettings.open(context, page) }

    private fun row(iconRes: Int, labelRes: Int, value: String? = null, onClick: () -> Unit): View {
        val row = baseRow()
        row.addView(ImageView(context).apply {
            setImageResource(iconRes)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(16) })
        row.addView(label(labelRes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) row.addView(secondary(value))
        row.setOnClickListener { onClick() }
        return row
    }

    /** Громкость прямо в панели: влево/вправо на строке. */
    private fun volumeRow(): View {
        val row = baseRow()
        row.addView(ImageView(context).apply { setImageResource(R.drawable.ic_volume) },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(16) })
        row.addView(label(R.string.quick_volume), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = secondary(volumeText())
        volumeValue = value
        row.addView(value)
        row.setOnKeyListener { _, keyCode, event ->
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.ADJUST_RAISE
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                value.text = volumeText()
            }
            true
        }
        return row
    }

    private fun volumeText(): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "‹  ${cur * 100 / max}%  ›"
    }

    private fun wifiStatus(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val on = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return context.getString(if (on) R.string.quick_connected else R.string.quick_disconnected)
    }

    private fun baseRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = context.getDrawable(R.drawable.row_bg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(4) }
    }

    private fun label(res: Int) = TextView(context).apply {
        text = context.getString(res)
        setTextColor(context.getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun secondary(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(context.getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun dp(v: Int) = Tiles.dp(context, v)
    private fun r() = dp(20).toFloat()

    private companion object {
        const val PANEL_WIDTH_DP = 400
        const val ANIM_MS = 150L
    }
}
