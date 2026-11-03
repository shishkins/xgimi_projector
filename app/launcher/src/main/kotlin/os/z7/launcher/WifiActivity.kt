package os.z7.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Wi-Fi: включение, список сетей, подключение с паролем на своей клавиатуре. */
class WifiActivity : Activity() {

    private val wifi by lazy { Wifi.manager(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: Ui.Row
    private lateinit var sheet: Sheet

    /** Сеть, к которой подключаемся сейчас, и id, если её создали мы (при неверном пароле — удалить). */
    private var connectingSsid: String? = null
    private var createdId: Int? = null

    private val rescan = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION") wifi.startScan()
            main.postDelayed(this, SCAN_EVERY_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SUPPLICANT_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING
            ) onWrongPassword()
            if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION && Wifi.isConnected(context)) {
                if (connectingSsid != null && connectingSsid == Wifi.currentSsid(context)) {
                    connectingSsid = null
                    createdId = null
                }
            }
            if (!sheet.isShown) render()
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@WifiActivity, 72), Ui.dp(this@WifiActivity, 56), Ui.dp(this@WifiActivity, 72), 0)
        }
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(Ui.icon(this, R.drawable.ic_wifi, 56))
        side.addView(Ui.text(this, getString(R.string.wifi), 40f).apply { setPadding(0, Ui.dp(context, 16), 0, 0) })
        status = Ui.text(this, "", 18f, secondary = true).apply { maxLines = 2 }
        side.addView(status, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Ui.dp(this@WifiActivity, 8) })
        toggle = Ui.Row(this, null, getString(R.string.wifi))
        @Suppress("DEPRECATION")
        toggle.setOnClickListener { wifi.setWifiEnabled(!wifi.isWifiEnabled); render() }
        side.addView(toggle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Ui.dp(this@WifiActivity, 32) })
        root.addView(side, LinearLayout.LayoutParams(Ui.dp(this, SIDE_DP), MATCH).apply { marginEnd = Ui.dp(this@WifiActivity, 56) })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 48))
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        setContentView(root)
        sheet = Sheet(this)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        })
        main.post(rescan)
        render()
        toggle.requestFocus()
    }

    override fun onPause() {
        main.removeCallbacks(rescan)
        unregisterReceiver(receiver)
        super.onPause()
    }

    private fun render() {
        val on = wifi.isWifiEnabled
        toggle.setValue(getString(if (on) R.string.on else R.string.off))
        status.text = when {
            connectingSsid != null -> getString(R.string.wifi_connecting, connectingSsid)
            else -> Wifi.status(this)
        }

        val focusedSsid = (currentFocus?.tag as? String)
        list.removeAllViews()
        if (!on) {
            list.addView(Ui.text(this, getString(R.string.wifi_turned_off), 18f, secondary = true))
            return
        }
        val networks = Wifi.networks(this)
        if (networks.isEmpty()) {
            list.addView(Ui.text(this, getString(R.string.wifi_searching), 18f, secondary = true))
            return
        }
        for (n in networks) {
            val row = networkRow(n)
            list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = Ui.dp(this@WifiActivity, 8) })
            if (n.ssid == focusedSsid) row.requestFocus()
        }
    }

    private fun networkRow(n: Wifi.Network): View {
        val value = when {
            n.ssid == connectingSsid -> getString(R.string.wifi_connecting_short)
            n.connected -> getString(R.string.wifi_connected)
            n.savedId != null -> getString(R.string.wifi_saved)
            n.secured -> getString(R.string.wifi_secured)
            else -> getString(R.string.wifi_open)
        }
        return LinearLayout(this).apply {
            tag = n.ssid
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(context, 64)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 10), Ui.dp(context, 20), Ui.dp(context, 10))
            addView(SignalView(context, n.level), LinearLayout.LayoutParams(Ui.dp(context, 26), Ui.dp(context, 22)).apply {
                marginEnd = Ui.dp(context, 18)
            })
            addView(Ui.text(context, n.ssid, 19f), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(Ui.text(context, value, 16f, secondary = !n.connected).apply {
                if (n.connected) setTextColor(context.getColor(R.color.accent))
            })
            Ui.focusable(this, Ui.ROW_FOCUS_SCALE)
            setOnClickListener { onNetwork(n) }
        }
    }

    private fun onNetwork(n: Wifi.Network) {
        when {
            n.connected -> sheet.confirm(n.ssid, Wifi.ipAddress(this)?.let { getString(R.string.wifi_ip, it) }, listOf(
                getString(R.string.wifi_forget) to { forget(n) },
                getString(R.string.cancel) to {},
            ))
            n.savedId != null -> connect(n.ssid) { Wifi.connectSaved(this, n.savedId) }
            !n.secured -> connect(n.ssid) { Wifi.connectNew(this, n, null).also { createdId = it } != -1 }
            else -> askPassword(n)
        }
    }

    private fun connect(ssid: String, action: () -> Boolean) {
        connectingSsid = ssid
        if (!action()) {
            connectingSsid = null
            Toast.makeText(this, R.string.wifi_connect_failed, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun forget(n: Wifi.Network) {
        val id = n.savedId ?: return
        if (!Wifi.forget(this, id)) Toast.makeText(this, R.string.wifi_forget_failed, Toast.LENGTH_LONG).show()
        render()
    }

    private fun onWrongPassword() {
        val ssid = connectingSsid ?: return
        connectingSsid = null
        createdId?.let { Wifi.forget(this, it) }
        createdId = null
        Toast.makeText(this, getString(R.string.wifi_wrong_password, ssid), Toast.LENGTH_LONG).show()
    }

    private fun askPassword(n: Wifi.Network) {
        val password = StringBuilder()
        var visible = false
        val field = TextView(this).apply {
            textSize = 24f
            setTextColor(getColor(R.color.text_primary))
            background = getDrawable(R.drawable.field_bg)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 12))
            minWidth = Ui.dp(context, FIELD_WIDTH_DP)
        }
        fun update() {
            field.text = when {
                password.isEmpty() -> ""
                visible -> password
                else -> "•".repeat(password.length)
            }
            field.hint = getString(R.string.wifi_password_hint)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(Ui.text(this, getString(R.string.wifi_password_for, n.ssid), 24f))
        val fieldRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fieldRow.addView(field)
        val show = Ui.Row(this, null, getString(R.string.wifi_show_password))
        show.setOnClickListener {
            visible = !visible
            show.setValue(getString(if (visible) R.string.on else R.string.off))
            update()
        }
        show.setValue(getString(R.string.off))
        fieldRow.addView(show, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Ui.dp(this@WifiActivity, 16) })
        column.addView(fieldRow, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = Ui.dp(this@WifiActivity, 20)
            bottomMargin = Ui.dp(this@WifiActivity, 20)
        })
        val keyboard = Keyboard(this,
            onText = { password.append(it); update() },
            onDelete = { if (password.isNotEmpty()) password.setLength(password.length - 1); update() },
            onDone = {
                if (password.length < if (n.wep) MIN_WEP_KEY else MIN_PASSWORD) {
                    Toast.makeText(this, R.string.wifi_password_short, Toast.LENGTH_SHORT).show()
                } else {
                    sheet.dismiss()
                    connect(n.ssid) { Wifi.connectNew(this, n, password.toString()).also { createdId = it } != -1 }
                }
            },
            doneLabel = getString(R.string.wifi_connect),
        )
        column.addView(keyboard)
        update()
        sheet.show(column)
        keyboard.focusFirst()
    }

    /** Сила сигнала — четыре столбика. */
    private class SignalView(context: Context, private val level: Int) : View(context) {
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary) }
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.bar_track) }

        override fun onDraw(canvas: Canvas) {
            val bars = Wifi.SIGNAL_LEVELS
            val gap = width * 0.12f
            val w = (width - gap * (bars - 1)) / bars
            for (i in 0 until bars) {
                val x = i * (w + gap)
                val top = height * (1f - (i + 1f) / bars)
                canvas.drawRoundRect(x, top, x + w, height.toFloat(), w / 3, w / 3, if (i <= level) on else off)
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SIDE_DP = 360
        const val FIELD_WIDTH_DP = 420
        const val SCAN_EVERY_MS = 15_000L
        const val MIN_PASSWORD = 8
        const val MIN_WEP_KEY = 5
    }
}
