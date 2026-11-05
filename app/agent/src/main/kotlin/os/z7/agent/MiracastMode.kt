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

    private val ownReceiver = OwnMiracast.installed(context)

    /** Включить режим вручную: вызывает кнопка «Cast from Windows». */
    @Synchronized
    fun start() {
        Logf.w("miracast: requested")
        begin(connected = false)
    }

    /**
     * Windows постучалась по P2P (Win+K): открыть стоковый приёмник.
     * Эту первую группу вызывающий затем сбрасывает (см. AgentService): она поднята, пока
     * проектор был в домашней сети, поэтому сидит на её канале 2.4 ГГц (лаги, отставание звука),
     * а сток к тому же пропускает начало RTSP-сессии. Windows переподключается сама, уже к
     * готовому приёмнику, который отключил домашнюю сеть, и канал выбирается свободно.
     * Поэтому стартуем в режиме ожидания (connected=false), а не «уже подключено».
     * @return true, если сессия начата сейчас (а не шла уже).
     */
    @Synchronized
    fun onIncoming(): Boolean {
        if (isRequested) return false
        if (ownReceiver) return false // Win+K принимает наш приёмник сам
        Logf.w("miracast: incoming Win+K connection — opening receiver")
        begin(connected = false)
        return true
    }

    private fun begin(connected: Boolean) {
        val homeId = homeNetworkId()
        prefs.edit().putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_CONNECTED, connected)
            .putInt(KEY_HOME_NET_ID, homeId).apply()
        Logf.w("miracast: home network id=$homeId keepWifi=${keepWifi()}")
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
        keepHomeWifi()

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
        enableSavedNetworks()
        prefs.edit().clear().apply()
        lostSince = 0L
    }

    /**
     * Сток отключил сохранённые сети — включить домашнюю обратно (см. [Config.MIRACAST_KEEP_WIFI_PROP]).
     * Остальные сети не трогаем: их вернёт сам сток при выходе (WifiSwitchManager.enable).
     */
    private fun keepHomeWifi() {
        if (!keepWifi()) return
        val id = prefs.getInt(KEY_HOME_NET_ID, -1)
        if (id < 0) return
        val info = adb.run("dumpsys wifi | grep -m1 mNetworkInfo").orEmpty()
        if (info.contains("CONNECTED/CONNECTED") || info.contains("CONNECTING")) return
        val r = adb.run("service call wifi ${Config.WIFI_TX_ENABLE_NETWORK} i32 $id i32 0 s16 com.android.shell")
        adb.run("service call wifi ${Config.WIFI_TX_RECONNECT} s16 com.android.shell")
        Logf.w("miracast: home Wi-Fi re-enabled (id=$id): ${r?.trim()}")
    }

    private fun enableSavedNetworks() = OwnMiracast.enableSavedNetworks(adb)

    /** id сети, к которой проектор подключён сейчас (-1, если ни к какой). */
    private fun homeNetworkId(): Int {
        val line = adb.run("dumpsys wifi | grep -m1 mWifiInfo").orEmpty()
        return NET_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private var keepWifiProp: Boolean? = null
    private var keepWifiAt = 0L

    private fun keepWifi(): Boolean {
        val now = System.currentTimeMillis()
        if (keepWifiAt == 0L || now - keepWifiAt >= Config.BEACON_PROP_EVERY_MS) {
            keepWifiAt = now
            keepWifiProp = when (adb.run("getprop ${Config.MIRACAST_KEEP_WIFI_PROP}")?.trim()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
        return keepWifiProp ?: Config.MIRACAST_KEEP_WIFI_DEFAULT
    }

    companion object {
        private const val PREFS = "miracast"
        private const val KEY_REQUESTED_AT = "requested_at"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_HOME_NET_ID = "home_net_id"
        private val NET_ID = Regex("Net ID: (-?\\d+)")
    }
}
