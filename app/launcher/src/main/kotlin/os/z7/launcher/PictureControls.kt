package os.z7.launcher

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Пункты картинки — общие для раздела «Изображение» в настройках и панели поверх видео
 * ([PictureOverlay]): режим, яркость лампы, ручные значения, цветовая температура,
 * сглаживание, шумоподавление. Ручные пункты работают только в режиме «Свой»
 * ([Hw.MODE_CUSTOM]) — в остальных они погашены (прошивка их там молча игнорирует).
 *
 * @param params разметка каждой строки в [container]
 * @param onError железо не ответило
 */
class PictureControls(
    private val context: Context,
    private val container: LinearLayout,
    private val params: () -> LinearLayout.LayoutParams,
    private val onError: () -> Unit,
) {
    /** Первая строка — на неё ставим фокус. */
    val first: View
    private val mode: Ui.Choice
    private val customHint = Ui.text(context, context.getString(R.string.picture_custom_only), 15f, secondary = true)
    private val lamp: Ui.Slider
    private val brightness: Ui.Slider
    private val contrast: Ui.Slider
    private val saturation: Ui.Slider
    private val sharpness: Ui.Slider
    private val colorTemp: Ui.Choice
    private val motion: Ui.Choice
    private val noise: Ui.Choice
    private val manual: List<View>

    /** Все выбираемые строки — например, чтобы панель поверх видео дала им непрозрачный фон. */
    val rows: List<View>

    init {
        mode = Ui.Choice(context, R.drawable.ic_picture, context.getString(R.string.picture_mode), options(Hw.PICTURE_MODES)) { m ->
            applyMode(m)
            // у каждого режима свои значения — после смены перечитываем
            Gmpf.run(context, { Hw.setPictureMode(it, m) }) { reload() }
        }
        first = mode
        container.addView(mode, params())
        customHint.setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), Ui.dp(context, 8))
        container.addView(customHint, LinearLayout.LayoutParams(MATCH, WRAP))

        val lampWriter = Gmpf.Latest<Int>(context) { b, v -> b.setBrightness(v) }
        lamp = Ui.Slider(context, R.drawable.ic_brightness, context.getString(R.string.quick_brightness), 1, Gmpf.BRIGHTNESS_MAX,
            format = { "$it / ${Gmpf.BRIGHTNESS_MAX}" }, write = lampWriter::submit)
        lamp.set(Gmpf.BRIGHTNESS_MAX, notify = false)
        container.addView(lamp, params())

        brightness = item(R.string.picture_brightness, "Brightness")
        contrast = item(R.string.picture_contrast, "Contrast")
        saturation = item(R.string.picture_saturation, "Saturation")
        sharpness = item(R.string.picture_sharpness, "Sharpness")
        colorTemp = choice(R.drawable.ic_brightness, R.string.color_temperature, Hw.COLOR_TEMPS, "ColorTemperature")
        motion = choice(R.drawable.ic_picture, R.string.motion_smoothing, Hw.LEVELS, "MemcLevel")
        noise = choice(R.drawable.ic_picture, R.string.noise_reduction, Hw.LEVELS, "3DNR")

        manual = listOf(brightness, contrast, saturation, sharpness, colorTemp, motion, noise)
        rows = listOf(mode, lamp) + manual
        applyMode(null)
    }

    /** Перечитать значения из железа (после смены режима, сброса, при открытии). */
    fun reload() {
        Gmpf.run(context, { b -> Hw.picture(b) to b.brightness() }) { values ->
            if (values == null) {
                onError()
                return@run
            }
            val p = values.first
            mode.show(p.mode)
            applyMode(p.mode)
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

    private fun applyMode(m: Int?) {
        val custom = m == Hw.MODE_CUSTOM
        manual.forEach { Ui.setActive(it, custom) }
        customHint.visibility = if (custom || m == null) View.GONE else View.VISIBLE
    }

    private fun item(labelRes: Int, name: String): Ui.Slider {
        val writer = Gmpf.Latest<Int>(context) { b, v -> Hw.setPictureItem(b, name, v) }
        return Ui.Slider(context, R.drawable.ic_picture, context.getString(labelRes), 0, 100,
            format = { "$it" }, write = writer::submit, step = 2).also { container.addView(it, params()) }
    }

    private fun choice(iconRes: Int, labelRes: Int, list: List<Pair<Int, Int>>, name: String): Ui.Choice =
        Ui.Choice(context, iconRes, context.getString(labelRes), options(list)) { v ->
            Gmpf.run(context, { Hw.setPictureItem(it, name, v) })
        }.also { container.addView(it, params()) }

    private fun options(list: List<Pair<Int, Int>>) = list.map { (key, res) -> key to context.getString(res) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
