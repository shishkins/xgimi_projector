package os.z7.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Минимальный экран статуса агента (временный, до появления настроек/лаунчера).
 * Показывает состояние каста и ADB-моста.
 */
class StatusActivity : Activity() {

    private lateinit var text: TextView
    private val adb by lazy { AdbBridge(filesDir) }
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        TextView(this).apply {
            text = "XGIMI Projector OS"
            setTextColor(Color.WHITE)
            textSize = 28f
            root.addView(this)
        }
        text = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        root.addView(text)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Thread {
            val recv = adb.run("pidof ${Config.RECEIVER_PKG}")?.trim().orEmpty()
            val ip = adb.run("ip addr show wlan0 | grep 'inet '")?.trim().orEmpty()
            val status = buildString {
                appendLine("Агент: активен")
                appendLine("AirPlay-приёмник: " + if (recv.isNotEmpty()) "работает (pid $recv)" else "не запущен")
                appendLine("ADB-мост: " + if (recv.isNotEmpty() || ip.isNotEmpty()) "ок" else "нет связи")
            }
            ui.post { text.text = status }
        }.start()
    }
}
