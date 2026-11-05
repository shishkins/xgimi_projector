package os.z7.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Быстрая панель (долгое нажатие «меню» на пульте) — замена быстрого меню XGIMI.
 * Окно TYPE_ACCESSIBILITY_OVERLAY из [KeyService]: поверх любого приложения и без паузы
 * видео под ним. Всё на одном экране, без прокрутки: плитки проектора, ползунки, Wi-Fi и настройки.
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

    val isShown: Boolean get() = root != null && !closing

    /**
     * Кнопка «настройки» (долгое нажатие). Быстрое меню XGIMI прошивка открывает на это же нажатие
     * раньше нашей службы (~140 мс), его гасит только CLOSE_SYSTEM_DIALOGS. Поэтому при закрытии
     * наша панель ещё немного стоит на месте — меню XGIMI гаснет под ней, а не после неё.
     */
    fun toggle() {
        if (!isShown) {
            show()
            return
        }
        closeStockMenu()
        main.postDelayed({ hide() }, COVER_ON_CLOSE_MS)
    }

    fun show() {
        if (root != null) return
        // язык интерфейса мог смениться в настройках — каждый раз свежий контекст
        val context = UiLanguage.wrap(service)
        val width = Ui.dp(context, PANEL_WIDTH_DP)

        // фон как у меню XGIMI: без плашки — тёмный слева, плавно уходит в прозрачность
        val scrimView = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(SHADE_LEFT, SHADE_MID, SHADE_END))
        }
        // содержимое добавим следующим сообщением — окно с затемнением появляется сразу
        val panelView = FrameLayout(context)
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
            addView(scrimView, FrameLayout.LayoutParams(Ui.dp(context, SHADE_DP), MATCH, Gravity.START))
            addView(panelView, FrameLayout.LayoutParams(width, MATCH, Gravity.START))
        }

        val params = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }

        // почти без выезда: к появлению меню XGIMI (~140 мс) его место уже закрыто нашей панелью
        panelView.translationX = -Ui.dp(context, OPEN_SHIFT_DP).toFloat()
        scrimView.alpha = 0f
        root = rootView
        panel = panelView
        scrim = scrimView
        closing = false
        lp = params
        wm.addView(rootView, params)
        main.post {
            if (root !== rootView || closing) return@post
            panelView.addView(build(context))
            firstFocus?.requestFocus()
        }

        // анимацию запускаем, когда окно уже разложено: иначе первые кадры теряются и выезд «рвётся»
        rootView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                rootView.viewTreeObserver.removeOnPreDrawListener(this)
                panelView.animate().translationX(0f).setDuration(OPEN_SHIFT_MS)
                    .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
                scrimView.animate().alpha(1f).setDuration(OPEN_MS).start()
                return true
            }
        })
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
        panelView.animate().translationX(-panelView.width.toFloat()).setDuration(CLOSE_MS)
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

    private var firstFocus: View? = null

    private fun build(context: Context): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 30), Ui.dp(context, 28), Ui.dp(context, 24))
        }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(context, context.getString(R.string.quick_title), 26f),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(Ui.text(context, SimpleDateFormat("HH:mm", UiLanguage.locale(context)).format(Date()), 20f, secondary = true))
        column.addView(header)

        // плитки проектора 2×2
        val autoFocus = Ui.Tile(context, R.drawable.ic_focus, context.getString(R.string.quick_auto_focus))
        val autoKeystone = Ui.Tile(context, R.drawable.ic_keystone, context.getString(R.string.quick_auto_keystone))
        val keystone = Ui.Tile(context, R.drawable.ic_corners, context.getString(R.string.quick_keystone))
        val focus = Ui.Tile(context, R.drawable.ic_focus_manual, context.getString(R.string.quick_manual_focus))
        autoFocus.setOnClickListener { closeThen { Projector.autoFocus(service) } }
        autoKeystone.setOnClickListener {
            closeThen {
                Gmpf.run(service, { it.autoKeystone() }) { ok ->
                    // ход калибровки показывает экран XGIMI (с нашей плашкой, см. AkCover)
                    if (ok != true) toast(context, R.string.action_failed)
                }
            }
        }
        keystone.setOnClickListener { closeThen { KeystoneScreen.show(service) } }
        focus.setOnClickListener { closeThen { Projector.manualFocus(service) } }
        solid(context, autoFocus, autoKeystone, keystone, focus)
        column.addView(pair(context, autoFocus, autoKeystone), block(context, 24))
        column.addView(pair(context, keystone, focus), block(context, GAP_DP))
        firstFocus = autoFocus

        // ползунки
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = Ui.Slider(context, R.drawable.ic_volume, context.getString(R.string.quick_volume), 0, maxVolume,
            format = { "${it * 100 / maxVolume}%" },
            write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) })
        volume.set(audio.getStreamVolume(AudioManager.STREAM_MUSIC), notify = false)

        val brightnessWriter = Gmpf.Latest<Int>(service) { b, v -> b.setBrightness(v) }
        val brightness = Ui.Slider(context, R.drawable.ic_brightness, context.getString(R.string.quick_brightness),
            1, Gmpf.BRIGHTNESS_MAX, format = { "$it / ${Gmpf.BRIGHTNESS_MAX}" }, write = brightnessWriter::submit)
        brightness.set(Gmpf.BRIGHTNESS_MAX, notify = false)

        // размер изображения: вправо — больше; у прошивки наоборот (шаг 0 — полный размер)
        var zoomMax = Gmpf.ZOOM_MAX_DEFAULT
        val zoomWriter = Gmpf.Latest<Int>(service) { b, v -> b.setZoom(v) }
        val size = Ui.Slider(context, R.drawable.ic_zoom, context.getString(R.string.quick_size), 0, zoomMax,
            format = { if (it == zoomMax) context.getString(R.string.size_full) else "−${zoomMax - it}" },
            write = { zoomWriter.submit(zoomMax - it) })
        size.set(zoomMax, notify = false)

        Gmpf.run(service, { Triple(it.brightness(), it.zoomMax(), it.zoom()) }) { values ->
            if (values == null) return@run
            val (level, max, step) = values
            brightness.set(level, notify = false)
            zoomMax = max
            size.setRange(0, max)
            size.set(max - step, notify = false)
        }

        column.addView(volume, block(context, 24))
        column.addView(brightness, block(context, 8))
        column.addView(size, block(context, 8))

        // сеть и настройки
        val wifi = Ui.Tile(context, R.drawable.ic_wifi, context.getString(R.string.wifi), Wifi.status(context))
        val settings = Ui.Tile(context, R.drawable.ic_settings, context.getString(R.string.settings))
        wifi.setOnClickListener { closeThen { start(WifiActivity::class.java) } }
        settings.setOnClickListener { closeThen { start(SettingsActivity::class.java) } }
        solid(context, wifi, settings, volume, brightness, size)
        column.addView(pair(context, wifi, settings), block(context, 24))
        return column
    }

    /** Плитки и ползунки панели — непрозрачные (панель лежит на прозрачном градиенте поверх видео). */
    private fun solid(context: Context, vararg views: View) =
        views.forEach { it.background = context.getDrawable(R.drawable.card_solid_bg) }

    private fun pair(context: Context, a: View, b: View, heightDp: Int = TILE_DP) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, Ui.dp(context, heightDp), 1f).apply { marginEnd = Ui.dp(context, GAP_DP / 2) })
        addView(b, LinearLayout.LayoutParams(0, Ui.dp(context, heightDp), 1f).apply { marginStart = Ui.dp(context, GAP_DP / 2) })
    }

    private fun block(context: Context, topDp: Int) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Ui.dp(context, topDp) }

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
        for (delay in STOCK_RECHECK_MS) main.postDelayed({ send() }, delay)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PANEL_WIDTH_DP = 440
        const val TILE_DP = 96
        const val GAP_DP = 12
        const val OPEN_MS = 260L
        const val CLOSE_MS = 200L
        const val AUTO_HIDE_MS = 30_000L
        val STOCK_RECHECK_MS = longArrayOf(150L, 300L, 700L)
        const val OPEN_SHIFT_DP = 28
        const val OPEN_SHIFT_MS = 120L
        const val COVER_ON_CLOSE_MS = 350L
        const val SHADE_DP = 760
        const val SHADE_LEFT = 0xE6040710.toInt()
        const val SHADE_MID = 0xA6040710.toInt()
        const val SHADE_END = 0x00040710
    }
}
