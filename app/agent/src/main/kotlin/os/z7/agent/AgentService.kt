package os.z7.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private var loopJob: Job? = null

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
        loopJob = scope.launch {
            ensureRevivable()
            var lastBeat = 0L
            while (isActive) {
                val miracastActive = miracast.tick()
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
