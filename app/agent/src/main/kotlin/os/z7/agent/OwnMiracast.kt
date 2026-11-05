package os.z7.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper

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
 * 4. Правило: группа Wi-Fi Direct НИКОГДА не разрывается при включённой домашней сети.
 *    Поэтому в конце сеанса сначала дожидаемся, пока группы не станет, и только потом
 *    включаем сеть; а группу, поднятую вне сеанса (Windows переподключилась к закрытому
 *    приёмнику, прошивка сама принимает приглашения), снимаем при выключенной сети ([guardOrphan]).
 *    2026-11-05 такая «самовольная» группа в 18:03 предшествовала мёртвому Wi-Fi после ребута.
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
        endSession(context, adb, "receiver gone")
    }

    /** Вернуть домашнюю Wi-Fi: приёмник закрылся (его сигнал [EndedReceiver] или сторож). */
    @Synchronized
    fun endSession(context: Context, adb: AdbBridge, why: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        Logf.w("miracast: session over ($why) — restoring home Wi-Fi")
        dropGroupAndWait(context, adb)
        adb.run("service call wifi ${Config.WIFI_TX_ENABLE_CONNECTIVITY_MANAGER} i32 1")
        enableSavedNetworks(adb)
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        closedTicks = 0
    }

    /**
     * Группа Wi-Fi Direct появилась вне нашего сеанса (и не во время подготовки) — домашняя
     * сеть при этом включена. Ставим сеть на паузу, снимаем группу, ждём, возвращаем сеть.
     */
    @Synchronized
    fun guardOrphan(context: Context, adb: AdbBridge) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACTIVE, false) || priming) return
        Logf.w("p2p: group formed outside our session — pausing home Wi-Fi before dropping it")
        quietWifi(adb)
        dropGroupAndWait(context, adb)
        adb.run("service call wifi ${Config.WIFI_TX_ENABLE_CONNECTIVITY_MANAGER} i32 1")
        enableSavedNetworks(adb)
    }

    /** Снять P2P-группу и дождаться, пока её не станет (до ~10 с). Вызывать при выключенной сети. */
    private fun dropGroupAndWait(context: Context, adb: AdbBridge) {
        if (!groupPresent(adb)) return
        try {
            val mgr = context.getSystemService(WifiP2pManager::class.java)
            val ch = mgr.initialize(context, Looper.getMainLooper(), null)
            mgr.removeGroup(ch, null)
        } catch (e: Exception) {
            Logf.w("p2p: removeGroup failed: ${e.message}")
        }
        for (i in 0 until GROUP_WAIT_STEPS) {
            Thread.sleep(500)
            if (!groupPresent(adb)) {
                Thread.sleep(GROUP_SETTLE_MS) // драйверу — время довести разрыв до конца
                Logf.w("p2p: group gone")
                return
            }
        }
        Logf.w("p2p: group still present after waiting")
    }

    /** В dumpsys: «mGroup null» — группы нет; «mGroup network: …» — есть. */
    private fun groupPresent(adb: AdbBridge): Boolean {
        val line = adb.run("dumpsys wifip2p | grep '^mGroup '").orEmpty()
        return line.contains("mGroup") && !line.contains("mGroup null")
    }

    /** Приёмник сообщает, что закрылся, — сеть возвращаем сразу, не дожидаясь сторожа. */
    class EndedReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            val pending = goAsync()
            Thread {
                val adb = AdbBridge(app.filesDir)
                try {
                    endSession(app, adb, "receiver closed")
                } finally {
                    adb.close()
                    pending.finish()
                }
            }.start()
        }
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
    private const val GROUP_WAIT_STEPS = 20
    private const val GROUP_SETTLE_MS = 1_000L
}
