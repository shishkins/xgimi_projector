package os.z7.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Wi-Fi обычным API приложения. Список сетей Android 9 отдаёт только при разрешении на
 * геолокацию и включённой геолокации — оба выставляет агент (см. AgentService.ensureWifiAccess).
 * Ограничение Android 9: сеть, сохранённую другим приложением (стоковыми настройками),
 * можно включить, но нельзя изменить или забыть.
 */
object Wifi {

    data class Network(
        val ssid: String,
        val level: Int,
        val secured: Boolean,
        val wep: Boolean,
        val savedId: Int?,
        val connected: Boolean,
    )

    fun manager(context: Context): WifiManager =
        context.applicationContext.getSystemService(WifiManager::class.java)

    /** Строка состояния для плиток: имя сети, «Не подключено» или «Выкл.». */
    fun status(context: Context): String {
        val wifi = manager(context)
        if (!wifi.isWifiEnabled) return context.getString(R.string.wifi_off)
        if (!isConnected(context)) return context.getString(R.string.wifi_not_connected)
        return currentSsid(context) ?: context.getString(R.string.wifi_connected)
    }

    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun currentSsid(context: Context): String? {
        val ssid = manager(context).connectionInfo?.ssid ?: return null
        return unquote(ssid).takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    fun ipAddress(context: Context): String? {
        val ip = manager(context).connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    /** Видимые сети, по одной на имя: подключённая, затем сохранённые, затем по силе сигнала. */
    fun networks(context: Context): List<Network> {
        val wifi = manager(context)
        val current = if (isConnected(context)) currentSsid(context) else null
        val saved = try {
            wifi.configuredNetworks.orEmpty().associate { unquote(it.SSID) to it.networkId }
        } catch (e: SecurityException) {
            emptyMap()
        }
        val results = try { wifi.scanResults.orEmpty() } catch (e: SecurityException) { emptyList() }
        return results.filter { it.SSID.isNotEmpty() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level }!!
                Network(
                    ssid = ssid,
                    level = WifiManager.calculateSignalLevel(best.level, SIGNAL_LEVELS),
                    secured = isSecured(best),
                    wep = best.capabilities.contains("WEP"),
                    savedId = saved[ssid],
                    connected = ssid == current,
                )
            }
            .sortedWith(compareByDescending<Network> { it.connected }.thenByDescending { it.savedId != null }.thenByDescending { it.level })
    }

    /** Подключиться к сохранённой сети. */
    fun connectSaved(context: Context, networkId: Int): Boolean {
        val wifi = manager(context)
        return wifi.enableNetwork(networkId, true) && wifi.reconnect()
    }

    /** Новая сеть: сохранить (пароль null — открытая) и подключиться. -1 — система не приняла. */
    fun connectNew(context: Context, network: Network, password: String?): Int {
        val config = WifiConfiguration()
        config.SSID = quote(network.ssid)
        if (password == null) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        } else if (network.wep) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            config.wepKeys[0] = if (password.matches(HEX_KEY)) password else quote(password)
            config.wepTxKeyIndex = 0
        } else {
            config.preSharedKey = quote(password)
        }
        val wifi = manager(context)
        val id = wifi.addNetwork(config)
        if (id != -1) connectSaved(context, id)
        return id
    }

    /** Забыть сеть; false — её сохранило другое приложение, Android 9 не даёт её удалить. */
    fun forget(context: Context, networkId: Int): Boolean {
        val wifi = manager(context)
        return wifi.removeNetwork(networkId) && wifi.saveConfiguration()
    }

    private fun isSecured(result: ScanResult) =
        result.capabilities.contains("WEP") || result.capabilities.contains("PSK") || result.capabilities.contains("EAP")

    private fun quote(s: String) = "\"$s\""

    private fun unquote(s: String) = s.removeSurrounding("\"")

    const val SIGNAL_LEVELS = 4
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private val HEX_KEY = Regex("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}")
}
