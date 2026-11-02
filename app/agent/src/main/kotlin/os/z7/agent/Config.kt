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
     * AirPlay мешает нашему приёмнику, Miracast нужен — поэтому гасим только вне Miracast.
     */
    const val STOCK_CAST_PKG = "com.xgimi.wirelessscreen"
    const val STOCK_AIRPLAY_PORT = 7100

    /** Признаки активной Miracast-сессии в mResumedActivity. */
    val MIRACAST_MARKERS = listOf("com.xgimi.wfd", "MirCastActivity")

    /** Период проверки каста, мс. */
    const val WATCH_INTERVAL_MS = 10_000L
}
