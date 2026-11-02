package os.z7.agent

import android.util.Log
import dadb.Dadb

/**
 * Локальный ADB-мост: агент подключается к adbd проектора на 127.0.0.1:5555
 * и выполняет команды с правами shell (pm, am, settings, monkey...).
 * Работает, пока ro.adb.secure=0 (на Z7X это так) — без обмена ключами.
 *
 * Это тот же приём, что у Shizuku/LADB: обычное приложение получает права shell,
 * не будучи root и без системной подписи.
 */
class AdbBridge {
    private var dadb: Dadb? = null

    @Synchronized
    private fun connection(): Dadb {
        dadb?.let { return it }
        Logf.w("dadb: connecting to ${Config.ADB_HOST}:${Config.ADB_PORT}")
        val d = Dadb.create(Config.ADB_HOST, Config.ADB_PORT)
        Logf.w("dadb: connected")
        dadb = d
        return d
    }

    @Synchronized
    fun close() {
        try {
            dadb?.close()
        } catch (_: Exception) {
        }
        dadb = null
    }

    /** Выполнить команду в shell. Возвращает stdout (или null при ошибке связи). */
    fun run(command: String): String? {
        repeat(2) { attempt ->
            try {
                val resp = connection().shell(command)
                return resp.output
            } catch (e: Exception) {
                Logf.w("dadb: shell '$command' failed (attempt $attempt): ${e.javaClass.simpleName}: ${e.message}")
                close() // переподключимся на следующей попытке
            }
        }
        return null
    }

    companion object {
        private const val TAG = "z7.AdbBridge"
    }
}
