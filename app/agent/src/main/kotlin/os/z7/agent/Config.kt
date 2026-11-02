package os.z7.agent

/** Константы устройства/конфигурации. Специфика XGIMI Z7X (m7642) — см. docs/device-api.md. */
object Config {
    /** Локальный ADB-мост: adbd слушает 5555, ro.adb.secure=0 → без ключа. */
    const val ADB_HOST = "127.0.0.1"
    const val ADB_PORT = 5555

    /** Открытый AirPlay-приёмник (UxPlay). См. docs/cast-setup.md. */
    const val RECEIVER_PKG = "io.github.jqssun.airplay"

    /**
     * Стоковые каст-движки — конкуренты в mDNS. Их гасим перед запуском приёмника.
     * wirelessscreen держит ещё и Miracast, поэтому НЕ отключаем пакет,
     * только force-stop при необходимости (он сам перезапустится).
     */
    val STOCK_CAST_PKGS = listOf(
        "com.xgimi.wirelessscreen",
        "com.hpplay.happyplay.aw",
    )

    /** Период проверки живости каста, мс. */
    const val WATCH_INTERVAL_MS = 20_000L

    /** Имя приёмника для сети. */
    const val RECEIVER_NAME = "XGIMI Projector"
}
