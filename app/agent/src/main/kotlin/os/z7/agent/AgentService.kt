package os.z7.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
 * v0.1 — держит беспроводной каст живым через локальный ADB-мост.
 * Дальше сюда добавятся: обновлятор, перехват HOME, режим Miracast, профили пакетов.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adb by lazy { AdbBridge(filesDir) }
    private val castKeeper by lazy { CastKeeper(adb) }
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Logf.init(this)
        startForeground(NOTIF_ID, buildNotification())
        Logf.w("foreground started")
        loopJob = scope.launch {
            castKeeper.applyReceiverSettings() // в IO, не в главном потоке
            while (isActive) {
                castKeeper.ensureCast()
                delay(Config.WATCH_INTERVAL_MS)
            }
        }
        Log.i(TAG, "agent started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
