package os.z7.agent

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ядро-агент: постоянный фоновый сервис.
 * v0.1 — держит беспроводной каст живым через локальный ADB-мост
 * (наш AirPlay поднят, стоковый каст выключен вне Miracast).
 * Дальше сюда добавятся: обновлятор, перехват HOME, режим Miracast, профили пакетов.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adb by lazy { AdbBridge(filesDir) }
    private val castKeeper by lazy { CastKeeper(this, adb) }
    private val miracast by lazy { MiracastMode(this, adb) }
    private val beacon by lazy { P2pBeacon(this) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var loopJob: Job? = null

    /** Windows подключилась по Win+K: P2P-группа сформирована — сразу открываем приёмник. */
    /**
     * Лаунчер обновился (adb install -r, в будущем — обновление по воздуху): служба кнопок пульта
     * после этого может остаться подключённой лишь наполовину — клавиши не доходят (2026-11-05).
     * Переподключаем её: убрать из списка и вернуть.
     */
    private val launcherUpdated = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart != Config.LAUNCHER_PKG) return
            scope.launch {
                // сразу после обновления система ещё разбирает пакет — переподключение не цепляется
                delay(Config.KEYS_REBIND_AFTER_UPDATE_MS)
                Logf.w("keys: launcher updated — rebinding ${Config.KEY_SERVICE}")
                val current = adb.run("settings get secure enabled_accessibility_services")?.trim().orEmpty()
                val without = current.split(':').filter { it.isNotEmpty() && it != "null" && it != Config.KEY_SERVICE }
                adb.run("settings put secure enabled_accessibility_services " +
                    (without.joinToString(":").ifEmpty { "null" }))
                delay(Config.KEYS_REBIND_PAUSE_MS)
                ensureKeyService()
            }
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast) return // старое состояние на момент регистрации
            val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
            if (info?.groupFormed == true) {
                Logf.w("p2p: group formed (owner=${info.isGroupOwner})")
                scope.launch {
                    if (miracast.onIncoming()) {
                        delay(Config.MIRACAST_RECEIVER_READY_MS) // приёмник открылся и отключил домашнюю сеть
                        beacon.dropGroup()
                    } else {
                        // приёмник подготовил сеанс сам — ничего; иначе группа при домашней сети, снять безопасно
                        delay(Config.ORPHAN_GROUP_GRACE_MS)
                        OwnMiracast.guardOrphan(this@AgentService, adb)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logf.init(this)
        startForeground(NOTIF_ID, buildNotification())
        Logf.w("foreground started")
        scheduleRevive()
        // Настройки приёмника (имя, режим передачи видео) задаются один раз вручную
        // и сохраняются в самом приёмнике — агент их НЕ переустанавливает:
        // команды `am broadcast` к SettingsReceiver через dadb зависают.
        // Агент делает только критичное — watchdog каста (pidof/force-stop/monkey работают).
        registerReceiver(p2pReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        registerReceiver(launcherUpdated, IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") })
        loopJob = scope.launch {
            // кнопки пульта — первыми: без службы долгое «меню» открывает китайское меню
            ensureKeyService()
            logWifiState()
            ensureRevivable()
            ensureP2pPermission()
            SystemLocale(this@AgentService, adb).ensureSupported()
            ensureWifiAccess()
            retireLegacyReceiver()
            syncStockSettings()
            var lastBeat = 0L
            var lastKeysCheck = SystemClock.elapsedRealtime()
            while (isActive) {
                val miracastActive = miracast.tick()
                // Маяк уводит радио с канала домашней сети (пинг 3 → 130–260 мс),
                // поэтому он работает, только пока на проекторе ничего не играет.
                beacon.tick(enabled = beaconAllowed() && !miracastActive && !audio.isMusicActive && !airplayInUse())
                castKeeper.ensureCast(miracastActive)
                OwnMiracast.tick(this@AgentService, adb)
                val now = SystemClock.elapsedRealtime()
                // прошивка после загрузки переписывает список служб спецвозможностей — возвращаем свою
                if (now - lastKeysCheck >= Config.KEYS_CHECK_MS) {
                    ensureKeyService()
                    lastKeysCheck = now
                }
                if (now - lastBeat >= Config.HEARTBEAT_MS) {
                    Logf.w("heartbeat: cast=${castKeeper.lastState} miracast=$miracastActive")
                    lastBeat = now
                }
                delay(Config.WATCH_INTERVAL_MS)
            }
        }
        Log.i(TAG, "agent started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Каждый startForegroundService() (загрузка, будильник, кнопка) требует startForeground,
        // даже если сервис уже на переднем плане — иначе система уронит процесс.
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    /**
     * Будильник-страховка: на проекторе мало памяти, и система может убить процесс агента
     * без перезапуска. Раз в [Config.REVIVE_INTERVAL_MS] будильник будит [BootReceiver],
     * а тот поднимает сервис (если он жив — это просто no-op).
     */
    private fun ensureRevivable() {
        val value = adb.run("getprop ${Config.MEM_OPTIMISE_PROP}")?.trim()
        if (value != "false") {
            Logf.w("revive: ${Config.MEM_OPTIMISE_PROP}='$value' — setting false")
            adb.run("setprop ${Config.MEM_OPTIMISE_PROP} false")
        }
    }

    /** P2P-поиск требует разрешения на геолокацию; runtime-разрешение выдаём себе через shell. */
    private fun ensureP2pPermission() {
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            Logf.w("p2p: granting $perm")
            adb.run("pm grant $packageName $perm")
        }
        // свой приёмник Miracast: Wi-Fi Direct на Android 9 работает только с геолокацией
        if (OwnMiracast.installed(this) &&
            packageManager.checkPermission(perm, Config.OWN_MIRACAST_PKG) != PackageManager.PERMISSION_GRANTED) {
            Logf.w("p2p: granting $perm to ${Config.OWN_MIRACAST_PKG}")
            adb.run("pm grant ${Config.OWN_MIRACAST_PKG} $perm")
        }
    }

    /**
     * Служба кнопок пульта лаунчера (долгое «меню» → наша быстрая панель).
     * Службы специальных возможностей включаются только настройкой secure — её пишет shell.
     */
    /** Состояние Wi-Fi при старте — чтобы при зависании чипа MT7961 было видно, на чём. */
    private fun logWifiState() {
        val state = adb.run("dumpsys wifi | grep -E '^Wi-Fi is|mNetworkInfo' | head -2").orEmpty().trim()
        val link = adb.run("ip -o link show wlan0").orEmpty().trim().take(120)
        val oneLine = state.lines().joinToString(" | ")
        Logf.w("wifi at start: $oneLine | wlan0: ${link.ifEmpty { "absent" }}")
    }

    private fun ensureKeyService() {
        if (adb.run("pm path ${Config.LAUNCHER_PKG}").isNullOrBlank()) return
        val current = adb.run("settings get secure enabled_accessibility_services")?.trim().orEmpty()
        if (!current.contains(Config.KEY_SERVICE)) {
            val list = if (current.isEmpty() || current == "null") Config.KEY_SERVICE else "$current:${Config.KEY_SERVICE}"
            Logf.w("keys: enabling ${Config.KEY_SERVICE}")
            adb.run("settings put secure enabled_accessibility_services $list")
        }
        if (adb.run("settings get secure accessibility_enabled")?.trim() != "1") {
            adb.run("settings put secure accessibility_enabled 1")
        }
    }

    /**
     * Свой экран Wi-Fi лаунчера: Android 9 отдаёт приложению список сетей только с разрешением
     * на геолокацию и при включённой геолокации. Поставщик network лишь помечается разрешённым —
     * местоположение никто не запрашивает. Откат: restore.ps1.
     */
    private fun ensureWifiAccess() {
        if (adb.run("pm path ${Config.LAUNCHER_PKG}").isNullOrBlank()) return
        adb.run("pm grant ${Config.LAUNCHER_PKG} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        val providers = adb.run("settings get secure location_providers_allowed")?.trim().orEmpty()
        if (!providers.split(',').contains(Config.LOCATION_PROVIDER)) {
            Logf.w("wifi: allowing location provider '${Config.LOCATION_PROVIDER}' for scan results")
            adb.run("settings put secure location_providers_allowed +${Config.LOCATION_PROVIDER}")
        }
    }

    /**
     * Стоковые настройки XGIMI (com.android.newsettings): в них быстрое меню (всплывает под нашей
     * панелью) и FallbackHome — домашний экран на время загрузки. Без какого-нибудь такого экрана
     * Android не завершает загрузку: 2026-11-06 выключенные настройки дали чёрный экран после ребута.
     * Поэтому выключаем их, только если в лаунчере есть свой загрузочный экран (BootHomeActivity,
     * launcher 0.4.1+), иначе — включаем обратно.
     */
    private fun syncStockSettings() {
        val haveBootHome = adb.run("dumpsys package ${Config.LAUNCHER_PKG}").orEmpty().contains(Config.BOOT_HOME_CLASS)
        val enabled = !adb.run("pm list packages -e ${Config.STOCK_SETTINGS_PKG}").isNullOrBlank()
        if (haveBootHome && enabled) {
            Logf.w("boot: own boot home present — disabling ${Config.STOCK_SETTINGS_PKG} (stock quick menu)")
            adb.run("pm disable-user --user 0 ${Config.STOCK_SETTINGS_PKG}")
        } else if (!haveBootHome && !enabled) {
            Logf.w("boot: no own boot home — enabling ${Config.STOCK_SETTINGS_PKG} (FallbackHome is needed to boot)")
            adb.run("pm enable ${Config.STOCK_SETTINGS_PKG}")
        }
    }

    /**
     * Наш приёмник (os.z7.airplay) пришёл на смену официальной сборке. Две копии рекламировали бы
     * одно имя и делили порт 7000, поэтому старую выключаем (обратимо, restore.ps1 включает).
     */
    private fun retireLegacyReceiver() {
        if (adb.run("pm path ${Config.RECEIVER_PKG}").isNullOrBlank()) return
        if (adb.run("pm list packages -e ${Config.LEGACY_RECEIVER_PKG}").isNullOrBlank()) return
        Logf.w("cast: ${Config.RECEIVER_PKG} installed — disabling ${Config.LEGACY_RECEIVER_PKG}")
        adb.run("am force-stop ${Config.LEGACY_RECEIVER_PKG}")
        adb.run("pm disable-user --user 0 ${Config.LEGACY_RECEIVER_PKG}")
    }

    private var beaconProp: Boolean? = null
    private var beaconPropAt = 0L

    /**
     * Маяк включается свойством `persist.z7.p2p_beacon` (1/0) — проверить на проекторе без
     * пересборки; без свойства действует [Config.P2P_BEACON_ENABLED]. Читаем раз в минуту.
     */
    private fun beaconAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (beaconPropAt == 0L || now - beaconPropAt >= Config.BEACON_PROP_EVERY_MS) {
            beaconPropAt = now
            beaconProp = when (adb.run("getprop ${Config.BEACON_PROP}")?.trim()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
        return beaconProp ?: Config.P2P_BEACON_ENABLED
    }

    /**
     * К AirPlay кто-то подключён (установленное TCP-соединение на порт приёмника). В этот момент
     * listen маяка мешал бы синхронизации часов с iPhone — маяк молчит, даже если звука нет.
     */
    private fun airplayInUse(): Boolean {
        val port = "%04X".format(Config.RECEIVER_PORT)
        val conns = adb.run("cat /proc/net/tcp /proc/net/tcp6").orEmpty()
        return conns.lineSequence().any { line ->
            val cols = line.trim().split(WHITESPACE)
            cols.size > 3 && cols[1].endsWith(":$port") && cols[3] == TCP_ESTABLISHED
        }
    }

    private fun scheduleRevive() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_REVIVE),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + Config.REVIVE_INTERVAL_MS,
            Config.REVIVE_INTERVAL_MS,
            pi
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        unregisterReceiver(p2pReceiver)
        unregisterReceiver(launcherUpdated)
        adb.close()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Z7 OS Agent", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("XGIMI Projector OS")
            .setContentText("Фоновый агент активен")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "z7.AgentService"
        private const val CHANNEL_ID = "z7_agent"
        private const val NOTIF_ID = 1001
        private const val TCP_ESTABLISHED = "01"
        private val WHITESPACE = Regex("""\s+""")

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
