package os.z7.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Наш приёмник Miracast (os.z7.miracast): подготовка Wi-Fi и сеанс.
 *
 * 1. Атрибуты WFD («я — дисплей», порт RTSP) ставит только системное приложение (setWFDInfo
 *    требует CONFIGURE_WIFI_DISPLAY), а после перезагрузки или перезапуска Wi-Fi они пропадают.
 *    Тогда агент на пару секунд поднимает стоковый приёмник: тот ставит атрибуты при запуске,
 *    а P2P-служба хранит их и после его закрытия. Китайский экран стока закрывает накладка
 *    лаунчера (MiracastCover).
 * 2. На время сеанса домашняя Wi-Fi выключается, как у стока. Если группа Wi-Fi Direct делит
 *    радио с домашней сетью на другом канале, драйвер MT7961 при разрыве сеанса виснет: Wi-Fi
 *    молча перестаёт работать и не включается даже после перезагрузки — только после
 *    отключения питания. Без домашней сети этого не бывает, а картинка ровнее (нет сканирований).
 * 3. Приёмник закрылся — агент возвращает сеть (сторож [tick], с запасом на падения).
 */
object OwnMiracast {
    private const val PREFS = "own_miracast"
    private const val KEY_ACTIVE = "session_active"
    private const val EXTRA_UI_LANGUAGE = "z7.ui_language"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(Config.OWN_MIRACAST_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Идёт подготовка: сторож агента не должен выключать сток. */
    @Volatile var priming = false
        private set

    /** Кнопка «Cast from Windows»: подготовить Wi-Fi и открыть приёмник. Блокирует поток (до ~10 с). */
    fun startSession(context: Context, adb: AdbBridge, uiLanguage: String?) {
        synchronized(this) {
            priming = true
            try {
                prime(adb)
                quietWifi(adb)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            } catch (e: Exception) {
                Logf.w("miracast: session setup failed: ${e.message}")
            } finally {
                priming = false
            }
        }
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(Config.OWN_MIRACAST_PKG, Config.OWN_MIRACAST_ACTIVITY))
            .putExtra(EXTRA_UI_LANGUAGE, uiLanguage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var closedTicks = 0

    /** Шаг сторожа: приёмник больше не на экране — вернуть домашнюю Wi-Fi. */
    fun tick(context: Context, adb: AdbBridge) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false) || priming) { closedTicks = 0; return }
        val resumed = adb.run("dumpsys activity activities | grep mResumedActivity").orEmpty()
        if (resumed.contains(Config.OWN_MIRACAST_PKG)) { closedTicks = 0; return }
        if (++closedTicks < 2) return // одного раза мало: между окнами mResumedActivity бывает пустым
        Logf.w("miracast: receiver closed — restoring home Wi-Fi")
        adb.run("service call wifi ${Config.WIFI_TX_ENABLE_CONNECTIVITY_MANAGER} i32 1")
        enableSavedNetworks(adb)
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        closedTicks = 0
    }

    /** Атрибуты WFD, если их нет: поднять сток, дождаться, закрыть. */
    private fun prime(adb: AdbBridge) {
        if (wfdReady(adb)) return
        Logf.w("miracast: no WFD info in P2P service — priming via stock receiver")
        adb.run("pm enable ${Config.STOCK_CAST_PKG}")
        adb.run("am start -a ${Config.MIRACAST_ACTION}")
        var ready = false
        for (i in 0 until 20) {
            Thread.sleep(500)
            if (wfdReady(adb)) { ready = true; break }
        }
        adb.run("input keyevent KEYCODE_HOME")
        Thread.sleep(PRIME_EXIT_MS)
        adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
        adb.run("pm disable-user --user 0 ${Config.STOCK_CAST_PKG}")
        Logf.w("miracast: WFD info ${if (ready) "set" else "NOT set"}")
    }

    /** Выключить домашнюю сеть и фоновые сканирования на время сеанса. */
    private fun quietWifi(adb: AdbBridge) {
        val ids = SAVED_ID.findAll(adb.run("dumpsys wifi | grep -E '^ *(- DSBLE )?ID: [0-9]+ SSID'").orEmpty())
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        adb.run("service call wifi ${Config.WIFI_TX_ENABLE_CONNECTIVITY_MANAGER} i32 0")
        for (id in ids) {
            adb.run("service call wifi ${Config.WIFI_TX_DISABLE_NETWORK} i32 $id s16 com.android.shell")
        }
        Logf.w("miracast: home Wi-Fi paused for the session (networks $ids)")
    }

    private fun wfdReady(adb: AdbBridge): Boolean =
        adb.run("dumpsys wifip2p | grep 'WFD enabled'")?.contains("true") == true

    /** Включить сохранённые сети, выключенные стоком или сеансом. */
    fun enableSavedNetworks(adb: AdbBridge) {
        val list = adb.run("dumpsys wifi | grep -E '^ *- DSBLE ID: [0-9]+ SSID'").orEmpty()
        val ids = SAVED_ID.findAll(list).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        for (id in ids) {
            adb.run("service call wifi ${Config.WIFI_TX_ENABLE_NETWORK} i32 $id i32 0 s16 com.android.shell")
        }
        if (ids.isNotEmpty()) Logf.w("miracast: re-enabled saved networks $ids")
    }

    private val SAVED_ID = Regex("ID: (\\d+) SSID")
    private const val PRIME_EXIT_MS = 3_000L
}
