package os.z7.launcher

import android.app.Application
import android.content.Intent
import android.util.Log

/**
 * Главный экран — единственное, что Android обязан запустить после загрузки:
 * прошивка XGIMI не даёт сторонним приложениям стартовать по BOOT_COMPLETED,
 * но HOME запускается всегда. Поэтому лаунчер при старте поднимает агента
 * (а тот — AirPlay и остальной фон).
 */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startAgent()
    }

    fun startAgent() {
        try {
            startForegroundService(Intent().setClassName(AGENT_PKG, AGENT_SERVICE))
        } catch (e: Exception) {
            // агента нет или он старой версии без экспортированного сервиса — экран работает и без него
            Log.w(TAG, "agent start failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "z7.Launcher"
        const val AGENT_PKG = "os.z7.agent"
        const val AGENT_SERVICE = "os.z7.agent.AgentService"
    }
}
