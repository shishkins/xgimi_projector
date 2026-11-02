package os.z7.agent

/** Константы устройства/конфигурации. Специфика XGIMI Z7X (m7642) — см. docs/device-api.md. */
object Config {
    /** Локальный ADB-мост: adbd слушает 5555, ro.adb.secure=0 → без подтверждения ключа. */
    const val ADB_HOST = "127.0.0.1"
    const val ADB_PORT = 5555

    /** Открытый AirPlay-приёмник (UxPlay). См. docs/cast-setup.md. */
    const val RECEIVER_PKG = "io.github.jqssun.airplay"

    /**
     * Стоковый каст XGIMI: внутри и AirPlay (waxrain, порт 7100), и Miracast.
     * Его AirPlay перехватывает подключения iPhone, поэтому в обычном режиме пакет выключен
     * (pm disable-user) и включается только на время Miracast.
     */
    const val STOCK_CAST_PKG = "com.xgimi.wirelessscreen"
    const val STOCK_AIRPLAY_PORT = 7100

    /** Интент стокового Miracast-приёмника (WifiDisplayActivity). */
    const val MIRACAST_ACTION = "com.xgimi.NEWMIRACAST"

    /** Сколько ждать подключения Windows после нажатия «Cast from Windows». */
    const val MIRACAST_WAIT_MS = 3 * 60_000L

    /** Сколько ждать после разрыва сессии, прежде чем вернуть проектор в обычный режим. */
    const val MIRACAST_END_GRACE_MS = 20_000L

    /** Период проверки, мс. */
    const val WATCH_INTERVAL_MS = 10_000L
}
