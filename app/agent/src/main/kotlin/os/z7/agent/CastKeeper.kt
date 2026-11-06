package os.z7.agent

import android.content.Context
import android.content.Intent
import android.os.SystemClock

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
class CastKeeper(private val context: Context, private val adb: AdbBridge) {

    @Volatile
    var lastState: String = "init"
        private set

    /** Неудачных запусков без окна подряд (см. [Config.RECEIVER_WINDOW_AFTER_FAILURES]). */
    private var headlessFailures = 0

    /** Наш приёмник запущен? (по наличию процесса) */
    private fun receiverRunning(): Boolean {
        val pid = adb.run("pidof ${Config.RECEIVER_PKG}")?.trim().orEmpty()
        return pid.isNotEmpty()
    }

    /** Стоковый каст сейчас включён как пакет? */
    private fun stockCastEnabled(): Boolean =
        adb.run("pm list packages -e ${Config.STOCK_CAST_PKG}")
            ?.contains("package:${Config.STOCK_CAST_PKG}") == true

    /**
     * Запуск сервера приёмника без UI: его BootReceiver (при boot_auto_start=true) поднимает
     * AirPlayService. Свой BOOT_COMPLETED XGIMI ему не доставляет (см. [Config.REVIVE_INTERVAL_MS]),
     * поэтому шлём явный QUICKBOOT_POWERON — его приёмник обрабатывает так же, а отправлять его
     * может обычное приложение. Окно приёмника откроется само при подключении iPhone.
     */
    private fun startHeadless() {
        try {
            context.sendBroadcast(
                Intent(Config.RECEIVER_START_ACTION)
                    .setClassName(Config.RECEIVER_PKG, Config.RECEIVER_BOOT_RECEIVER)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    // фоновая очередь после загрузки/установки забита рассылками на десятки секунд
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        } catch (e: Exception) {
            Logf.w("ensureCast: headless broadcast failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Холодный старт процесса на проекторе занимает несколько секунд — ждём с опросом. */
    private fun awaitReceiver(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000)
            if (receiverRunning()) return true
        }
        return false
    }

    /** Если на экране пустое окно ожидания приёмника — вернуться на главный экран. */
    private fun leaveReceiverScreen() {
        val resumed = adb.run("dumpsys activity activities | grep mResumedActivity").orEmpty()
        if (resumed.contains(Config.RECEIVER_PKG)) adb.run("input keyevent KEYCODE_HOME")
    }

    /** Шаг watchdog. [miracastActive] — идёт ли сейчас режим «Трансляция с Windows». */
    fun ensureCast(miracastActive: Boolean) {
        try {
            // во время подготовки своего приёмника сток включён намеренно (ставит атрибуты WFD)
            if (!miracastActive && !OwnMiracast.priming && stockCastEnabled()) {
                Logf.w("ensureCast: stock cast enabled outside Miracast — disabling")
                adb.run("am force-stop ${Config.STOCK_CAST_PKG}")
                adb.run("pm disable-user --user 0 ${Config.STOCK_CAST_PKG}")
                // после force-stop сток не возвращает выключенные им сети — возвращаем сами
                OwnMiracast.enableSavedNetworks(adb)
            }
            if (receiverRunning()) {
                if (lastState != "ok") Logf.w("ensureCast: ok (receiver up)")
                lastState = "ok"
                return
            }
            Logf.w("ensureCast: receiver down — starting it in background")
            startHeadless()
            if (awaitReceiver(Config.RECEIVER_HEADLESS_WAIT_MS)) {
                headlessFailures = 0
            } else {
                headlessFailures++
                if (headlessFailures < Config.RECEIVER_WINDOW_AFTER_FAILURES ||
                    SystemClock.elapsedRealtime() < Config.RECEIVER_NO_WINDOW_AFTER_BOOT_MS) {
                    // без окна — повторим на следующем шаге сторожа
                    lastState = "starting"
                    Logf.w("ensureCast: receiver not up yet (attempt $headlessFailures) — retrying in background")
                    return
                }
                // запасной путь: через окно приёмника, и сразу назад на главный экран
                Logf.w("ensureCast: headless start failed $headlessFailures times — launching activity")
                headlessFailures = 0
                adb.run("monkey -p ${Config.RECEIVER_PKG} -c android.intent.category.LAUNCHER 1")
                Thread.sleep(3_000)
                leaveReceiverScreen()
            }
            lastState = if (receiverRunning()) "restarted" else "launch-failed"
            Logf.w("ensureCast: $lastState")
        } catch (e: Exception) {
            lastState = "error: ${e.message}"
            Logf.w("ensureCast failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
