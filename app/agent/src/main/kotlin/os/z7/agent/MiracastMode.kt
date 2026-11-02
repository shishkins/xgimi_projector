package os.z7.agent

import android.content.Context

/**
 * Режим «Трансляция с Windows» (Miracast).
 *
 * Miracast идёт через Wi-Fi Direct и занимает единственную антенну проектора,
 * поэтому держать его включённым постоянно нельзя. Пользователь включает режим
 * кнопкой («Cast from Windows»), агент ведёт сессию и сам возвращает проектор
 * в обычный режим:
 *   requested → (Windows подключилась: groupFormed) → connected → (разрыв > grace) → выключение
 *   requested → (никто не подключился за MIRACAST_WAIT_MS) → выключение
 *
 * Состояние хранится в SharedPreferences: кнопка (активити) и агент (сервис) живут
 * в одном процессе, но запускаются независимо.
 */
class MiracastMode(context: Context, private val adb: AdbBridge) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lostSince = 0L

    val isRequested: Boolean get() = prefs.getLong(KEY_REQUESTED_AT, 0L) > 0L

    /** Включить режим: вызывает кнопка «Cast from Windows». */
    fun start() {
        prefs.edit().putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_CONNECTED, false).apply()
        lostSince = 0L
        Logf.w("miracast: requested")
        adb.run("pm enable ${Config.STOCK_CAST_PKG}")
        adb.run("am start -a ${Config.MIRACAST_ACTION}")
    }

    /** Шаг watchdog. Возвращает true, пока режим Miracast активен. */
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

    /** Вернуть обычный режим: погасить стоковый каст и выключить пакет. */
    fun stop(reason: String) {
        Logf.w("miracast: stop ($reason)")
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
