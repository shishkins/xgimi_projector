package os.z7.agent

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.os.SystemClock

/**
 * «Маяк» Miracast: держит проектор видимым в Win+K, не отключая домашнюю Wi-Fi.
 *
 * Windows находит приёмник Miracast по ответам на P2P probe-запросы, а проектор отвечает
 * на них только в режиме listen. Стоковый экран Miracast включает listen, но при этом
 * сам отключает все сохранённые сети (WifiSwitchManager.disable в WifiDisplayActivity.onResume).
 * Ограничение не в железе: у MT7961 отдельные интерфейсы wlan0 и p2p0.
 *
 * Поэтому агент сам периодически вызывает discoverPeers (поиск = чередование scan/listen,
 * длится ~2 мин) и тем держит P2P в эфире. WFD-информация (приёмник, порт 7236) уже
 * выставлена системой. Когда Windows подключается, группа P2P формируется сама,
 * а [MiracastMode.onIncoming] открывает стоковый приёмник для RTSP-сессии.
 */
class P2pBeacon(private val context: Context) {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var lastDiscover = 0L
    private var running = false
    private var lastResult = ""

    /**
     * Шаг цикла агента. [enabled] = false во время Miracast-сессии (P2P ведёт стоковый приёмник)
     * и пока что-то играет: listen уводит радио с канала домашней сети и ломает стриминг.
     */
    fun tick(enabled: Boolean) {
        val mgr = manager ?: return
        if (!enabled) {
            if (running) {
                channel?.let { mgr.stopPeerDiscovery(it, null) }
                running = false
                lastDiscover = 0L
                Logf.w("p2p beacon: paused")
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (running && now - lastDiscover < Config.P2P_DISCOVER_EVERY_MS) return
        val ch = channel ?: mgr.initialize(context, Looper.getMainLooper(), null).also { channel = it }
        running = true
        lastDiscover = now
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = report("on")

            override fun onFailure(reason: Int) {
                report("failed reason=$reason")
                lastDiscover = 0L // повторим на следующем шаге
            }
        })
    }

    /** Сбросить текущую P2P-группу, чтобы Windows переподключилась к уже открытому приёмнику. */
    fun dropGroup() {
        val mgr = manager ?: return
        val ch = channel ?: mgr.initialize(context, Looper.getMainLooper(), null).also { channel = it }
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Logf.w("p2p: first link dropped, waiting for Windows to reconnect")

            override fun onFailure(reason: Int) = Logf.w("p2p: removeGroup failed reason=$reason")
        })
    }

    /** Логируем только смену состояния, иначе лог забьётся каждые полторы минуты. */
    private fun report(result: String) {
        if (result != lastResult) Logf.w("p2p beacon: $result")
        lastResult = result
    }
}
