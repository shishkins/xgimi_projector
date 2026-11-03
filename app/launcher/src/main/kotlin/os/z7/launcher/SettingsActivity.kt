package os.z7.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Наши настройки — вместо стоковых XGIMI. Слева разделы, справа содержимое выбранного:
 * фокус на разделе сразу показывает его, «вправо» — внутрь.
 */
class SettingsActivity : Activity() {

    private class Section(val titleRes: Int, val iconRes: Int, val build: (LinearLayout) -> Unit)

    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var sheet: Sheet
    private val factory by lazy { FactoryReset(this) }
    private var shown = -1

    private val sections by lazy {
        listOf(
            Section(R.string.section_network, R.drawable.ic_wifi, ::network),
            Section(R.string.section_projection, R.drawable.ic_keystone, ::projection),
            Section(R.string.section_sound, R.drawable.ic_volume, ::sound),
            Section(R.string.section_system, R.drawable.ic_settings, ::system),
        )
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(64), dp(52), dp(64), 0)
        }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menu.addView(Ui.text(this, getString(R.string.settings), 40f), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            bottomMargin = dp(28)
        })
        sections.forEachIndexed { i, s ->
            val row = Ui.Row(this, s.iconRes, getString(s.titleRes))
            row.setOnFocusChangeListener { v, focused ->
                Ui.onFocus(v, focused, Ui.ROW_FOCUS_SCALE)
                if (focused) showSection(i)
            }
            row.setOnClickListener { contentScroll.requestFocus(View.FOCUS_RIGHT) }
            menu.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        root.addView(menu, LinearLayout.LayoutParams(dp(MENU_DP), MATCH).apply { marginEnd = dp(48) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(84), dp(24), dp(48))
        }
        contentScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        root.addView(contentScroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        setContentView(root)
        sheet = Sheet(this)
        menu.getChildAt(1 + (savedInstanceState?.getInt(KEY_SECTION) ?: 0)).requestFocus()
    }

    override fun onRestart() {
        super.onRestart()
        // вернулись с Wi-Fi или другого экрана — значения могли измениться; фокус оставляем на месте
        if (shown < 0) return
        val focused = (0 until content.childCount).firstOrNull { content.getChildAt(it).hasFocus() }
        content.removeAllViews()
        sections[shown].build(content)
        focused?.let { content.getChildAt(minOf(it, content.childCount - 1))?.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SECTION, maxOf(shown, 0))
    }

    override fun onDestroy() {
        factory.disconnect()
        super.onDestroy()
    }

    private fun showSection(i: Int) {
        if (i == shown) return
        shown = i
        content.removeAllViews()
        sections[i].build(content)
        content.alpha = 0f
        content.translationX = dp(16).toFloat()
        content.animate().alpha(1f).translationX(0f).setDuration(SWITCH_MS).start()
    }

    private fun network(c: LinearLayout) {
        c.addView(row(R.drawable.ic_wifi, R.string.wifi, Wifi.status(this)) { open(WifiActivity::class.java) })
    }

    private fun projection(c: LinearLayout) {
        c.addView(row(R.drawable.ic_keystone, R.string.quick_auto_keystone) {
            Gmpf.run(this, { it.autoKeystone() }) { ok -> if (ok != true) toast(R.string.action_failed) }
        })
        c.addView(row(R.drawable.ic_corners, R.string.quick_keystone) {
            if (!KeystoneScreen.show(this)) toast(R.string.keys_service_off)
        })
        c.addView(row(R.drawable.ic_reset, R.string.reset_keystone) {
            // сброс стирает настроенную форму — только после подтверждения, «Отмена» первой
            sheet.confirm(getString(R.string.reset_keystone_title), null, listOf(
                getString(R.string.cancel) to {},
                getString(R.string.reset_keystone) to {
                    Gmpf.run(this, { it.resetKeystone() }) { ok ->
                        toast(if (ok != null) R.string.keystone_reset_done else R.string.action_failed)
                    }
                },
            ))
        })
        c.addView(row(R.drawable.ic_focus, R.string.quick_auto_focus) { Projector.autoFocus(this) })
        c.addView(row(R.drawable.ic_focus_manual, R.string.quick_manual_focus) { Projector.manualFocus(this) })

        val brightnessWriter = Gmpf.Latest<Int>(this) { b, v -> b.setBrightness(v) }
        val brightness = Ui.Slider(this, R.drawable.ic_brightness, getString(R.string.quick_brightness), 1, Gmpf.BRIGHTNESS_MAX,
            format = { "$it / ${Gmpf.BRIGHTNESS_MAX}" }, write = brightnessWriter::submit)
        brightness.set(Gmpf.BRIGHTNESS_MAX, notify = false)
        var zoomMax = Gmpf.ZOOM_MAX_DEFAULT
        val zoomWriter = Gmpf.Latest<Int>(this) { b, v -> b.setZoom(v) }
        val size = Ui.Slider(this, R.drawable.ic_zoom, getString(R.string.quick_size), 0, zoomMax,
            format = { if (it == zoomMax) getString(R.string.size_full) else "−${zoomMax - it}" },
            write = { zoomWriter.submit(zoomMax - it) })
        size.set(zoomMax, notify = false)
        Gmpf.run(this, { Triple(it.brightness(), it.zoomMax(), it.zoom()) }) { values ->
            if (values == null) return@run
            brightness.set(values.first, notify = false)
            zoomMax = values.second
            size.setRange(0, values.second)
            size.set(values.second - values.third, notify = false)
        }
        c.addView(brightness, spaced())
        c.addView(size, spaced())
    }

    private fun sound(c: LinearLayout) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = Ui.Slider(this, R.drawable.ic_volume, getString(R.string.quick_volume), 0, max,
            format = { "${it * 100 / max}%" }, write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) })
        volume.set(audio.getStreamVolume(AudioManager.STREAM_MUSIC), notify = false)
        c.addView(volume, spaced())
    }

    private fun system(c: LinearLayout) {
        val code = UiLanguage.code(this)
        val current = UiLanguage.choices.firstOrNull { it.first == code }?.second ?: code
        c.addView(row(R.drawable.ic_language, R.string.language, current) {
            sheet.confirm(getString(R.string.language), null, UiLanguage.choices.map { (lang, name) ->
                name to {
                    if (lang != code) {
                        UiLanguage.set(this, lang)
                        recreate()
                    }
                }
            })
        })
        c.addView(Ui.text(this, getString(R.string.about), 16f, secondary = true), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(28)
            bottomMargin = dp(8)
            marginStart = dp(8)
        })
        c.addView(info(R.string.about_model, Build.MODEL))
        c.addView(info(R.string.about_android, Build.VERSION.RELEASE))
        c.addView(info(R.string.about_ip, Wifi.ipAddress(this) ?: "—"))
        c.addView(info(R.string.about_version, packageManager.getPackageInfo(packageName, 0).versionName))
        c.addView(row(R.drawable.ic_reset, R.string.factory_reset) { confirmFactoryReset() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
    }

    /** Два подтверждения, «Отмена» — первой и в фокусе: случайный OK ничего не стирает. */
    private fun confirmFactoryReset() {
        sheet.confirm(getString(R.string.factory_reset_title), getString(R.string.factory_reset_message), listOf(
            getString(R.string.cancel) to {},
            getString(R.string.factory_reset_continue) to {
                sheet.confirm(getString(R.string.factory_reset_sure), null, listOf(
                    getString(R.string.cancel) to {},
                    getString(R.string.factory_reset_confirm) to ::startFactoryReset,
                ))
            },
        ))
    }

    private fun startFactoryReset() {
        factory.connect { ready ->
            if (!ready) {
                toast(R.string.factory_reset_unavailable)
                return@connect
            }
            Thread {
                val ok = factory.run()
                if (!ok) runOnUiThread { toast(R.string.factory_reset_unavailable) }
            }.start()
            toast(R.string.factory_reset_started)
        }
    }

    private fun row(iconRes: Int, labelRes: Int, value: String? = null, onClick: () -> Unit): View =
        Ui.Row(this, iconRes, getString(labelRes), value).apply {
            setOnClickListener { onClick() }
            layoutParams = spaced()
        }

    private fun info(labelRes: Int, value: String): View =
        Ui.Row(this, null, getString(labelRes), value).apply {
            isFocusable = false
            isClickable = false
            background = null
            minimumHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun spaced() = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }

    private fun dp(v: Int) = Ui.dp(this, v)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MENU_DP = 340
        const val SWITCH_MS = 160L
        const val KEY_SECTION = "section"
    }
}
