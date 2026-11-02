package os.z7.agent

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Локальный ADB-мост: агент подключается к adbd проектора на 127.0.0.1:5555
 * и выполняет команды с правами shell (pm, am, settings, monkey...).
 *
 * dadb требует ADB-ключ для рукопожатия. По умолчанию он ищет ~/.android/adbkey,
 * которого в контексте приложения нет (FileNotFoundException) — поэтому генерируем
 * свой ключ в каталоге приложения и передаём его явно. Так как на Z7X ro.adb.secure=0,
 * adbd принимает подключение без подтверждения.
 *
 * Приём тот же, что у Shizuku/LADB: обычное приложение получает права shell
 * без root и без системной подписи.
 */
class AdbBridge(keyDir: File) {

    private val privKey = File(keyDir, "adbkey")
    private val pubKey = File(keyDir, "adbkey.pub")
    private var dadb: Dadb? = null

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
}
