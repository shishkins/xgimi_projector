package os.z7.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Центр управления (долгое нажатие «меню» на пульте) — замена быстрого меню XGIMI.
 * Как на игровой консоли: панель выезжает снизу поверх любого приложения, видео над ней
 * продолжает идти. Сверху ползунки (громкость, яркость, размер), ниже — ряд кнопок.
 * Окно TYPE_ACCESSIBILITY_OVERLAY из [KeyService]: без паузы приложения под ним.
 */
class QuickPanel(private val service: Context) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }

    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var scrim: View? = null
    private var closing = false
    private var firstFocus: View? = null

    val isShown: Boolean get() = root != null && !closing

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        if (root != null) return
        // язык интерфейса мог смениться в настройках — каждый раз свежий контекст
        val context = UiLanguage.wrap(service)

        val scrimView = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(context.getColor(R.color.scrim), 0x00000000))
        }
        val panelView = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.panel_bg))
                val r = Ui.dp(context, 28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(build(context))
        }
        val rootView = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (closing) return true
                restartAutoHide()
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            addView(scrimView, FrameLayout.LayoutParams(MATCH, Ui.dp(context, SCRIM_DP), Gravity.BOTTOM))
            addView(panelView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }

        val params = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }

        scrimView.alpha = 0f
        panelView.visibility = View.INVISIBLE
        root = rootView
        panel = panelView
        scrim = scrimView
        closing = false
        lp = params
        wm.addView(rootView, params)

        // высоту панели знаем только после раскладки — тогда прячем её вниз и выдвигаем
        rootView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                rootView.viewTreeObserver.removeOnPreDrawListener(this)
                panelView.translationY = panelView.height.toFloat()
                panelView.visibility = View.VISIBLE
                panelView.animate().translationY(0f).setDuration(OPEN_MS)
                    .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
                scrimView.animate().alpha(1f).setDuration(OPEN_MS).start()
                return true
            }
        })
        firstFocus?.requestFocus()
        closeStockMenu()
        restartAutoHide()
    }

    fun hide() {
        val rootView = root ?: return
        if (closing) return
        closing = true
        main.removeCallbacks(autoHide)
        // пока панель уезжает, клавиши уже идут приложению под ней
        lp?.let {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(rootView, it)
        }
        val panelView = panel!!
        panelView.animate().translationY(panelView.height.toFloat()).setDuration(CLOSE_MS)
            .setInterpolator(AccelerateInterpolator(1.5f)).withLayer()
            .withEndAction { remove(rootView) }.start()
        scrim?.animate()?.alpha(0f)?.setDuration(CLOSE_MS)?.start()
    }

    /** Убрать окно сразу, без анимации (служба выключается). */
    fun dismiss() {
        root?.let { remove(it) }
    }

    private fun remove(view: View) {
        if (root !== view) return
        root = null
        lp = null
        panel = null
        scrim = null
        closing = false
        firstFocus = null
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    private fun build(context: Context): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 48), dp(context, 22), dp(context, 48), dp(context, 26))
            clipChildren = false
            clipToPadding = false
        }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(context, context.getString(R.string.quick_title), 22f),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(Ui.text(context, context.getString(R.string.quick_hint), 15f, secondary = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(context, 28) })
        header.addView(Ui.light(Ui.text(context, SimpleDateFormat("HH:mm", UiLanguage.locale(context)).format(Date()), 22f)))
        column.addView(header)

        // ползунки
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = Ui.Slider(context, R.drawable.ic_volume, context.getString(R.string.quick_volume), 0, maxVolume,
            format = { "${it * 100 / maxVolume}%" },
            write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }, engageOnClick = true)
        volume.set(audio.getStreamVolume(AudioManager.STREAM_MUSIC), notify = false)

        val brightnessWriter = Gmpf.Latest<Int>(service) { b, v -> b.setBrightness(v) }
        val brightness = Ui.Slider(context, R.drawable.ic_brightness, context.getString(R.string.quick_brightness),
            1, Gmpf.BRIGHTNESS_MAX, format = { "$it / ${Gmpf.BRIGHTNESS_MAX}" }, write = brightnessWriter::submit,
            engageOnClick = true)
        brightness.set(Gmpf.BRIGHTNESS_MAX, notify = false)

        // размер изображения: вправо — больше; у прошивки наоборот (шаг 0 — полный размер)
        var zoomMax = Gmpf.ZOOM_MAX_DEFAULT
        val zoomWriter = Gmpf.Latest<Int>(service) { b, v -> b.setZoom(v) }
        val size = Ui.Slider(context, R.drawable.ic_zoom, context.getString(R.string.quick_size), 0, zoomMax,
            format = { if (it == zoomMax) context.getString(R.string.size_full) else "−${zoomMax - it}" },
            write = { zoomWriter.submit(zoomMax - it) }, engageOnClick = true)
        size.set(zoomMax, notify = false)

        val sliders = LinearLayout(context).apply { clipChildren = false }
        listOf(volume, brightness, size).forEachIndexed { i, s ->
            sliders.addView(s, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = dp(context, 14)
            })
        }
        column.addView(sliders, block(context, 18))

        // кнопки
        val autoKeystone = Action(context, R.drawable.ic_keystone, context.getString(R.string.quick_auto_keystone))
        val keystone = Action(context, R.drawable.ic_corners, context.getString(R.string.quick_keystone))
        val autoFocus = Action(context, R.drawable.ic_focus, context.getString(R.string.quick_auto_focus))
        val focus = Action(context, R.drawable.ic_focus_manual, context.getString(R.string.quick_manual_focus))
        val picture = Action(context, R.drawable.ic_picture, context.getString(R.string.picture_mode), "—")
        val hdmi = Action(context, R.drawable.ic_hdmi, "HDMI")
        val wifi = Action(context, R.drawable.ic_wifi, context.getString(R.string.wifi), Wifi.status(context))
        val settings = Action(context, R.drawable.ic_settings, context.getString(R.string.settings))

        autoKeystone.setOnClickListener {
            closeThen {
                Gmpf.run(service, { it.autoKeystone() }) { ok ->
                    // ход калибровки показывает экран XGIMI (с нашей плашкой, см. AkCover)
                    if (ok != true) toast(context, R.string.action_failed)
                }
            }
        }
        keystone.setOnClickListener { closeThen { KeystoneScreen.show(service) } }
        autoFocus.setOnClickListener { closeThen { Projector.autoFocus(service) } }
        focus.setOnClickListener { closeThen { Projector.manualFocus(service) } }
        hdmi.setOnClickListener { closeThen { HdmiActivity.open(service) } }
        wifi.setOnClickListener { closeThen { start(WifiActivity::class.java) } }
        settings.setOnClickListener { closeThen { start(SettingsActivity::class.java) } }

        // режим изображения перебирается прямо здесь: OK — следующий
        var mode: Int? = null
        fun modeName(m: Int?) = Hw.PICTURE_MODES.firstOrNull { it.first == m }?.let { context.getString(it.second) } ?: "—"
        picture.setOnClickListener {
            val current = Hw.PICTURE_MODES.indexOfFirst { it.first == mode }
            val next = Hw.PICTURE_MODES[(current + 1) % Hw.PICTURE_MODES.size].first
            mode = next
            picture.setStatus(modeName(next))
            Gmpf.run(service, { Hw.setPictureMode(it, next) })
        }

        val actions = LinearLayout(context).apply { clipChildren = false }
        listOf(autoKeystone, keystone, autoFocus, focus, picture, hdmi, wifi, settings).forEachIndexed { i, a ->
            actions.addView(a, LinearLayout.LayoutParams(dp(context, ACTION_DP), WRAP).apply {
                if (i > 0) marginStart = dp(context, 10)
            })
        }
        column.addView(actions, block(context, 22))
        firstFocus = autoKeystone

        Gmpf.run(service, { b -> Triple(b.brightness(), b.zoomMax(), b.zoom()) to Hw.pictureMode(b) }) { values ->
            if (values == null) return@run
            val (level, max, step) = values.first
            brightness.set(level, notify = false)
            zoomMax = max
            size.setRange(0, max)
            size.set(max - step, notify = false)
            mode = values.second
            picture.setStatus(modeName(values.second))
        }
        return column
    }

    /** Кнопка ряда: карточка со значком (белеет в фокусе), подпись и строка состояния под ней. */
    private class Action(context: Context, iconRes: Int, label: String, status: String? = null) : LinearLayout(context) {
        private val card = FrameLayout(context)
        private val statusView: TextView? = status?.let { small(context, it, 12f) }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            card.background = context.getDrawable(R.drawable.card_bg)
            card.isDuplicateParentStateEnabled = true
            val size = Ui.dp(context, ICON_DP)
            card.addView(Ui.cardIcon(context, iconRes, ICON_DP), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(card, LayoutParams(Ui.dp(context, CARD_DP), Ui.dp(context, CARD_DP)))
            addView(small(context, label, 14f), LayoutParams(WRAP, WRAP).apply { topMargin = Ui.dp(context, 8) })
            statusView?.let { addView(it) }
            setOnFocusChangeListener { _, focused ->
                if (focused) Ui.unclipAncestors(card)
                val s = if (focused) 1.1f else 1f
                card.animate().scaleX(s).scaleY(s).setDuration(Ui.FOCUS_ANIM_MS).setInterpolator(DecelerateInterpolator()).start()
            }
        }

        fun setStatus(value: String) { statusView?.text = value }

        private companion object {
            const val CARD_DP = 76
            const val ICON_DP = 32

            fun small(context: Context, value: String, sp: Float) = TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(context.getColorStateList(R.color.label_state))
                isDuplicateParentStateEnabled = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
        }
    }

    private fun block(context: Context, topDp: Int) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(context, topDp) }

    /** Действие со своим экраном: панель уезжает, экран открывается сразу. */
    private fun closeThen(action: () -> Unit) {
        hide()
        action()
    }

    private fun start(activity: Class<*>) {
        service.startActivity(Intent(service, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(context: Context, res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    private fun restartAutoHide() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    /**
     * Быстрое меню XGIMI закрывается по CLOSE_SYSTEM_DIALOGS с любой причиной, кроме своей
     * (HomeBroadcastReceiver). Шлём сразу и ещё раз чуть позже: оно может открыться после нас.
     */
    private fun closeStockMenu() {
        val send = { service.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", service.packageName)) }
        send()
        // под нагрузкой меню XGIMI открывается позже нашего — закрываем его ещё несколько раз
        for (delay in STOCK_RECHECK_MS) main.postDelayed({ if (isShown) send() }, delay)
    }

    private fun dp(context: Context, v: Int) = Ui.dp(context, v)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ACTION_DP = 112
        const val SCRIM_DP = 520
        const val OPEN_MS = 280L
        const val CLOSE_MS = 200L
        const val AUTO_HIDE_MS = 30_000L
        val STOCK_RECHECK_MS = longArrayOf(300L, 700L, 1_200L, 2_000L)
    }
}
