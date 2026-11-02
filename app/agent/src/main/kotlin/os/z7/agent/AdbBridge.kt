package os.z7.agent

import android.os.SystemClock
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Локальный ADB-мост: агент подключается к adbd проектора на 127.0.0.1:5555
 * и выполняет команды с правами shell (pm, am, settings, monkey...).
 *
 * dadb требует ADB-ключ для рукопожатия. По умолчанию он ищет ~/.android/adbkey,
 * которого в контексте приложения нет (FileNotFoundException) — поэтому генерируем
 * свой ключ в каталоге приложения и передаём его явно. Так как на Z7X ro.adb.secure=0,
 * adbd принимает подключение без подтверждения.
 *
 * Каждая команда выполняется с таймаутом: на этом устройстве shell через dadb иногда
 * зависает навсегда (так встал агент 0.1.0 — цикл замер на первой команде). По таймауту
 * соединение закрывается (это разблокирует зависший поток) и открывается заново.
 *
 * Приём тот же, что у Shizuku/LADB: обычное приложение получает права shell
 * без root и без системной подписи.
 */
class AdbBridge(keyDir: File) {

    private val privKey = File(keyDir, "adbkey")
    private val pubKey = File(keyDir, "adbkey.pub")

    @Volatile
    private var dadb: Dadb? = null

    /** Пул, а не один поток: если зависший вызов не отпустит поток, следующие команды не встанут за ним. */
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "z7-adb").apply { isDaemon = true }
    }

    private fun keyPair(): AdbKeyPair {
        if (!privKey.exists() || !pubKey.exists()) {
            Logf.w("dadb: generating adb keypair in ${privKey.parent}")
            AdbKeyPair.generate(privKey, pubKey)
        }
        return AdbKeyPair.read(privKey, pubKey)
    }

    @Synchronized
    private fun connection(): Dadb {
        dadb?.let { return it }
        Logf.w("dadb: connecting to ${Config.ADB_HOST}:${Config.ADB_PORT}")
        val d = Dadb.create(Config.ADB_HOST, Config.ADB_PORT, keyPair())
        Logf.w("dadb: connected")
        dadb = d
        return d
    }

    /** Без блокировки: должен срабатывать, даже если другой поток завис внутри connection(). */
    fun close() {
        val d = dadb
        dadb = null
        try {
            d?.close()
        } catch (_: Exception) {
        }
    }

    /** Выполнить команду в shell. Возвращает stdout (или null при ошибке связи/таймауте). */
    fun run(command: String, timeoutMs: Long = Config.ADB_CMD_TIMEOUT_MS): String? {
        repeat(2) { attempt ->
            val started = SystemClock.elapsedRealtime()
            val task = executor.submit(Callable { connection().shell(command).output })
            try {
                val out = task.get(timeoutMs, TimeUnit.MILLISECONDS)
                val took = SystemClock.elapsedRealtime() - started
                if (took > SLOW_MS) Logf.w("dadb: slow '$command' ${took}ms")
                return out
            } catch (e: TimeoutException) {
                Logf.w("dadb: '$command' timed out after ${timeoutMs}ms (attempt $attempt)")
                task.cancel(true)
                close() // переподключимся на следующей попытке
            } catch (e: ExecutionException) {
                val c = e.cause ?: e
                Logf.w("dadb: shell '$command' failed (attempt $attempt): ${c.javaClass.simpleName}: ${c.message}")
                close()
            }
        }
        return null
    }

    private companion object {
        const val SLOW_MS = 3_000L
    }
}
