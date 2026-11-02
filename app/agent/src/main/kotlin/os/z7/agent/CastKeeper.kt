package os.z7.agent

/**
 * Держит беспроводной каст в порядке:
 *  1. наш AirPlay-приёмник запущен (поднимает, если упал или не стартовал после загрузки);
 *  2. стоковый AirPlay XGIMI (waxrain в com.xgimi.wirelessscreen) не вещает параллельно.
 *     Иначе iPhone видит «XGIMI Projector», но при подключении попадает на стоковый
 *     (китайское имя, iOS 27 не поддерживает). Отключить только его компонент shell
 *     не может, тумблера в настройках нет (включён жёстко в CastConfig), поэтому гасим
 *     wirelessscreen целиком — но НЕ во время Miracast: он живёт в том же приложении.
 * См. docs/cast-setup.md.
 */
class CastKeeper(private val adb: AdbBridge) {

    @Volatile
    var lastState: String = "init"
        private set

    /** Наш приёмник запущен? (по наличию процесса) */
    private fun receiverRunning(): Boolean {
        val pid = adb.run("pidof ${Config.RECEIVER_PKG}")?.trim().orEmpty()
        return pid.isNotEmpty()
    }

    /** Стоковый AirPlay слушает свой порт? */
    private fun stockAirplayListening(): Boolean {
        val tcp = adb.run("cat /proc/net/tcp /proc/net/tcp6") ?: return false
        val portHex = ":%04X".format(Config.STOCK_AIRPLAY_PORT)
        return tcp.lineSequence().any { line ->
            val f = line.trim().split(Regex("\\s+"))
            f.size > 3 && f[1].endsWith(portHex) && f[3] == "0A"
        }
    }

    /** Идёт Miracast-сессия (на экране стоковый WFD-приёмник)? */
    private fun miracastActive(): Boolean {
        val top = adb.run("dumpsys activity activities | grep mResumedActivity") ?: return false
        return Config.MIRACAST_MARKERS.any { top.contains(it) }
    }

    /** Основной шаг watchdog. */
    fun ensureCast() {
        try {
            if (stockAirplayListening() && !miracastActive()) {
                Logf.w("ensureCast: stock AirPlay is up — stopping ${Config.STOCK_CAST_PKG}")
                adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
            }
            if (receiverRunning()) {
                lastState = "ok"
                return
            }
            Logf.w("ensureCast: receiver down — starting it")
            adb.run("monkey -p ${Config.RECEIVER_PKG} -c android.intent.category.LAUNCHER 1")
            Thread.sleep(3_000)
            lastState = if (receiverRunning()) "restarted" else "launch-failed"
            Logf.w("ensureCast: $lastState")
        } catch (e: Exception) {
            lastState = "error: ${e.message}"
            Logf.w("ensureCast failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
