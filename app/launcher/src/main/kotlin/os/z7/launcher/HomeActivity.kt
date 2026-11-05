package os.z7.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * Главный экран (HOME) в духе меню игровой консоли: вкладки «Приложения / Медиа» сверху,
 * ряд квадратных плиток (выбранная крупнее), весь экран подсвечен цветом выбранного,
 * под рядом — крупное название и кнопка «Открыть». Последним в ряду — «Библиотека».
 * Ряд двигаем сами (масштаб и сдвиг соседей), поэтому стрелки внутри ряда тоже свои.
 */
class HomeActivity : Activity() {

    private class Item(
        val title: String,
        val subtitle: String,
        /** Цвет фона баннера / значка — из него плитка и подсветка экрана. */
        val color: Int,
        val banner: Drawable? = null,
        val icon: Drawable? = null,
        val iconRes: Int? = null,
        val app: AppEntry? = null,
        val open: () -> Unit,
    )

    private enum class Tab { APPS, MEDIA }

    private val main = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private lateinit var heroBack: View
    private lateinit var heroFront: View
    private lateinit var tabApps: TextView
    private lateinit var tabMedia: TextView
    private lateinit var wifi: ImageView
    private lateinit var settingsButton: ImageView
    private lateinit var strip: LinearLayout
    private lateinit var heroBlock: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var openButton: TextView
    private lateinit var moreButton: ImageView
    private lateinit var sheet: Sheet

    private var tab = Tab.APPS
    private var items: List<Item> = emptyList()
    private val tiles = mutableListOf<View>()
    private var focusIndex = 0
    private var heroColor = 0
    private var allApps: List<AppEntry>? = null
    private val colors = HashMap<String, Int>()
    private var hdmiConnected: Boolean? = null
    private var language = ""
    private val heroUpdate = Runnable { showHero(animate = true) }

    /** Значок Wi-Fi в шапке следует за сетью по умолчанию. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            showWifi(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))

        override fun onLost(network: Network) = showWifi(false)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            allApps = null
            if (tab == Tab.APPS) render(resetFocus = false)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        language = UiLanguage.code(this)
        setContentView(buildLayout())
        sheet = Sheet(this)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        render(resetFocus = true)
        // появление: ряд и подпись проявляются, как после загрузки консоли
        strip.alpha = 0f
        heroBlock.alpha = 0f
        strip.animate().alpha(1f).setDuration(ENTER_MS).start()
        heroBlock.animate().alpha(1f).setStartDelay(ENTER_MS / 2).setDuration(ENTER_MS).start()
    }

    override fun onResume() {
        super.onResume()
        // язык сменили в наших настройках — пересобрать экран с новыми строками
        if (UiLanguage.code(this) != language) {
            recreate()
            return
        }
        // вернулись из приложения — оно теперь первое в ряду
        if (tab == Tab.APPS && !sheet.isShown) render(resetFocus = true)
        // агента мог убить режим ожидания XGIMI — главный экран поднимает его каждый раз
        (application as LauncherApp).startAgent()
    }

    /** HOME, когда мы уже на главном экране: вкладка приложений, первая плитка. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sheet.dismiss()
        if (tab != Tab.APPS) {
            tab = Tab.APPS
            styleTabs()
        }
        render(resetFocus = true)
    }

    override fun onBackPressed() {
        // главный экран — некуда возвращаться; «Назад» из шапки или кнопок — к плитке
        if (sheet.isShown) sheet.dismiss() else tiles.getOrNull(focusIndex)?.requestFocus()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        connectivity.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- разметка

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        heroBack = View(this)
        heroFront = View(this)
        root.addView(heroBack, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(heroFront, FrameLayout.LayoutParams(MATCH, MATCH))
        // затемнение слева и снизу — под текстом
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(getColor(R.color.hero_scrim_start), getColor(R.color.hero_scrim_end)))
        }, FrameLayout.LayoutParams(dp(880), MATCH))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(getColor(R.color.hero_scrim_start), getColor(R.color.hero_scrim_end)))
        }, FrameLayout.LayoutParams(MATCH, dp(240), Gravity.BOTTOM))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(30), dp(64), dp(40))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // шапка: вкладки слева, справа Wi-Fi, настройки и часы
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tabApps = tabView(R.string.tab_apps, Tab.APPS)
        tabMedia = tabView(R.string.tab_media, Tab.MEDIA)
        top.addView(tabApps)
        top.addView(tabMedia, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        wifi = roundButton(R.drawable.ic_wifi_off, R.string.wifi) { launch(Intent(this, WifiActivity::class.java)) }
        settingsButton = roundButton(R.drawable.ic_settings, R.string.settings) {
            launch(Intent(this, SettingsActivity::class.java))
        }
        top.addView(wifi)
        top.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(12) })
        top.addView(Ui.light(TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(24) })
        content.addView(top)
        styleTabs()

        // ряд плиток: двигаем его сами, поэтому ширина — по числу плиток
        val viewport = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        viewport.addView(strip, FrameLayout.LayoutParams(WRAP, WRAP))
        content.addView(viewport, LinearLayout.LayoutParams(MATCH, dp((TILE_DP * FOCUS_SCALE).toInt() + 6)).apply {
            topMargin = dp(40)
        })

        // крупное название и кнопки
        title = Ui.light(Ui.text(this, "", 52f))
        subtitle = Ui.text(this, "", 19f, secondary = true).apply {
            // всегда две строки: кнопки под подписью не прыгают между плитками
            minLines = 2
            maxLines = 2
            setLineSpacing(dp(4).toFloat(), 1f)
            ellipsize = TextUtils.TruncateAt.END
        }
        openButton = TextView(this).apply {
            setText(R.string.open)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColorStateList(R.color.on_card))
            background = getDrawable(R.drawable.pill_bg)
            gravity = Gravity.CENTER
            minWidth = dp(180)
            setPadding(dp(40), dp(13), dp(40), dp(13))
            isFocusable = true
            isClickable = true
            setOnClickListener { items.getOrNull(focusIndex)?.open?.invoke() }
            setOnFocusChangeListener { v, f -> Ui.onFocus(v, f, Ui.FOCUS_SCALE) }
            setOnKeyListener { _, code, e -> buttonKey(code, e, left = null, right = moreButton) }
        }
        moreButton = roundButton(R.drawable.ic_more, R.string.options) { showOptions() }.apply {
            setOnKeyListener { _, code, e -> buttonKey(code, e, left = openButton, right = null) }
        }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(openButton)
            addView(moreButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(16) })
        }
        heroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(title, LinearLayout.LayoutParams(dp(900), WRAP))
            addView(subtitle, LinearLayout.LayoutParams(dp(720), WRAP).apply { topMargin = dp(6) })
            addView(buttons, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(26) })
        }
        content.addView(heroBlock, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        return root
    }

    private fun tabView(textRes: Int, which: Tab) = TextView(this).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        setPadding(dp(18), dp(7), dp(18), dp(7))
        background = getDrawable(R.drawable.tab_bg)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, focused ->
            if (focused && tab != which) switchTab(which)
            styleTabs()
        }
        setOnClickListener { tiles.getOrNull(focusIndex)?.requestFocus() }
        setOnKeyListener { _, code, e -> headerKey(code, e) }
    }

    private fun roundButton(iconRes: Int, descRes: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        contentDescription = getString(descRes)
        imageTintList = this@HomeActivity.getColorStateList(R.color.on_card)
        background = this@HomeActivity.getDrawable(R.drawable.status_bg)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f -> Ui.onFocus(v, f, 1.1f) }
        setOnKeyListener { _, code, e -> headerKey(code, e) }
    }

    private fun styleTabs() {
        for ((view, which) in listOf(tabApps to Tab.APPS, tabMedia to Tab.MEDIA)) {
            val selected = tab == which
            view.setTextColor(getColor(when {
                view.isFocused -> R.color.text_on_focus
                selected -> R.color.text_primary
                else -> R.color.text_dim
            }))
            view.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
    }

    // ---------------------------------------------------------------- содержимое

    private fun switchTab(which: Tab) {
        tab = which
        render(resetFocus = true, takeFocus = false)
    }

    private fun render(resetFocus: Boolean, takeFocus: Boolean = true) {
        items = if (tab == Tab.APPS) appItems() else mediaItems()
        strip.removeAllViews()
        tiles.clear()
        items.forEachIndexed { i, item ->
            val t = tile(item, i)
            tiles += t
            strip.addView(t, LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP)).apply { marginEnd = dp(GAP_DP) })
        }
        strip.layoutParams = (strip.layoutParams as FrameLayout.LayoutParams).apply {
            width = items.size * dp(TILE_DP + GAP_DP) + dp(TILE_DP)
        }
        focusIndex = if (resetFocus) 0 else focusIndex.coerceIn(0, maxOf(0, items.size - 1))
        layoutRow(animate = false)
        showHero(animate = false)
        if (takeFocus) tiles.getOrNull(focusIndex)?.requestFocus()
        if (tab == Tab.MEDIA) refreshHdmiStatus()
    }

    private fun appItems(): List<Item> {
        val all = allApps ?: AppCatalog.load(this).also { allApps = it }
        val row = AppCatalog.homeRow(this, all, ROW_APPS).map { app ->
            Item(
                title = app.label,
                subtitle = "",
                color = colorOf(app),
                banner = app.banner,
                icon = if (app.banner == null) app.icon else null,
                app = app,
            ) { openApp(app) }
        }
        val library = Item(
            title = getString(R.string.library),
            subtitle = resources.getQuantityString(R.plurals.library_count, all.size, all.size),
            color = LIBRARY_COLOR,
            iconRes = R.drawable.ic_library,
        ) { launch(Intent(this, LibraryActivity::class.java)) }
        return row + library
    }

    private fun mediaItems(): List<Item> {
        val list = mutableListOf<Item>()
        val hdmiStatus = when (hdmiConnected) {
            true -> getString(R.string.hdmi_status_connected)
            false -> getString(R.string.hdmi_status_empty)
            null -> ""
        }
        list += Item("HDMI", listOf(getString(R.string.hdmi_hint), hdmiStatus).filter { it.isNotEmpty() }.joinToString("\n"),
            HDMI_COLOR, iconRes = R.drawable.ic_hdmi) { HdmiActivity.open(this) }
        if (AppCatalog.isInstalled(this, AIRPLAY_PKG)) {
            list += Item(getString(R.string.airplay), getString(R.string.airplay_hint), AIRPLAY_COLOR,
                iconRes = R.drawable.ic_airplay) { launch(packageManager.getLeanbackLaunchIntentForPackage(AIRPLAY_PKG)) }
        }
        if (AppCatalog.isInstalled(this, LauncherApp.AGENT_PKG)) {
            list += Item(getString(R.string.cast_from_windows), getString(R.string.windows_hint), WINDOWS_COLOR,
                iconRes = R.drawable.ic_cast) {
                // язык нашего интерфейса — приёмнику Miracast (у него нет доступа к нашим настройкам)
                launch(Intent().setComponent(ComponentName(LauncherApp.AGENT_PKG, CAST_FROM_WINDOWS))
                    .putExtra(EXTRA_UI_LANGUAGE, UiLanguage.code(this)))
            }
        }
        return list
    }

    private fun refreshHdmiStatus() {
        Gmpf.run(this, { Hw.hdmiConnected(it) }) { connected ->
            if (connected == null || connected == hdmiConnected || tab != Tab.MEDIA) return@run
            hdmiConnected = connected
            items = mediaItems()
            showHero(animate = false)
        }
    }

    private fun colorOf(app: AppEntry): Int = colors.getOrPut(app.packageName) {
        try {
            app.banner?.let { Art.bannerBackdrop(it) } ?: Art.tileBackdrop(Art.average(app.icon))
        } catch (_: Exception) {
            LIBRARY_COLOR
        }
    }

    // ---------------------------------------------------------------- ряд

    private fun tile(item: Item, index: Int): View {
        val art = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(item.color)
            }
            clipToOutline = true
        }
        when {
            item.banner != null -> art.addView(ImageView(this).apply {
                setImageDrawable(item.banner)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(4), 0, dp(4), 0) })
            item.icon != null -> art.addView(ImageView(this).apply { setImageDrawable(item.icon) },
                FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            item.iconRes != null -> art.addView(ImageView(this).apply { setImageResource(item.iconRes) },
                FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
        }
        return FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            pivotX = 0f
            pivotY = 0f
            contentDescription = item.title
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(View(this@HomeActivity).apply {
                background = getDrawable(R.drawable.tile_ring)
                isDuplicateParentStateEnabled = true
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { item.open() }
            setOnFocusChangeListener { _, focused -> if (focused) onTileFocused(index) }
            setOnKeyListener { _, code, e -> tileKey(index, code, e) }
        }
    }

    private fun onTileFocused(index: Int) {
        val changed = index != focusIndex
        focusIndex = index
        if (changed) layoutRow(animate = true)
        main.removeCallbacks(heroUpdate)
        if (changed) main.postDelayed(heroUpdate, HERO_DELAY_MS)
    }

    /** Выбранная плитка крупнее (растёт вправо-вниз), соседи справа отъезжают; ряд прокручивается. */
    private fun layoutRow(animate: Boolean) {
        val grow = dp(TILE_DP) * (FOCUS_SCALE - 1f)
        tiles.forEachIndexed { j, t ->
            val s = if (j == focusIndex) FOCUS_SCALE else 1f
            val x = if (j > focusIndex) grow else 0f
            if (animate) {
                t.animate().scaleX(s).scaleY(s).translationX(x).setDuration(ROW_MS)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            } else {
                t.scaleX = s
                t.scaleY = s
                t.translationX = x
            }
        }
        val shift = -((focusIndex - SCROLL_AFTER).coerceAtLeast(0) * dp(TILE_DP + GAP_DP)).toFloat()
        if (animate) {
            strip.animate().translationX(shift).setDuration(ROW_MS).setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            strip.translationX = shift
        }
    }

    private fun tileKey(index: Int, code: Int, e: KeyEvent): Boolean {
        val target: View? = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> tiles.getOrNull(index + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> tiles.getOrNull(index - 1)
            KeyEvent.KEYCODE_DPAD_UP -> if (tab == Tab.APPS) tabApps else tabMedia
            KeyEvent.KEYCODE_DPAD_DOWN -> openButton
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) target?.requestFocus()
        return true
    }

    /** Шапка: по горизонтали — обычный поиск фокуса, вниз — к выбранной плитке. */
    private fun headerKey(code: Int, e: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (e.action == KeyEvent.ACTION_DOWN) tiles.getOrNull(focusIndex)?.requestFocus()
            true
        }
        KeyEvent.KEYCODE_DPAD_UP -> true
        else -> false
    }

    private fun buttonKey(code: Int, e: KeyEvent, left: View?, right: View?): Boolean {
        val target: View? = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> tiles.getOrNull(focusIndex)
            KeyEvent.KEYCODE_DPAD_LEFT -> left
            KeyEvent.KEYCODE_DPAD_RIGHT -> right?.takeIf { it.visibility == View.VISIBLE }
            KeyEvent.KEYCODE_DPAD_DOWN -> null
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) target?.requestFocus()
        return true
    }

    // ---------------------------------------------------------------- подпись и подсветка

    private fun showHero(animate: Boolean) {
        val item = items.getOrNull(focusIndex) ?: return
        title.text = item.title
        subtitle.text = item.subtitle
        subtitle.visibility = if (item.subtitle.isEmpty()) View.INVISIBLE else View.VISIBLE
        moreButton.visibility = if (item.app != null) View.VISIBLE else View.GONE
        if (animate) {
            heroBlock.alpha = 0.3f
            heroBlock.translationY = dp(6).toFloat()
            heroBlock.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(HERO_TEXT_MS).start()
        }
        if (item.color == heroColor) return
        val previous = heroColor
        heroColor = item.color
        // у каждого слоя своя картинка: один Drawable на двух View теряет перерисовку
        heroBack.background = if (previous != 0) Art.wash(previous) else null
        heroFront.background = Art.wash(item.color)
        if (animate) {
            heroFront.alpha = 0f
            heroFront.animate().alpha(1f).setDuration(HERO_FADE_MS).start()
        } else {
            heroFront.alpha = 1f
        }
    }

    /** «…» у приложения: открыть или удалить (только установленные пользователем). */
    private fun showOptions() {
        val app = items.getOrNull(focusIndex)?.app ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.open) to { openApp(app) })
        if (AppCatalog.isUserApp(this, app.packageName)) {
            actions += getString(R.string.uninstall) to {
                launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            }
        }
        actions += getString(R.string.cancel) to {}
        sheet.confirm(app.label, null, actions)
    }

    private fun openApp(app: AppEntry) {
        AppCatalog.noteLaunch(this, app.packageName)
        launch(app.launch)
    }

    private fun launch(intent: Intent?) {
        if (intent == null) return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWifi(connected: Boolean) = runOnUiThread {
        wifi.setImageResource(if (connected) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val AIRPLAY_PKG = "os.z7.airplay"
        private const val CAST_FROM_WINDOWS = "os.z7.agent.CastFromWindowsActivity"
        private const val EXTRA_UI_LANGUAGE = "z7.ui_language"

        private const val TILE_DP = 112
        private const val GAP_DP = 16
        private const val FOCUS_SCALE = 1.42f
        /** После какой плитки ряд начинает прокручиваться. */
        private const val SCROLL_AFTER = 5
        /** Приложений в ряду (плюс «Библиотека»). */
        private const val ROW_APPS = 8

        private const val ROW_MS = 220L
        private const val HERO_DELAY_MS = 90L
        private const val HERO_TEXT_MS = 200L
        private const val HERO_FADE_MS = 420L
        private const val ENTER_MS = 420L

        private const val LIBRARY_COLOR = 0xFF2A3656.toInt()
        private const val HDMI_COLOR = 0xFFB4532A.toInt()
        private const val AIRPLAY_COLOR = 0xFF4A3FD0.toInt()
        private const val WINDOWS_COLOR = 0xFF0F7FBF.toInt()
    }
}
