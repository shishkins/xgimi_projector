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
import android.net.wifi.WifiManager
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
    private val castKeeper by lazy { CastKeeper(adb) }
    private val miracast by lazy { MiracastMode(this, adb) }
    private val beacon by lazy { P2pBeacon(this) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var loopJob: Job? = null

    /**
     * Держим Wi-Fi без энергосбережения. В режиме сна чип копит пакеты и отвечает пачками
     * раз в ~200 мс (пинг проектора 140–260 мс вместо 2–5): отсюда лаги Miracast
     * и «догоняющий» звук AirPlay. Проектор всегда от сети, экономить нечего.
     */
    private val wifiLock by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "z7:no-powersave")
            .apply { setReferenceCounted(false) }
    }

    /** Windows подключилась по Win+K: P2P-группа сформирована — сразу открываем приёмник. */
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
        wifiLock.acquire()
        Logf.w("wifi: power save off (high-perf lock)")
        // Настройки приёмника (имя, режим передачи видео) задаются один раз вручную
        // и сохраняются в самом приёмнике — агент их НЕ переустанавливает:
        // команды `am broadcast` к SettingsReceiver через dadb зависают.
        // Агент делает только критичное — watchdog каста (pidof/force-stop/monkey работают).
        registerReceiver(p2pReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        loopJob = scope.launch {
            ensureRevivable()
            ensureP2pPermission()
            var lastBeat = 0L
            while (isActive) {
                val miracastActive = miracast.tick()
                // Маяк уводит радио с канала домашней сети (пинг 3 → 130–260 мс),
                // поэтому он работает, только пока на проекторе ничего не играет.
                beacon.tick(enabled = !miracastActive && !audio.isMusicActive)
                castKeeper.ensureCast(miracastActive)
                val now = SystemClock.elapsedRealtime()
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
        if (wifiLock.isHeld) wifiLock.release()
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
