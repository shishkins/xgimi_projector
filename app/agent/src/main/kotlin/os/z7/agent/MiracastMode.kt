package os.z7.agent

import android.content.Context

/**
 * Режим «Трансляция с Windows» (Miracast).
 *
 * Приёмник RTSP — стоковый WifiDisplayActivity (пакет com.xgimi.wirelessscreen). Вне сессии
 * пакет выключен: его AirPlay перехватывает iPhone. Сессия начинается двумя путями:
 *   1. Win+K без нажатий: [P2pBeacon] держит проектор видимым, Windows подключается,
 *      формируется P2P-группа → [onIncoming] сразу открывает стоковый приёмник;
 *   2. кнопка «Cast from Windows» → [start] (запасной путь, если маяк не сработал).
 * Дальше агент ведёт сессию и сам возвращает проектор в обычный режим:
 *   requested → (Windows подключилась: groupFormed) → connected → (разрыв > grace) → выключение
 *   requested → (никто не подключился за MIRACAST_WAIT_MS) → выключение
 *
 * Состояние хранится в SharedPreferences: кнопка (активити) и агент (сервис) живут
 * в одном процессе, но запускаются независимо. Методы synchronized: [onIncoming]
 * приходит из broadcast-а P2P, [tick] — из цикла агента.
 */
class MiracastMode(context: Context, private val adb: AdbBridge) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lostSince = 0L

    val isRequested: Boolean get() = prefs.getLong(KEY_REQUESTED_AT, 0L) > 0L

    /** Включить режим вручную: вызывает кнопка «Cast from Windows». */
    @Synchronized
    fun start() {
        Logf.w("miracast: requested")
        begin(connected = false)
    }

    /** Windows уже подключилась по P2P (Win+K) — поднять приёмник как можно быстрее. */
    @Synchronized
    fun onIncoming() {
        if (isRequested) return
        Logf.w("miracast: incoming Win+K connection — opening receiver")
        begin(connected = true)
    }

    private fun begin(connected: Boolean) {
        prefs.edit().putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_CONNECTED, connected).apply()
        lostSince = 0L
        adb.run("pm enable ${Config.STOCK_CAST_PKG}")
        adb.run("am start -a ${Config.MIRACAST_ACTION}")
    }

    /** Шаг watchdog. Возвращает true, пока режим Miracast активен. */
    @Synchronized
    fun tick(): Boolean {
        val requestedAt = prefs.getLong(KEY_REQUESTED_AT, 0L)
        if (requestedAt == 0L) return false

        val now = System.currentTimeMillis()
        val groupFormed = adb.run("dumpsys wifip2p | grep groupFormed")
            ?.contains("groupFormed: true") == true
        val connected = prefs.getBoolean(KEY_CONNECTED, false)

        when {
            groupFormed -> {
                if (!connected) {
                    Logf.w("miracast: Windows connected")
                    prefs.edit().putBoolean(KEY_CONNECTED, true).apply()
                }
                lostSince = 0L
            }
            connected -> {
                if (lostSince == 0L) lostSince = now
                if (now - lostSince >= Config.MIRACAST_END_GRACE_MS) {
                    stop("session ended")
                    return false
                }
            }
            now - requestedAt >= Config.MIRACAST_WAIT_MS -> {
                stop("nobody connected")
                return false
            }
        }
        return true
    }

    /**
     * Вернуть обычный режим. Экран Miracast закрываем кнопкой HOME, а не force-stop:
     * домашнюю Wi-Fi сток включает обратно только в WifiDisplayActivity.onDestroy
     * (WifiSwitchManager.enable, с задержкой ~2 с). После force-stop сохранённые сети
     * остаются выключенными, и проектор пропадает из сети.
     */
    @Synchronized
    fun stop(reason: String) {
        Logf.w("miracast: stop ($reason)")
        val resumed = adb.run("dumpsys activity activities | grep mResumedActivity").orEmpty()
        if (resumed.contains(Config.STOCK_CAST_PKG)) {
            adb.run("input keyevent KEYCODE_HOME")
            Thread.sleep(Config.MIRACAST_WIFI_RESTORE_MS)
        }
        adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
        adb.run("pm disable-user --user 0 ${Config.STOCK_CAST_PKG}")
        prefs.edit().clear().apply()
        lostSince = 0L
    }

    companion object {
        private const val PREFS = "miracast"
        private const val KEY_REQUESTED_AT = "requested_at"
        private const val KEY_CONNECTED = "connected"
    }
}
