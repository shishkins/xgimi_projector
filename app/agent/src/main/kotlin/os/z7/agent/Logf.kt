package os.z7.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой файловый лог для отладки без logcat (на этом устройстве logcat shell-у недоступен).
 * Пишет в app-specific external dir: /sdcard/Android/data/os.z7.agent/files/agent.log
 * — читается `adb pull`, разрешений не требует.
 */
object Logf {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
            file = File(dir, "agent.log")
            w("=== agent start ===")
        } catch (e: Exception) {
            Log.w("z7.Logf", "init failed", e)
        }
    }

    @Synchronized
    fun w(msg: String) {
        Log.i("z7", msg)
        try {
            file?.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {
        }
    }
}
