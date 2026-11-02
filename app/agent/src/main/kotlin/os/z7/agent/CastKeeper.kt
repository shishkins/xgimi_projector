package os.z7.agent

/**
 * Держит беспроводной каст в порядке:
 *  1. наш AirPlay-приёмник запущен (поднимает, если упал или не стартовал после загрузки);
 *  2. вне Miracast-сессии стоковый каст XGIMI выключен. Его AirPlay (waxrain) иначе
 *     перехватывает подключения iPhone: в списке виден «XGIMI Projector», а соединение
 *     уходит на стоковый (китайское имя, iOS 27 не поддерживает). Отключить только его
 *     компонент shell не может, а сам пакет поднимают сервисы XGIMI — поэтому пакет
 *     выключается целиком (pm disable-user) и включается лишь на время Miracast.
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

    /** Стоковый каст сейчас включён как пакет? */
    private fun stockCastEnabled(): Boolean =
        adb.run("pm list packages -e ${Config.STOCK_CAST_PKG}")
            ?.contains("package:${Config.STOCK_CAST_PKG}") == true

    /** Шаг watchdog. [miracastActive] — идёт ли сейчас режим «Трансляция с Windows». */
    fun ensureCast(miracastActive: Boolean) {
        try {
            if (!miracastActive && stockCastEnabled()) {
                Logf.w("ensureCast: stock cast enabled outside Miracast — disabling")
                adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
                adb.run("pm disable-user --user 0 ${Config.STOCK_CAST_PKG}")
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
