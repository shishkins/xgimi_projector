package os.z7.launcher

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал службы кнопок в файл (logcat на этой прошивке почти пуст):
 * /sdcard/Android/data/os.z7.launcher/files/keys.log, не больше ~200 КБ.
 */
object KeyLog {
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = context.getExternalFilesDir(null)?.let { File(it, "keys.log") }
    }

    @Synchronized
    fun w(message: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "keys.old.log"))
            f.appendText("${time.format(Date())} [${SystemClock.uptimeMillis()}] $message\n")
        } catch (_: Exception) {
        }
    }

    private const val MAX_BYTES = 200_000L
}
