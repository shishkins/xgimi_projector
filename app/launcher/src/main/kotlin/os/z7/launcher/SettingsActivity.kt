package os.z7.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Наши настройки — вместо стоковых XGIMI. Слева разделы, справа содержимое выбранного:
 * фокус на разделе сразу показывает его, «вправо» — внутрь. Всё, что касается железа,
 * идёт через gmpf ([Gmpf], [Hw]) — те же вызовы, что у стоковых настроек.
 */
class SettingsActivity : Activity() {

    private class Section(val titleRes: Int, val iconRes: Int, val build: (LinearLayout) -> Unit)

    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var sheet: Sheet
    private val factory by lazy { FactoryReset(this) }
    private var shown = -1
    private val menuRows = mutableListOf<View>()

    private val sections by lazy {
        listOf(
            Section(R.string.section_network, R.drawable.ic_wifi, ::network),
            Section(R.string.section_picture, R.drawable.ic_picture, ::picture),
            Section(R.string.section_projection, R.drawable.ic_projector, ::projection),
            Section(R.string.section_sound, R.drawable.ic_volume, ::sound),
            Section(R.string.section_hdmi, R.drawable.ic_hdmi, ::hdmi),
            Section(R.string.section_system, R.drawable.ic_settings, ::system),
        )
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(64), dp(48), dp(64), 0)
        }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menu.addView(Ui.light(Ui.text(this, getString(R.string.settings), 40f)), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            bottomMargin = dp(24)
        })
        sections.forEachIndexed { i, s ->
            val row = Ui.Row(this, s.iconRes, getString(s.titleRes))
            row.setOnFocusChangeListener { v, focused ->
                Ui.onFocus(v, focused, Ui.ROW_FOCUS_SCALE)
                if (focused) showSection(i)
            }
            // вправо и OK — на первую строку раздела, а не на ближайшую по высоте
            row.setOnClickListener { focusContent() }
            row.setOnKeyListener { _, code, e ->
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (e.action == KeyEvent.ACTION_DOWN) focusContent()
                true
            }
            menuRows += row
            menu.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        }
        root.addView(menu, LinearLayout.LayoutParams(dp(MENU_DP), MATCH).apply { marginEnd = dp(48) })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(78), dp(24), dp(48))
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

    /** «Назад» внутри раздела — к списку разделов (ползунки забирают «влево»), из списка — выход. */
    override fun onBackPressed() {
        when {
            sheet.isShown -> sheet.dismiss()
            content.hasFocus() && shown >= 0 -> menuRows[shown].requestFocus()
            else -> super.onBackPressed()
        }
    }

    private fun focusContent() {
        contentScroll.scrollTo(0, 0)
        (0 until content.childCount).map { content.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
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
        contentScroll.scrollTo(0, 0)
        content.alpha = 0f
        content.translationX = dp(16).toFloat()
        content.animate().alpha(1f).translationX(0f).setDuration(SWITCH_MS).start()
    }

    // ---------------------------------------------------------------- разделы

    private fun network(c: LinearLayout) {
        c.addView(row(R.drawable.ic_wifi, R.string.wifi, Wifi.status(this)) { open(WifiActivity::class.java) })
    }

    /** Режим и параметры картинки — на текущем источнике (у HDMI свои). */
    private fun picture(c: LinearLayout) {
        lateinit var reload: () -> Unit
        val mode = Ui.Choice(this, R.drawable.ic_picture, getString(R.string.picture_mode), options(Hw.PICTURE_MODES)) { m ->
            // у каждого режима свои значения — после смены перечитываем
            Gmpf.run(this, { Hw.setPictureMode(it, m) }) { reload() }
        }
        c.addView(mode, spaced())

        val lampWriter = Gmpf.Latest<Int>(this) { b, v -> b.setBrightness(v) }
        val lamp = Ui.Slider(this, R.drawable.ic_brightness, getString(R.string.quick_brightness), 1, Gmpf.BRIGHTNESS_MAX,
            format = { "$it / ${Gmpf.BRIGHTNESS_MAX}" }, write = lampWriter::submit)
        lamp.set(Gmpf.BRIGHTNESS_MAX, notify = false)
        c.addView(lamp, spaced())

        fun item(labelRes: Int, name: String): Ui.Slider {
            val writer = Gmpf.Latest<Int>(this) { b, v -> Hw.setPictureItem(b, name, v) }
            return Ui.Slider(this, R.drawable.ic_picture, getString(labelRes), 0, 100,
                format = { "$it" }, write = writer::submit, step = 2).also { c.addView(it, spaced()) }
        }
        val brightness = item(R.string.picture_brightness, "Brightness")
        val contrast = item(R.string.picture_contrast, "Contrast")
        val saturation = item(R.string.picture_saturation, "Saturation")
        val sharpness = item(R.string.picture_sharpness, "Sharpness")

        val colorTemp = Ui.Choice(this, R.drawable.ic_brightness, getString(R.string.color_temperature), options(Hw.COLOR_TEMPS)) { v ->
            Gmpf.run(this, { Hw.setPictureItem(it, "ColorTemperature", v) })
        }
        val motion = Ui.Choice(this, R.drawable.ic_picture, getString(R.string.motion_smoothing), options(Hw.LEVELS)) { v ->
            Gmpf.run(this, { Hw.setPictureItem(it, "MemcLevel", v) })
        }
        val noise = Ui.Choice(this, R.drawable.ic_picture, getString(R.string.noise_reduction), options(Hw.LEVELS)) { v ->
            Gmpf.run(this, { Hw.setPictureItem(it, "3DNR", v) })
        }
        c.addView(colorTemp, spaced())
        c.addView(motion, spaced())
        c.addView(noise, spaced())
        c.addView(row(R.drawable.ic_reset, R.string.picture_reset) {
            sheet.confirm(getString(R.string.picture_reset_title), null, listOf(
                getString(R.string.cancel) to {},
                getString(R.string.picture_reset) to { Gmpf.run(this, { Hw.resetPicture(it) }) { reload() } },
            ))
        })

        reload = {
            Gmpf.run(this, { b -> Hw.picture(b) to b.brightness() }) { values ->
                if (values == null) {
                    toast(R.string.hardware_unavailable)
                    return@run
                }
                val p = values.first
                mode.show(p.mode)
                lamp.set(values.second, notify = false)
                brightness.set(p.brightness, notify = false)
                contrast.set(p.contrast, notify = false)
                saturation.set(p.saturation, notify = false)
                sharpness.set(p.sharpness, notify = false)
                colorTemp.show(p.colorTemp)
                motion.show(p.motion)
                noise.show(p.noise)
            }
        }
        reload()
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

        var zoomMax = Gmpf.ZOOM_MAX_DEFAULT
        val zoomWriter = Gmpf.Latest<Int>(this) { b, v -> b.setZoom(v) }
        val size = Ui.Slider(this, R.drawable.ic_zoom, getString(R.string.quick_size), 0, zoomMax,
            format = { if (it == zoomMax) getString(R.string.size_full) else "−${zoomMax - it}" },
            write = { zoomWriter.submit(zoomMax - it) })
        size.set(zoomMax, notify = false)
        c.addView(size, spaced())

        val side = Ui.Choice(this, R.drawable.ic_projector, getString(R.string.projection_side), options(Hw.SIDES)) { v ->
            Gmpf.run(this, { Hw.setRear(it, v == 1) })
        }
        val mount = Ui.Choice(this, R.drawable.ic_projector, getString(R.string.projection_mount), options(Hw.MOUNTS)) { v ->
            Gmpf.run(this, { Hw.setMount(it, v) })
        }
        val keystoneOnMove = Ui.Toggle(this, R.drawable.ic_keystone, getString(R.string.keystone_on_move),
            getString(R.string.keystone_on_move_hint)) { on -> Gmpf.run(this, { Hw.setKeystoneOnMove(it, on) }) }
        val focusOnMove = Ui.Toggle(this, R.drawable.ic_focus, getString(R.string.focus_on_move),
            getString(R.string.focus_on_move_hint)) { on -> Gmpf.run(this, { Hw.setFocusOnMove(it, on) }) }
        val focusOnStart = Ui.Toggle(this, R.drawable.ic_focus, getString(R.string.focus_on_start)) { on ->
            Gmpf.run(this, { Hw.setFocusOnStart(it, on) })
        }
        listOf(side, mount, keystoneOnMove, focusOnMove, focusOnStart).forEach { c.addView(it, spaced()) }
        listOf(keystoneOnMove, focusOnMove, focusOnStart).forEach { it.set(null) }

        Gmpf.run(this, { b -> Triple(b.zoomMax(), b.zoom(), Hw.projection(b)) }) { values ->
            if (values == null) return@run
            val (max, step, p) = values
            zoomMax = max
            size.setRange(0, max)
            size.set(max - step, notify = false)
            side.show(if (p.rear) 1 else 0)
            mount.show(p.mount)
            keystoneOnMove.set(p.keystoneOnMove)
            focusOnMove.set(p.focusOnMove)
            focusOnStart.set(p.focusOnStart)
        }
    }

    private fun sound(c: LinearLayout) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = Ui.Slider(this, R.drawable.ic_volume, getString(R.string.quick_volume), 0, max,
            format = { "${it * 100 / max}%" }, write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) })
        volume.set(audio.getStreamVolume(AudioManager.STREAM_MUSIC), notify = false)
        c.addView(volume, spaced())
    }

    private fun hdmi(c: LinearLayout) {
        c.addView(row(R.drawable.ic_hdmi, R.string.hdmi_open) { HdmiActivity.open(this) })
        val autoSwitch = Ui.Toggle(this, R.drawable.ic_hdmi, getString(R.string.hdmi_auto_switch),
            getString(R.string.hdmi_auto_switch_hint)) { on -> HdmiActivity.setAutoSwitch(this, on) }
        val cec = Ui.Toggle(this, R.drawable.ic_hdmi, getString(R.string.hdmi_cec),
            getString(R.string.hdmi_cec_hint)) { on -> Gmpf.run(this, { Hw.setCecWake(it, on) }) }
        val game = Ui.Choice(this, R.drawable.ic_hdmi, getString(R.string.hdmi_game_mode), listOf(
            HdmiActivity.GAME_AUTO to getString(R.string.game_auto),
            HdmiActivity.GAME_ALWAYS to getString(R.string.game_always),
            HdmiActivity.GAME_OFF to getString(R.string.game_off),
        )) { v -> HdmiActivity.setGameMode(this, v) }
        game.show(HdmiActivity.gameMode(this))
        c.addView(game, spaced())
        listOf(autoSwitch, cec).forEach {
            it.set(null)
            c.addView(it, spaced())
        }
        autoSwitch.set(HdmiActivity.autoSwitch(this))
        Gmpf.run(this, { b -> Hw.cecWake(b) }) { on -> if (on != null) cec.set(on) }
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
        val bootSound = Ui.Toggle(this, R.drawable.ic_volume, getString(R.string.boot_sound)) { on ->
            Gmpf.run(this, { Hw.setBootSound(it, on) })
        }
        bootSound.set(null)
        c.addView(bootSound, spaced())
        Gmpf.run(this, { Hw.bootSound(it) }) { on -> if (on != null) bootSound.set(on) }

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

    // ---------------------------------------------------------------- элементы

    private fun options(list: List<Pair<Int, Int>>) = list.map { (key, res) -> key to getString(res) }

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
        const val MENU_DP = 320
        const val SWITCH_MS = 160L
        const val KEY_SECTION = "section"
    }
}
