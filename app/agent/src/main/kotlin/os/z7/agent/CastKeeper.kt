package os.z7.agent

import android.util.Log

/**
 * Держит беспроводной каст живым: следит, что AirPlay-приёмник запущен,
 * и поднимает его (погасив стоковых конкурентов), если он упал или не стартовал
 * после загрузки. См. docs/cast-setup.md — почему это делает агент, а не сам приёмник.
 */
class CastKeeper(private val adb: AdbBridge) {

    @Volatile
    var lastState: String = "init"
        private set

    /** Приёмник запущен? (по наличию процесса) */
    private fun receiverRunning(): Boolean {
        val pid = adb.run("pidof ${Config.RECEIVER_PKG}")?.trim().orEmpty()
        return pid.isNotEmpty()
    }

    /** Применить настройки приёмника один раз (имя + режим передачи видео). */
    fun applyReceiverSettings() {
        setSetting("server_name", Config.RECEIVER_NAME)
        // Режим передачи видео (а не полного зеркалирования): iPhone отдаёт видеопоток,
        // приёмник играет нативно — полное разрешение, синхронный звук, без лагов.
        // Зеркалирование остаётся доступным (advertise_mirroring=true по умолчанию).
        setSetting("advertise_video", "true")
        setSetting("resolution", "auto")
        setSetting("boot_auto_start", "true")
        setSetting("run_in_background", "true")
    }

    private fun setSetting(key: String, value: String) {
        adb.run(
            "am broadcast -n ${Config.RECEIVER_PKG}/.SettingsReceiver " +
                "-a ${Config.RECEIVER_PKG}.SET_SETTING --es key '$key' --es value '$value'"
        )
    }

    /** Основной шаг: если приёмник не работает — поднять его. */
    fun ensureCast() {
        try {
            if (receiverRunning()) {
                lastState = "ok"
                Logf.w("ensureCast: receiver ok")
                return
            }
            Logf.w("ensureCast: receiver down — restarting cast")
            // погасить конкурентов в mDNS (wirelessscreen перезапустится сам; Miracast не теряем)
            for (pkg in Config.STOCK_CAST_PKGS) {
                adb.run("am force-stop $pkg")
            }
            // запустить приёмник
            adb.run(
                "monkey -p ${Config.RECEIVER_PKG} -c android.intent.category.LAUNCHER 1"
            )
            Thread.sleep(3_000)
            lastState = if (receiverRunning()) "restarted" else "launch-failed"
            Log.i(TAG, "cast state: $lastState")
        } catch (e: Exception) {
            lastState = "error: ${e.message}"
            Log.w(TAG, "ensureCast failed", e)
        }
    }

    companion object {
        private const val TAG = "z7.CastKeeper"
    }
}
