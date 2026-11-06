package os.z7.launcher

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock

/**
 * Модуль Wi-Fi иногда не поднимается после перезагрузки: Wi-Fi вечно «включается», выключатель
 * не работает, помогает только отключение питания — тёплая перезагрузка USB-модуль не обесточивает
 * (2026-11-06). Если так дольше [STUCK_MS], наши экраны показывают подсказку; агент в это время
 * сохраняет журнал для разбора (WifiWatch).
 */
object WifiStuck {

    private const val STUCK_MS = 60_000L
    private var since = 0L

    /** Wi-Fi завис? Вызывать периодически: отсчёт идёт с первого замеченного «включается». */
    fun check(context: Context): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val state = wifi.wifiState
        if (state != WifiManager.WIFI_STATE_ENABLING && state != WifiManager.WIFI_STATE_UNKNOWN) {
            since = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (since == 0L) since = now
        return now - since >= STUCK_MS
    }
}
