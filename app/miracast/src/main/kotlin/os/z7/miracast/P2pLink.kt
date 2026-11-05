package os.z7.miracast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Wi-Fi Direct для приёмника: держит проектор видимым в Win+K (поиск устройств = режим listen)
 * и сообщает, когда компьютер подключился и по какому адресу его искать.
 *
 * Атрибуты WFD («я — дисплей», порт) выставляет системный стоковый приёмник (setWFDInfo требует
 * CONFIGURE_WIFI_DISPLAY); они живут в P2P-службе до перезапуска Wi-Fi. Об этом заботится агент.
 */
@SuppressLint("MissingPermission")
internal class P2pLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** Компьютер в группе: [host] — его адрес, [port] — RTSP-порт источника. */
        fun onSourceConnected(name: String, host: String, port: Int)
        fun onSourceLost()
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var connected = false
    private var searching = false

    private val rediscover = object : Runnable {
        override fun run() {
            if (searching && !connected) discover()
            main.postDelayed(this, REDISCOVER_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
            val net = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
            val up = info?.groupFormed == true && net?.isConnected == true
            L.i("p2p: connection changed formed=${info?.groupFormed} connected=${net?.isConnected} owner=${info?.isGroupOwner}")
            if (up && !connected) {
                connected = true
                onGroup(info!!)
            } else if (!up && connected) {
                connected = false
                listener.onSourceLost()
            }
        }
    }

    fun start() {
        channel = manager.initialize(context, Looper.getMainLooper()) { L.w("p2p: channel lost") }
        context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        searching = true
        // старая группа (от прошлого сеанса или стока) мешает новому подключению
        manager.removeGroup(channel, null)
        main.postDelayed({ discover() }, 500)
        main.postDelayed(rediscover, REDISCOVER_MS)
    }

    fun stop() {
        searching = false
        main.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        channel?.let { ch ->
            manager.stopPeerDiscovery(ch, null)
            manager.removeGroup(ch, null)
        }
        channel = null
    }

    /** Во время сеанса поиск не нужен: он уводит радио с канала группы. */
    fun pauseDiscovery() {
        channel?.let { manager.stopPeerDiscovery(it, null) }
    }

    private fun discover() {
        val ch = channel ?: return
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = L.i("p2p: discoverable")
            override fun onFailure(reason: Int) = L.w("p2p: discoverPeers failed $reason")
        })
    }

    private fun onGroup(info: WifiP2pInfo) {
        val ch = channel ?: return
        manager.stopPeerDiscovery(ch, null)
        manager.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            val peer: WifiP2pDevice? = if (info.isGroupOwner) group?.clientList?.firstOrNull() else group?.owner
            val name = peer?.deviceName?.takeIf { it.isNotBlank() } ?: "computer"
            val port = peer?.let { controlPort(it) } ?: Wfd.DEFAULT_CONTROL_PORT
            L.i("p2p: group up, peer='$name' port=$port owner=${info.isGroupOwner} iface=${group?.`interface`}")
            if (!info.isGroupOwner) {
                listener.onSourceConnected(name, info.groupOwnerAddress.hostAddress, port)
            } else {
                findClientAddress(group?.`interface`) { host ->
                    if (host != null) listener.onSourceConnected(name, host, port)
                    else { L.w("p2p: client address not found"); listener.onSourceLost() }
                }
            }
        }
    }

    /**
     * Мы — владелец группы: адрес компьютера выдал наш DHCP. Ищем его в ARP-таблице интерфейса
     * группы (компьютер сразу после DHCP спрашивает наш MAC, и ядро запоминает его адрес).
     */
    private fun findClientAddress(iface: String?, done: (String?) -> Unit) {
        Thread {
            var host: String? = null
            for (i in 0 until 40) {
                host = readArp(iface)
                if (host != null) break
                Thread.sleep(250)
            }
            main.post { done(host) }
        }.start()
    }

    private fun readArp(iface: String?): String? = try {
        File("/proc/net/arp").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { c ->
                c.size >= 6 && c[0].startsWith("192.168.49.") && c[2] != "0x0" &&
                    (iface == null || c[5] == iface)
            }?.get(0)
    } catch (e: Exception) {
        L.w("p2p: arp read failed: ${e.message}"); null
    }

    /** RTSP-порт источника из его WFD-атрибутов (скрытое поле WifiP2pDevice.wfdInfo). */
    private fun controlPort(d: WifiP2pDevice): Int? = try {
        val wfd = WifiP2pDevice::class.java.getField("wfdInfo").get(d) ?: return null
        (wfd.javaClass.getMethod("getControlPort").invoke(wfd) as Int).takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Один поиск держит listen ~2 мин; обновляем чаще, чтобы проектор не пропадал из Win+K. */
        const val REDISCOVER_MS = 60_000L
    }
}
