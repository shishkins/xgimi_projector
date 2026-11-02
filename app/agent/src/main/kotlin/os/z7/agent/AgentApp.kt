package os.z7.agent

import android.app.Application

/** Точка входа приложения-агента. Поднимает фоновый сервис. */
class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentService.start(this)
    }
}
