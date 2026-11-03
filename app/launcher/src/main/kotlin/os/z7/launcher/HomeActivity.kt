package os.z7.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран (HOME): часы, избранное, ряд управления и все приложения.
 * Стиль — Apple TV: тёмный фон, крупные карточки, без лент контента и рекламы.
 */
class HomeActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var favorites: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var apps: GridLayout
    private lateinit var date: TextView
    private lateinit var wifi: ImageView

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    /** Значок Wi-Fi в шапке следует за сетью по умолчанию. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            showWifi(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))

        override fun onLost(network: Network) = showWifi(false)
    }

    /** Язык интерфейса: русский, если он системный, иначе английский (системный тут китайский). */
    private val uiLocale: Locale
        get() = if (resources.configuration.locales[0].language == "ru") Locale("ru") else Locale.ENGLISH

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateDate()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        scroll = findViewById(R.id.scroll)
        favorites = findViewById(R.id.favorites)
        controls = findViewById(R.id.controls)
        apps = findViewById(R.id.apps)
        date = findViewById(R.id.date)
        wifi = findViewById(R.id.status_wifi)
        // до нашего модуля настроек оба значка открывают стоковые настройки XGIMI
        wifi.setOnClickListener { launch(Intent().setComponent(STOCK_SETTINGS)) }
        findViewById<View>(R.id.status_settings).setOnClickListener { launch(Intent().setComponent(STOCK_SETTINGS)) }
        connectivity.registerDefaultNetworkCallback(networkCallback)

        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        updateDate()
        // агента мог убить режим ожидания XGIMI — главный экран поднимает его каждый раз
        (application as LauncherApp).startAgent()
    }

    /** HOME, когда мы уже на главном экране: вернуться наверх, к избранному. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scroll.smoothScrollTo(0, 0)
        focusFirst()
    }

    override fun onBackPressed() {
        // главный экран — некуда возвращаться
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    private fun render() {
        val all = AppCatalog.load(this)

        favorites.removeAllViews()
        for (app in AppCatalog.favorites(all)) {
            favorites.addView(Tiles.app(this, app, Tiles.Size.BIG) { launch(app.launch) }, gap(28, 0))
        }

        controls.removeAllViews()
        for (action in controlActions()) {
            controls.addView(action, gap(24, 0))
        }

        apps.removeAllViews()
        for (app in all) {
            apps.addView(Tiles.app(this, app, Tiles.Size.SMALL) { launch(app.launch) },
                GridLayout.LayoutParams().apply {
                    rightMargin = Tiles.dp(this@HomeActivity, 24)
                    bottomMargin = Tiles.dp(this@HomeActivity, 20)
                })
        }
        focusFirst()
    }

    private fun controlActions(): List<View> {
        val list = mutableListOf<View>()
        if (AppCatalog.isInstalled(this, AIRPLAY_PKG)) {
            list += Tiles.action(this, R.drawable.ic_airplay, getString(R.string.airplay)) {
                launch(packageManager.getLeanbackLaunchIntentForPackage(AIRPLAY_PKG))
            }
        }
        if (AppCatalog.isInstalled(this, LauncherApp.AGENT_PKG)) {
            list += Tiles.action(this, R.drawable.ic_cast, getString(R.string.cast_from_windows)) {
                launch(Intent().setComponent(ComponentName(LauncherApp.AGENT_PKG, CAST_FROM_WINDOWS)))
            }
        }
        list += Tiles.action(this, R.drawable.ic_settings, getString(R.string.settings)) {
            launch(Intent().setComponent(STOCK_SETTINGS))
        }
        return list
    }

    private fun focusFirst() {
        val first = favorites.getChildAt(0) ?: apps.getChildAt(0) ?: return
        first.requestFocus()
    }

    private fun launch(intent: Intent?) {
        if (intent == null) return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWifi(connected: Boolean) = runOnUiThread {
        wifi.setImageResource(if (connected) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
    }

    private fun updateDate() {
        date.text = SimpleDateFormat("EEEE, d MMMM", uiLocale).format(Date())
            .replaceFirstChar { it.titlecase(uiLocale) }
    }

    private fun gap(endDp: Int, bottomDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginEnd = Tiles.dp(this@HomeActivity, endDp)
        bottomMargin = Tiles.dp(this@HomeActivity, bottomDp)
    }

    companion object {
        private const val AIRPLAY_PKG = "io.github.jqssun.airplay"
        private const val CAST_FROM_WINDOWS = "os.z7.agent.CastFromWindowsActivity"

        /** Стоковые настройки XGIMI — до появления нашего модуля настроек. */
        private val STOCK_SETTINGS = ComponentName(
            "com.android.newsettings", "com.xgimi.newsettings.mock.MockActivity",
        )
    }
}
