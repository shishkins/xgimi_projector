package os.z7.agent

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Чёрный ящик» Wi-Fi. Модуль Wi-Fi (USB 0e8d:7663, драйвер wlan_mt7663_usb) иногда не поднимается
 * после перезагрузки: Wi-Fi вечно «включается», выключатель не работает, лечится только отключением
 * питания (тёплая перезагрузка не обесточивает USB). Причина не найдена: 2026-11-06 так вышло
 * после сеанса Miracast, но повторить тем же сеансом и настоящей трансляцией не удалось.
 *
 * 1. Через [Config.WIFI_STUCK_CHECK_MS] после загрузки — один раз: если Wi-Fi завис, сохраняем
 *    журнал ядра (буфер держит ~4,5 мин — загрузка драйвера ещё в нём), состояние Wi-Fi и USB
 *    в файл wifi-stuck-*.txt рядом с agent.log.
 * 2. Перед выключением/перезагрузкой — строка в журнал: состояние Wi-Fi и есть ли группа P2P.
 */
object WifiWatch {

    @Volatile private var checked = false

    /** Шаг сторожа агента. */
    fun tick(context: Context, adb: AdbBridge) {
        val uptime = SystemClock.elapsedRealtime()
        if (checked || uptime < Config.WIFI_STUCK_CHECK_MS) return
        checked = true
        val state = wifiState(context)
        if (state != WifiManager.WIFI_STATE_ENABLING && state != WifiManager.WIFI_STATE_UNKNOWN) {
            if (uptime < Config.WIFI_BOOT_WINDOW_MS) Logf.w("wifi after boot: ok (${stateName(state)})")
            return
        }
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, "wifi-stuck-${SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())}.txt")
        // dumpsys при зависшем Wi-Fi может сам зависнуть — каждому свой timeout
        adb.run("{ echo \"== \$(date) up \$(cut -d' ' -f1 /proc/uptime)\"; " +
            "echo '== usb'; grep -H . /sys/bus/usb/devices/*/idVendor /sys/bus/usb/devices/*/idProduct; " +
            "echo '== net'; ls /sys/class/net; ip -o link; " +
            "echo '== wifi'; timeout 8 dumpsys wifi | head -80; " +
            "echo '== p2p'; timeout 5 dumpsys wifip2p | head -40; " +
            "echo '== props'; getprop | grep -i -E 'wlan|wifi|wpa'; " +
            "echo '== dmesg'; dmesg | grep -v 'avc:'; } > ${file.path} 2>&1", timeoutMs = 30_000L)
        Logf.w("wifi STUCK after boot (${stateName(state)}) — diagnostics saved to ${file.name}")
    }

    /** Перед выключением или перезагрузкой. */
    fun onShutdown(context: Context, adb: AdbBridge) {
        val group = adb.run("timeout 3 dumpsys wifip2p | grep '^mGroup '", timeoutMs = 4_000L)
            ?.trim()?.take(60).orEmpty()
        Logf.w("shutdown: wifi=${stateName(wifiState(context))} p2p=[${group.ifEmpty { "?" }}]")
    }

    private fun wifiState(context: Context): Int =
        context.applicationContext.getSystemService(WifiManager::class.java)?.wifiState ?: WifiManager.WIFI_STATE_UNKNOWN

    private fun stateName(state: Int) = when (state) {
        WifiManager.WIFI_STATE_DISABLING -> "disabling"
        WifiManager.WIFI_STATE_DISABLED -> "disabled"
        WifiManager.WIFI_STATE_ENABLING -> "enabling"
        WifiManager.WIFI_STATE_ENABLED -> "enabled"
        else -> "unknown"
    }
}
