package os.z7.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Вход HDMI — свой экран вместо стокового плеера XGIMI (com.xgimi.xhplayer, китайские подсказки).
 * Картинку показывает стандартный TvView: прошивка отдаёт HDMI обычным TV Input
 * (com.xgimi.tvinput, Hdmi1TvInputService), сток делает то же самое (tune на passthrough-канал).
 *
 * Если наш экран открылся вместо стокового (см. [KeyService]), сток при уходе в фон сбрасывает
 * свой сеанс и может задеть наш — поэтому без картинки через несколько секунд настраиваемся заново.
 */
class HdmiActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tv: TvView
    private lateinit var waiting: View
    private lateinit var waitingTitle: TextView
    private lateinit var info: TextView
    private var inputId: String? = null
    /** Устройство на HDMI подключено (getHdmiConnectStatus). События TvView и getSignalStatus здесь врут. */
    private var hasVideo = false
    private var retries = 0
    private var started = false
    private var port = 1
    private var gameOn = false
    private var focusRequest: AudioFocusRequest? = null
    /** Пульт → приставка по HDMI-CEC; «Назад» удержанием — выход. */
    private val remote = CecRemote(this) { onBackPressed() }

    private val retune = Runnable {
        if (!hasVideo && retries < MAX_RETRIES) {
            retries++
            tune()
        }
    }
    private val hideInfo = Runnable { info.animate().alpha(0f).setDuration(400).start() }

    /** Опрос сигнала раз в секунду, как делает стоковый плеер. */
    private val pollSignal = object : Runnable {
        override fun run() {
            Gmpf.run(this@HdmiActivity, { Hw.hdmiConnected(it) to Hw.allm(it, port) }) { state ->
                if (!started) return@run
                if (state != null) {
                    onSignal(state.first)
                    applyGameMode(allm = state.first && state.second)
                }
                main.postDelayed(this, SIGNAL_POLL_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        tv = TvView(this)
        root.addView(tv, FrameLayout.LayoutParams(MATCH, MATCH))

        waitingTitle = Ui.light(Ui.text(this, getString(R.string.hdmi_no_signal), 40f))
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.icon(this@HdmiActivity, R.drawable.ic_hdmi, 88).apply { alpha = 0.85f })
            addView(waitingTitle, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
            addView(Ui.text(this@HdmiActivity, getString(R.string.hdmi_no_signal_hint), 19f, secondary = true).apply {
                maxLines = 2
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
            addView(Ui.text(this@HdmiActivity, getString(R.string.hdmi_footer), 16f, secondary = true).apply {
                alpha = 0.7f
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(48) })
        }
        root.addView(waiting, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        info = Ui.text(this, "", 17f).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(this@HdmiActivity.getColor(R.color.panel_bg))
                cornerRadius = dp(100).toFloat()
            }
            alpha = 0f
        }
        root.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48)
            topMargin = dp(40)
        })
        setContentView(root)

        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(id: String) = scheduleRetune()
            override fun onDisconnected(id: String) = scheduleRetune()
        })
    }

    override fun onStart() {
        super.onStart()
        hasVideo = false
        retries = 0
        started = true
        isVisible = true
        waiting.alpha = 1f
        inputId = findInput(this)
        if (inputId == null) {
            waitingTitle.setText(R.string.hdmi_not_found)
            return
        }
        requestAudioFocus()
        // открылись вместо стокового плеера — даём ему отпустить вход
        val delay = if (intent.getBooleanExtra(EXTRA_REPLACING_STOCK, false)) STOCK_RELEASE_MS else 0L
        main.postDelayed({ tune() }, delay)
        main.postDelayed(pollSignal, delay + SIGNAL_POLL_MS)
    }

    /**
     * Игровой режим, как у стокового плеера (тот ловил событие ALLM и включал «максимальную
     * скорость»): «авто» — пока приставка просит низкую задержку, «всегда», «выкл».
     */
    private fun applyGameMode(allm: Boolean) {
        val want = when (gameMode(this)) {
            GAME_ALWAYS -> hasVideo
            GAME_OFF -> false
            else -> allm
        }
        if (want == gameOn) return
        gameOn = want
        val p = port
        Gmpf.run(this, { Hw.setGameMode(it, p, want) })
        showInfo(getString(if (want) R.string.hdmi_game_on else R.string.hdmi_game_off))
    }

    private fun onSignal(signal: Boolean) {
        if (signal == hasVideo) return
        hasVideo = signal
        waiting.animate().alpha(if (signal) 0f else 1f).setDuration(250).start()
        if (signal) {
            main.removeCallbacks(retune)
            // дочерний вход CEC прошивка заводит, когда устройство ответило, — ищем при каждом появлении сигнала
            remote.device = inputId?.let { CecRemote.find(this, it) }
            showInfo(hint = remote.device?.let { getString(R.string.hdmi_remote_hint, it.name.ifEmpty { "HDMI" }) })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hasVideo && remote.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        isVisible = false
        started = false
        main.removeCallbacksAndMessages(null)
        if (gameOn) {
            val p = port
            Gmpf.run(this, { Hw.setGameMode(it, p, false) })
            gameOn = false
        }
        tv.reset()
        Gmpf.run(this, { Hw.switchToAndroid(it) })
        hasVideo = false
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** «Назад» — на наш главный экран, а не в оставшийся под нами стоковый плеер. */
    override fun onBackPressed() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun tune() {
        val id = inputId ?: return
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
        // сам tune видеотракт не переключает — так же, как сток, переключаем источник на порт
        port = when {
            id.contains("Hdmi2", ignoreCase = true) -> 2
            id.contains("Hdmi3", ignoreCase = true) -> 3
            else -> 1
        }
        val p = port
        Gmpf.run(this, { Hw.switchToHdmi(it, p) })
        scheduleRetune()
    }

    private fun scheduleRetune() {
        main.removeCallbacks(retune)
        main.postDelayed(retune, RETUNE_MS)
    }

    /** Плашка «HDMI · 1920×1080 · 60 Hz» на несколько секунд после появления картинки. */
    private fun showInfo(extra: String? = null, hint: String? = null) {
        val parts = mutableListOf("HDMI")
        extra?.let { parts += it }
        try {
            val selected = tv.getSelectedTrack(TvTrackInfo.TYPE_VIDEO)
            tv.getTracks(TvTrackInfo.TYPE_VIDEO)?.firstOrNull { it.id == selected }?.let { t ->
                if (t.videoWidth > 0 && t.videoHeight > 0) parts += "${t.videoWidth}×${t.videoHeight}"
                if (t.videoFrameRate > 0f) parts += "${Math.round(t.videoFrameRate)} Hz"
            }
        } catch (_: Exception) {
        }
        info.text = parts.joinToString("  ·  ") + (hint?.let { "\n$it" } ?: "")
        info.animate().alpha(1f).setDuration(250).start()
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, if (hint != null) HINT_MS else INFO_MS)
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .build()
        focusRequest = request
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_REPLACING_STOCK = "z7.replacing_stock"

        /** Экран HDMI сейчас на экране — автопереключение не открывает его второй раз. */
        @Volatile var isVisible = false
            private set

        /** Своё автопереключение на HDMI (у стокового — китайский отсчёт), по умолчанию включено. */
        fun autoSwitch(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

        fun setAutoSwitch(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()

        /** Игровой режим: авто (по ALLM), всегда, выкл. */
        const val GAME_AUTO = 0
        const val GAME_ALWAYS = 1
        const val GAME_OFF = 2

        fun gameMode(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GAME, GAME_AUTO)

        fun setGameMode(context: Context, mode: Int) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GAME, mode).apply()

        private const val KEY_GAME = "game_mode"
        private const val PREFS = "hdmi"
        private const val KEY_AUTO = "auto_switch"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val STOCK_RELEASE_MS = 900L
        private const val RETUNE_MS = 3_000L
        private const val MAX_RETRIES = 3
        private const val INFO_MS = 4_000L
        private const val HINT_MS = 6_000L
        private const val SIGNAL_POLL_MS = 1_000L

        /** Первый вход HDMI (без дочерних входов HDMI-CEC). */
        fun findInput(context: Context): String? = try {
            context.getSystemService(TvInputManager::class.java).tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
                .minByOrNull { it.id }?.id
        } catch (_: Exception) {
            null
        }

        fun open(context: Context, replacingStock: Boolean = false) {
            context.startActivity(Intent(context, HdmiActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_REPLACING_STOCK, replacingStock))
        }
    }
}
