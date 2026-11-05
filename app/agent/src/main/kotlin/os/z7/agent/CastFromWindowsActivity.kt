package os.z7.agent

import android.app.Activity
import android.os.Bundle

/**
 * Кнопка «Cast from Windows». Если установлен наш приёмник (os.z7.miracast) — готовим Wi-Fi и
 * открываем его (см. [OwnMiracast]); иначе — прежний режим со стоковым приёмником ([MiracastMode]).
 */
class CastFromWindowsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentService.start(this)
        val app = applicationContext
        if (OwnMiracast.installed(this)) {
            val language = intent.getStringExtra("z7.ui_language")
            Thread {
                val adb = AdbBridge(app.filesDir)
                try {
                    OwnMiracast.startSession(app, adb, language)
                } finally {
                    adb.close()
                }
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
