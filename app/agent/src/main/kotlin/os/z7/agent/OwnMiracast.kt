package os.z7.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Наш приёмник Miracast (os.z7.miracast): установлен ли, как его открыть и как подготовить
 * Wi-Fi Direct. Атрибуты WFD («я — дисплей», порт RTSP) ставит только системное приложение
 * (setWFDInfo требует CONFIGURE_WIFI_DISPLAY), а после перезагрузки или перезапуска Wi-Fi они
 * пропадают. Тогда агент на пару секунд поднимает стоковый приёмник: тот ставит атрибуты при
 * запуске, а P2P-служба хранит их и после его закрытия. Китайский экран стока закрывает
 * накладка лаунчера (MiracastCover).
 */
object OwnMiracast {
    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(Config.OWN_MIRACAST_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun open(context: Context) {
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(Config.OWN_MIRACAST_PKG, Config.OWN_MIRACAST_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Идёт подготовка: сторож агента не должен выключать сток, повторное нажатие — ждёт. */
    @Volatile var priming = false
        private set

    /** Поставить атрибуты WFD, если их нет. Блокирует поток на время подготовки (до ~10 с). */
    fun ensureWfd(adb: AdbBridge) {
        synchronized(this) {
            priming = true
            try {
                prime(adb)
            } finally {
                priming = false
            }
        }
    }

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
        // HOME, а не force-stop: сток возвращает сохранённые сети только в onDestroy
        adb.run("input keyevent KEYCODE_HOME")
        Thread.sleep(PRIME_EXIT_MS)
        adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
        adb.run("pm disable-user --user 0 ${Config.STOCK_CAST_PKG}")
        enableSavedNetworks(adb)
        Logf.w("miracast: WFD info ${if (ready) "set" else "NOT set"}")
    }

    private fun wfdReady(adb: AdbBridge): Boolean =
        adb.run("dumpsys wifip2p | grep 'WFD enabled'")?.contains("true") == true

    /** Сток отключает все сохранённые сети и не всегда включает их обратно. */
    fun enableSavedNetworks(adb: AdbBridge) {
        val list = adb.run("dumpsys wifi | grep -E '^ *- DSBLE ID: [0-9]+ SSID'").orEmpty()
        val ids = DISABLED_ID.findAll(list).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        for (id in ids) {
            adb.run("service call wifi ${Config.WIFI_TX_ENABLE_NETWORK} i32 $id i32 0 s16 com.android.shell")
        }
        if (ids.isNotEmpty()) Logf.w("miracast: re-enabled saved networks $ids")
    }

    private val DISABLED_ID = Regex("DSBLE ID: (\\d+) SSID")
    private const val PRIME_EXIT_MS = 3_000L
}
