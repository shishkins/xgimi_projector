package os.z7.launcher

/**
 * Настройки проектора поверх [Gmpf.Bridge] — то же, что делают стоковые настройки XGIMI,
 * но без их экранов. Значения и смысл проверены на Z7X (2026-11-05, зонд app_process):
 * все методы вызываются только с потока gmpf (внутри [Gmpf.run]).
 */
object Hw {

    /** Режимы изображения (GmTvManager.setPictureMode, на текущем источнике). Игровой — только у HDMI. */
    val PICTURE_MODES = listOf(
        10 to R.string.pm_smart,
        1 to R.string.pm_standard,
        0 to R.string.pm_bright,
        2 to R.string.pm_soft,
        6 to R.string.pm_office,
        9 to R.string.pm_sport,
        3 to R.string.pm_custom,
    )

    /** Цветовая температура: 0 холодная, 1 стандартная, 2 тёплая. */
    val COLOR_TEMPS = listOf(0 to R.string.ct_cool, 1 to R.string.ct_standard, 2 to R.string.ct_warm)

    /** Уровни сглаживания движения (MEMC) и шумоподавления: 0 выкл … 3 высокий. */
    val LEVELS = listOf(0 to R.string.level_off, 1 to R.string.level_low, 2 to R.string.level_medium, 3 to R.string.level_high)

    /** Крепление: авто (по гироскопу), стол, потолок. */
    const val MOUNT_AUTO = 0
    const val MOUNT_TABLE = 1
    const val MOUNT_CEILING = 2
    val MOUNTS = listOf(MOUNT_AUTO to R.string.mount_auto, MOUNT_TABLE to R.string.mount_table, MOUNT_CEILING to R.string.mount_ceiling)
    val SIDES = listOf(0 to R.string.side_front, 1 to R.string.side_rear)

    private const val TV = "GmTvManager"
    private const val DISPLAY = "DisplayManager"
    private const val MOTION = "MotionDetectionManager"
    private const val FOCUS = "ProjectorFocusManager"
    private const val SYSTEM = "SystemManager"

    // ---- изображение ----

    class Picture(
        val mode: Int,
        val brightness: Int,
        val contrast: Int,
        val saturation: Int,
        val sharpness: Int,
        val colorTemp: Int,
        val motion: Int,
        val noise: Int,
    )

    private fun source(b: Gmpf.Bridge) = b.int(TV, "getCurrentInputSource")

    fun picture(b: Gmpf.Bridge): Picture {
        val s = source(b)
        return Picture(
            mode = b.int(TV, "getPictureMode", s),
            brightness = b.int(TV, "getBrightness", s),
            contrast = b.int(TV, "getContrast", s),
            saturation = b.int(TV, "getSaturation", s),
            sharpness = b.int(TV, "getSharpness", s),
            colorTemp = b.int(TV, "getColorTemperature", s),
            motion = b.int(TV, "getMemcLevel", s),
            noise = b.int(TV, "get3DNR", s),
        )
    }

    fun pictureMode(b: Gmpf.Bridge) = b.int(TV, "getPictureMode", source(b))

    fun setPictureMode(b: Gmpf.Bridge, mode: Int) = b.call(TV, "setPictureMode", source(b), mode)

    /** Пункт картинки текущего режима: Brightness, Contrast, Saturation, Sharpness, ColorTemperature, MemcLevel, 3DNR. */
    fun setPictureItem(b: Gmpf.Bridge, item: String, value: Int) = b.call(TV, "set$item", source(b), value)

    fun resetPicture(b: Gmpf.Bridge) = b.call(TV, "resetPqSettings", source(b))

    // ---- проекция ----

    class Projection(val rear: Boolean, val mount: Int, val keystoneOnMove: Boolean, val focusOnMove: Boolean, val focusOnStart: Boolean)

    /** Положение: бит 0 — потолок (картинка перевёрнута), бит 1 — проекция сзади (зеркально). */
    private fun putMode(b: Gmpf.Bridge) = b.int(DISPLAY, "getProjectorPutMode")

    fun projection(b: Gmpf.Bridge): Projection {
        val put = putMode(b)
        val auto = b.bool(MOTION, "getAutoReverse")
        return Projection(
            rear = (put and 2) != 0,
            mount = if (auto) MOUNT_AUTO else if ((put and 1) != 0) MOUNT_CEILING else MOUNT_TABLE,
            keystoneOnMove = b.bool(MOTION, "getAccTriggerAK"),
            focusOnMove = b.bool(MOTION, "getAngTriggerAF"),
            focusOnStart = b.bool(FOCUS, "isPowerOnAFEnabled"),
        )
    }

    fun setRear(b: Gmpf.Bridge, rear: Boolean) {
        val put = putMode(b)
        b.call(DISPLAY, "setProjectorPutMode", (put and 1) or (if (rear) 2 else 0))
    }

    /** Как стоковый setProjectorVerticalPutMode: «авто» отдаёт решение гироскопу. */
    fun setMount(b: Gmpf.Bridge, mount: Int) {
        if (mount == MOUNT_AUTO) {
            b.call(MOTION, "setAutoReverse", true)
            return
        }
        b.call(MOTION, "setAutoReverse", false)
        val put = putMode(b)
        val next = if (mount == MOUNT_CEILING) put or 1 else put and 2
        if (next != put) b.call(DISPLAY, "setProjectorPutMode", next)
    }

    fun setKeystoneOnMove(b: Gmpf.Bridge, on: Boolean) = b.call(MOTION, "setAccTriggerAK", on)
    fun setFocusOnMove(b: Gmpf.Bridge, on: Boolean) = b.call(MOTION, "setAngTriggerAF", on)
    fun setFocusOnStart(b: Gmpf.Bridge, on: Boolean) = b.call(FOCUS, "powerOnAFEnable", on)

    // ---- HDMI и система ----

    fun hdmiAutoSwitch(b: Gmpf.Bridge) = b.bool(SYSTEM, "getHdmiAutoSwitch")
    fun setHdmiAutoSwitch(b: Gmpf.Bridge, on: Boolean) = b.call(SYSTEM, "setHdmiAutoSwitch", on)
    fun cecWake(b: Gmpf.Bridge) = b.bool(TV, "getCecWakeUpState")
    fun setCecWake(b: Gmpf.Bridge, on: Boolean) = b.call(TV, "setCecWakeUp", on)

    /**
     * Подключено ли устройство к HDMI (на Z7X — порт 1; проверяем 0..3). Надёжнее, чем
     * getSignalStatus: тот при работающей картинке отвечает 1 («потерян»), проверено 2026-11-05.
     */
    fun hdmiConnected(b: Gmpf.Bridge) = (0..3).any { b.bool(TV, "getHdmiConnectStatus", it) }

    fun bootSound(b: Gmpf.Bridge) = b.bool(SYSTEM, "isPowerOnMusicEnabled")
    fun setBootSound(b: Gmpf.Bridge, on: Boolean) = b.call(SYSTEM, "enablePowerOnMusic", on)
}
