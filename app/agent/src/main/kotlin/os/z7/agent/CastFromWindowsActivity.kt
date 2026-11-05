package os.z7.agent

import android.app.Activity
import android.os.Bundle

/**
 * Кнопка «Cast from Windows» в списке приложений: включает режим Miracast
 * (проектор становится виден в Win+K) и сразу закрывается — дальше сессию ведёт агент.
 * Временная точка входа до появления плитки на главном экране нашей системы.
 */
class CastFromWindowsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentService.start(this)
        if (OwnMiracast.installed(this)) {
            OwnMiracast.open(this)
            finish()
            return
        }
        val app = applicationContext
        Thread {
            val adb = AdbBridge(app.filesDir)
            try {
                MiracastMode(app, adb).start()
            } finally {
                adb.close()
            }
        }.start()
        finish()
    }
}
