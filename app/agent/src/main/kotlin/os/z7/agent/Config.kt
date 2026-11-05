package os.z7.agent

/** Константы устройства/конфигурации. Специфика XGIMI Z7X (m7642) — см. docs/device-api.md. */
object Config {
    /** Локальный ADB-мост: adbd слушает 5555, ro.adb.secure=0 → без подтверждения ключа. */
    const val ADB_HOST = "127.0.0.1"
    const val ADB_PORT = 5555

    /**
     * AirPlay-приёмник — наша сборка открытого jqssun/android-airplay-server (UxPlay) с синхронизацией
     * видео по часам звука: github.com/shishkins/android-airplay-server, ветка z7. Код приложения
     * остался в пакете io.github.jqssun.airplay, поэтому имена классов — с ним.
     */
    const val RECEIVER_PKG = "os.z7.airplay"
    const val RECEIVER_BOOT_RECEIVER = "io.github.jqssun.airplay.BootReceiver"
    const val RECEIVER_PORT = 7000

    /** Прежний приёмник из официальных релизов: рядом с нашим он рекламирует то же имя — выключаем. */
    const val LEGACY_RECEIVER_PKG = "io.github.jqssun.airplay"

    /** Наш главный экран и его служба кнопок пульта (быстрая панель по долгому «меню»). */
    const val LAUNCHER_PKG = "os.z7.launcher"
    const val KEY_SERVICE = "os.z7.launcher/os.z7.launcher.KeyService"

    /** Поставщик геолокации, который должен быть «включён», чтобы лаунчер видел сети Wi-Fi. */
    const val LOCATION_PROVIDER = "network"

    /** Действие, по которому BootReceiver приёмника стартует сервер без окна. */
    const val RECEIVER_START_ACTION = "android.intent.action.QUICKBOOT_POWERON"

    /** Сколько ждать запуска приёмника без окна, прежде чем открыть его окно. */
    const val RECEIVER_HEADLESS_WAIT_MS = 12_000L

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

    /** Сколько ждать после HOME, пока сток вернёт домашнюю Wi-Fi (он делает это через ~2 с). */
    const val MIRACAST_WIFI_RESTORE_MS = 6_000L

    /**
     * Держать домашнюю Wi-Fi во время Miracast (`persist.z7.miracast_keep_wifi` = 1/0).
     * Сток отключает все сохранённые сети (WifiSwitchManager.disable), агент включает домашнюю
     * обратно через IWifiManager.enableNetwork от имени shell (у него NETWORK_SETTINGS).
     * Цена: P2P-группа делит радио с домашней сетью (её канал 2.4 ГГц). По умолчанию выключено.
     */
    const val MIRACAST_KEEP_WIFI_PROP = "persist.z7.miracast_keep_wifi"
    const val MIRACAST_KEEP_WIFI_DEFAULT = false

    /** Номера вызовов IWifiManager на Android 9 этой прошивки (framework.jar, IWifiManager.Stub). */
    const val WIFI_TX_ENABLE_NETWORK = 17
    const val WIFI_TX_RECONNECT = 22

    /** Сколько дать стоковому приёмнику на запуск, прежде чем сбросить первую P2P-группу. */
    const val MIRACAST_RECEIVER_READY_MS = 3_000L

    /**
     * P2P-маяк (Win+K без нажатий) выключен: listen уводит радио с канала домашней сети,
     * и если iPhone подключается в этот момент, AirPlay с ошибкой синхронизирует часы
     * (звук отстаёт до конца сессии). Включать только вместе с защитой AirPlay.
     */
    const val P2P_BEACON_ENABLED = false

    /** Свойство-переключатель маяка для проверки на проекторе (см. AgentService.beaconAllowed). */
    const val BEACON_PROP = "persist.z7.p2p_beacon"
    const val BEACON_PROP_EVERY_MS = 60_000L

    /** Как часто перезапускать P2P-поиск (один поиск держит listen ~2 мин). */
    const val P2P_DISCOVER_EVERY_MS = 90_000L

    /** Период проверки, мс. */
    const val WATCH_INTERVAL_MS = 10_000L

    /** Таймаут одной shell-команды через локальный ADB. */
    const val ADB_CMD_TIMEOUT_MS = 15_000L

    /**
     * Как часто будильник поднимает агента, если система убила процесс.
     * XGIMI убивает сторонние приложения при уходе в режим ожидания, а перезапуск
     * упавших и запуск по BOOT_COMPLETED для них запрещён в самом system_server.
     * Свой будильник — единственный фоновый способ вернуться (см. [MEM_OPTIMISE_PROP]).
     */
    const val REVIVE_INTERVAL_MS = 60_000L

    /**
     * Флаг «оптимизации памяти» XGIMI: при `true` system_server не запускает процесс
     * стороннего приложения ни для какого broadcast — даже для его собственного будильника.
     * Агент держит его в `false`; restore.ps1 возвращает `true`.
     */
    const val MEM_OPTIMISE_PROP = "persist.xgimi.mem_optimise.enable"

    /**
     * Язык системы вместо китайского прошивки (см. [SystemLocale]): английский кодом `eng`,
     * потому что `en` настройки XGIMI откатывают на китайский.
     */
    const val DEFAULT_LOCALE = "eng-US"

    /** Как часто писать «пульс» в лог (агент жив, состояние каста). */
    const val HEARTBEAT_MS = 30 * 60_000L
}
