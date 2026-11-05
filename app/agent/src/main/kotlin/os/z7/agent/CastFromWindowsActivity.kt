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
        val app = applicationContext
        if (OwnMiracast.installed(this)) {
            // свой приёмник; сначала — атрибуты WFD, если их сбросила перезагрузка
            Thread {
                val adb = AdbBridge(app.filesDir)
                try {
                    OwnMiracast.ensureWfd(adb)
                } catch (e: Exception) {
                    Logf.w("miracast: WFD priming failed: ${e.message}")
                } finally {
                    adb.close()
                }
                OwnMiracast.open(app)
            }.start()
            finish()
            return
        }
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
