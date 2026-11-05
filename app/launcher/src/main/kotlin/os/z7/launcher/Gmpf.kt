package os.z7.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.lang.reflect.Field

/**
 * Прямой доступ к железу проектора — тот же слой gmpf, через который работают настройки XGIMI.
 *
 * Нативные библиотеки gmpf (`lib*_jni.xgimi.so`) прошивка открывает всем приложениям
 * (/system/etc/public.libraries.xgimi.txt), а Java-обёртки к ним лежат в APK стоковых настроек.
 * Мы не копируем их код: грузим классы прямо из APK на устройстве отдельным ClassLoader'ом
 * и зовём рефлексией. Все вызовы — на своём потоке: нативный слой ходит в HAL синхронно.
 */
object Gmpf {

    private const val TAG = "z7.Gmpf"
    private const val SETTINGS_PKG = "com.android.newsettings"

    private val worker = HandlerThread("gmpf").apply { start() }
    private val handler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var bridge: Bridge? = null
    @Volatile private var unavailable = false

    /** Выполнить [work] на потоке gmpf; [done] получает результат (null — слой недоступен) в главном потоке. */
    fun <T> run(context: Context, work: (Bridge) -> T, done: (T?) -> Unit = {}) {
        val app = context.applicationContext
        handler.post {
            val value = try {
                bridge(app)?.let(work)
            } catch (e: Throwable) {
                Log.w(TAG, "call failed", e)
                null
            }
            main.post { done(value) }
        }
    }

    /**
     * Запись значения, которое меняется зажатой кнопкой (ползунок, угол трапеции): в железо уходит
     * только последнее, очередь не копится. Пользоваться из главного потока.
     */
    class Latest<V>(private val context: Context, private val apply: (Bridge, V) -> Any?) {
        private var pending: V? = null
        private var busy = false

        fun submit(value: V) {
            pending = value
            if (!busy) pump()
        }

        private fun pump() {
            val value = pending ?: return
            pending = null
            busy = true
            run(context, { apply(it, value) }) {
                busy = false
                pump()
            }
        }
    }

    /** Заранее загрузить классы — первое открытие экрана не ждёт загрузки APK. */
    fun warmUp(context: Context) = run(context, { }) {}

    /**
     * APK стоковых настроек. Само приложение агент выключает (pm disable-user: в нём живёт
     * быстрое меню XGIMI, которое прошивка открывает раньше нашей службы), поэтому ищем и среди
     * выключенных, а на крайний случай — по известному пути прошивки.
     */
    private fun settingsApk(context: Context): String {
        try {
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            return context.packageManager.getApplicationInfo(SETTINGS_PKG, flags).sourceDir
        } catch (_: PackageManager.NameNotFoundException) {
        }
        if (java.io.File(SETTINGS_APK).exists()) return SETTINGS_APK
        throw IllegalStateException("$SETTINGS_PKG not found")
    }

    private const val SETTINGS_APK = "/system/priv-app/release_838_newsettings/release_838_newsettings.apk"

    private fun bridge(context: Context): Bridge? {
        if (bridge == null && !unavailable) {
            bridge = try {
                val apk = settingsApk(context)
                // родитель — системный загрузчик фреймворка: kotlin и прочие зависимости
                // классы XGIMI берут из своего APK, а не из нашего
                val loader = dalvik.system.PathClassLoader(apk, "/system/lib", Context::class.java.classLoader)
                Bridge(loader)
            } catch (e: Throwable) {
                Log.w(TAG, "gmpf unavailable", e)
                unavailable = true
                null
            }
        }
        return bridge
    }

    /** Обёртка над com.xgimi.gmpf.api.*. Методы вызываются только с потока gmpf. */
    class Bridge(private val loader: ClassLoader) {

        private val displayCls = cls("com.xgimi.gmpf.api.DisplayManager")
        private val focusCls = cls("com.xgimi.gmpf.api.ProjectorFocusManager")
        private val display = displayCls.getMethod("getInstance").invoke(null)!!
        private val focus = focusCls.getMethod("getInstance").invoke(null)!!
        private val fullCls = cls("com.xgimi.gmpf.rp.KeyStoneFullCoordinates")
        private val pointCls = cls("com.xgimi.gmpf.rp.KeyStonePoint")
        private val pointX = pointCls.getField("x")
        private val pointY = pointCls.getField("y")

        /** Автотрапеция штатным алгоритмом (камера + датчики). */
        fun autoKeystone(): Boolean = (focusCls.getMethod("autoKst").invoke(focus) as Byte).toInt() != 0

        /**
         * Углы изображения в пикселях панели 1920×1080, по часовой стрелке:
         * x,y верхнего левого, верхнего правого, нижнего правого, нижнего левого.
         */
        fun corners(): IntArray {
            val full = fullCls.getConstructor().newInstance()
            displayCls.getMethod("getCorrectKeystone", fullCls).invoke(display, full)
            val pts = points(full)
            return CORNER_CELLS.flatMap { (r, c) -> listOf(pointX.getShort(pts[r][c]).toInt(), pointY.getShort(pts[r][c]).toInt()) }
                .toIntArray()
        }

        /** Применить углы; false — прошивка считает такую трапецию недопустимой, ничего не меняем. */
        fun setCorners(corners: IntArray): Boolean {
            val full = fullCls.getConstructor().newInstance()
            fullCls.getField("kstMode").setByte(full, 0) // 4 точки
            val pts = points(full)
            CORNER_CELLS.forEachIndexed { i, (r, c) ->
                pointX.setShort(pts[r][c], corners[i * 2].toShort())
                pointY.setShort(pts[r][c], corners[i * 2 + 1].toShort())
            }
            val ok = displayCls.getMethod("checkTrapezoidCoordinate", fullCls).invoke(display, full) as Boolean
            if (ok) displayCls.getMethod("correctKeystone", fullCls).invoke(display, full)
            return ok
        }

        /** Сброс трапеции к прямоугольнику (как «Сброс» в настройках XGIMI). */
        fun resetKeystone() {
            displayCls.getMethod("correctKeystoneReset", Byte::class.javaPrimitiveType).invoke(display, 0.toByte())
            val mode = displayCls.getMethod("getCurrentKeystoneMode").invoke(display) as Int
            if (mode == KST_MODE_FREE) {
                displayCls.getMethod("switchKeystoneMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(display, 0, 0)
            }
        }

        /** Цифровое уменьшение изображения: 0 — полный размер, [zoomMax] — самое маленькое. */
        fun zoom(): Int {
            val step = cls("com.xgimi.gmpf.rp.ZoomStep").getConstructor().newInstance()
            displayCls.getMethod("getCurrentZoomStep", step.javaClass).invoke(display, step)
            return field(step.javaClass, "zoomDigtalstep").getInt(step)
        }

        /**
         * Сколько шагов уменьшения доступно. getAllZoomStep отвечает стабильно; getZoomStepRange
         * (им пользуются настройки XGIMI) после трапеции иногда отдаёт нули — он только запасной.
         */
        fun zoomMax(): Int {
            val all = displayCls.getMethod("getAllZoomStep").invoke(display)
            val max = all?.let { field(it.javaClass, "zoomOutMaxStep").getInt(it) } ?: 0
            if (max > 0) return max
            val range = cls("com.xgimi.gmpf.rp.ZoomStepRange").getConstructor().newInstance()
            displayCls.getMethod("getZoomStepRange", range.javaClass).invoke(display, range)
            return field(range.javaClass, "zoomOutDigtalMaxNum").getInt(range).takeIf { it > 0 } ?: ZOOM_MAX_DEFAULT
        }

        fun setZoom(step: Int) {
            displayCls.getMethod("setDigitalZoomStep", Int::class.javaPrimitiveType).invoke(display, step)
        }

        /** Яркость лампы, 1..[BRIGHTNESS_MAX]. */
        fun brightness(): Int = (displayCls.getMethod("getDlpLumensLevel").invoke(display) as Byte).toInt()

        fun setBrightness(level: Int) {
            displayCls.getMethod("setDlpLumensLevel", Byte::class.javaPrimitiveType)
                .invoke(display, level.coerceIn(1, BRIGHTNESS_MAX).toByte())
        }

        private val managers = HashMap<String, Pair<Class<*>, Any>>()

        /**
         * Вызов метода менеджера gmpf (`com.xgimi.gmpf.api.<manager>`) по имени и числу аргументов.
         * Числа и логические значения приводятся к типам параметров (byte, int, boolean).
         * Только для методов без перегрузок с тем же числом аргументов.
         */
        fun call(manager: String, method: String, vararg args: Any): Any? {
            val (c, instance) = managers.getOrPut(manager) {
                val k = cls("com.xgimi.gmpf.api.$manager")
                k to k.getMethod("getInstance").invoke(null)!!
            }
            val m = c.methods.first { it.name == method && it.parameterTypes.size == args.size }
            val converted = Array(args.size) { i -> convert(args[i], m.parameterTypes[i]) }
            return m.invoke(instance, *converted)
        }

        fun int(manager: String, method: String, vararg args: Any): Int = when (val r = call(manager, method, *args)) {
            is Number -> r.toInt()
            is Boolean -> if (r) 1 else 0
            else -> throw IllegalStateException("$manager.$method returned $r")
        }

        fun bool(manager: String, method: String, vararg args: Any): Boolean = when (val r = call(manager, method, *args)) {
            is Boolean -> r
            is Number -> r.toInt() != 0
            else -> throw IllegalStateException("$manager.$method returned $r")
        }

        private fun convert(v: Any, type: Class<*>): Any = when (type) {
            java.lang.Byte.TYPE -> if (v is Boolean) (if (v) 1 else 0).toByte() else (v as Number).toByte()
            java.lang.Boolean.TYPE -> if (v is Boolean) v else (v as Number).toInt() != 0
            java.lang.Integer.TYPE -> if (v is Boolean) (if (v) 1 else 0) else (v as Number).toInt()
            else -> v
        }

        @Suppress("UNCHECKED_CAST")
        private fun points(full: Any) = fullCls.getField("coordinates").get(full) as Array<Array<Any>>

        private fun cls(name: String): Class<*> = Class.forName(name, true, loader)

        private fun field(c: Class<*>, name: String): Field = c.getDeclaredField(name).apply { isAccessible = true }
    }

    const val BRIGHTNESS_MAX = 10

    /** Шагов уменьшения на Z7X (getAllZoomStep.zoomOutMaxStep) — если прошивка не ответила. */
    const val ZOOM_MAX_DEFAULT = 38
    const val PANEL_W = 1920
    const val PANEL_H = 1080

    /** Ячейки сетки 9×9, в которых прошивка хранит 4 угла (режим kstMode=0). */
    private val CORNER_CELLS = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)

    /** Режим «свободной» трапеции, из которого сброс возвращает в обычный 4-точечный. */
    private const val KST_MODE_FREE = 6
}
