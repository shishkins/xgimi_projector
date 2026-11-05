package os.z7.miracast

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Свой приёмник Miracast: экран ожидания «Win + K», затем картинка с компьютера.
 * Сеанс: [P2pLink] (компьютер подключился) → [RtspSink] (договорились о формате) → [MirrorPlayer].
 * «Назад» — выход; сеанс и группа Wi-Fi Direct закрываются вместе с экраном.
 */
class MiracastActivity : Activity(), P2pLink.Listener, RtspSink.Listener, MirrorPlayer.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var p2p: P2pLink
    private lateinit var surfaceView: SurfaceView
    private lateinit var waiting: View
    private lateinit var status: TextView
    private var pulse: ValueAnimator? = null
    private var surfaceReady = false
    private var rtsp: RtspSink? = null
    private var player: MirrorPlayer? = null
    private var lastIdr = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopSession("surface gone") }
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        waiting = buildWaiting()
        root.addView(waiting, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        p2p = P2pLink(this, this)
    }

    override fun onStart() {
        super.onStart()
        L.i("activity: start, waiting for a source")
        showWaiting(getString(R.string.status_waiting))
        p2p.start()
    }

    override fun onStop() {
        L.i("activity: stop")
        stopSession("activity stopped")
        p2p.stop()
        super.onStop()
    }

    // --- P2P ---

    override fun onSourceConnected(name: String, host: String, port: Int) {
        L.i("session: source '$name' at $host:$port")
        showWaiting(getString(R.string.status_connecting, name))
        stopSession("new source")
        rtsp = RtspSink(host, port, this).also { it.start() }
    }

    override fun onSourceLost() {
        L.i("session: source left the group")
        stopSession("p2p lost")
        showWaiting(getString(R.string.status_waiting))
        p2p.stop()
        p2p.start()
    }

    // --- RTSP ---

    override fun onFormat(video: String?, audio: String?) {
        main.post {
            showWaiting(getString(R.string.status_negotiating))
            startPlayer()
        }
    }

    override fun onPlaying() {
        L.i("session: playing")
        main.post { p2p.pauseDiscovery() }
    }

    override fun onClosed(reason: String) {
        L.i("session: closed ($reason)")
        main.post {
            stopSession(reason)
            showWaiting(getString(R.string.status_failed))
        }
    }

    // --- плеер ---

    override fun onVideoSize(width: Int, height: Int) {
        main.post { fitSurface(width, height) }
    }

    override fun onFirstFrame() {
        L.i("session: first frame on screen")
        main.post { hideWaiting() }
    }

    override fun onLoss() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIdr < 1000) return
        lastIdr = now
        rtsp?.requestIdr()
    }

    private fun startPlayer() {
        if (player != null) return
        if (!surfaceReady) { L.w("session: no surface yet"); return }
        player = MirrorPlayer(surfaceView.holder.surface, this).also {
            try { it.start() } catch (e: Exception) { L.w("player start failed", e); player = null }
        }
    }

    private fun stopSession(reason: String) {
        if (rtsp == null && player == null) return
        L.i("session: stop ($reason)")
        rtsp?.close(); rtsp = null
        player?.stop(); player = null
    }

    /** Картинка во весь экран с сохранением пропорций. */
    private fun fitSurface(w: Int, h: Int) {
        val root = surfaceView.parent as View
        val rw = root.width.toFloat(); val rh = root.height.toFloat()
        if (rw <= 0 || rh <= 0 || w <= 0 || h <= 0) return
        val scale = minOf(rw / w, rh / h)
        surfaceView.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
    }

    // --- экран ожидания ---

    private fun showWaiting(text: String) {
        status.text = text
        if (waiting.visibility != View.VISIBLE) {
            waiting.alpha = 0f
            waiting.visibility = View.VISIBLE
            waiting.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        pulse?.resume()
    }

    private fun hideWaiting() {
        waiting.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            waiting.visibility = View.GONE
            pulse?.pause()
        }.start()
    }

    private fun buildWaiting(): View {
        val ring = PulseView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val iconBox = FrameLayout(context).apply {
                addView(ring, FrameLayout.LayoutParams(dp(RING_DP), dp(RING_DP)))
                addView(View(context).apply { background = getDrawable(R.drawable.ic_cast) },
                    FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            }
            addView(iconBox, LinearLayout.LayoutParams(dp(RING_DP), dp(RING_DP)))
            addView(text(getString(R.string.title), 40f, bold = true), wrap(top = 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(getString(R.string.step_press), 22f, secondary = true))
                addView(keyCap("Win"), wrap(start = 14))
                addView(text("+", 22f, secondary = true), wrap(start = 10))
                addView(keyCap("K"), wrap(start = 10))
                addView(text(getString(R.string.step_choose), 22f, secondary = true), wrap(start = 14))
            }, wrap(top = 28))
            addView(text(deviceName(), 30f, bold = true).apply {
                background = getDrawable(R.drawable.key_cap)
                setPadding(dp(28), dp(10), dp(28), dp(12))
            }, wrap(top = 20))
            status = text("", 18f, secondary = true)
            addView(status, wrap(top = 28))
        }
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { ring.phase = it.animatedValue as Float }
            start()
        }
        return FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_wallpaper)
            addView(content, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            addView(text(getString(R.string.footer), 15f, secondary = true),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        }
    }

    private fun text(value: String, sp: Float, secondary: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        paint.isFakeBoldText = bold
        maxLines = 1
    }

    private fun keyCap(label: String) = text(label, 20f, bold = true).apply {
        background = getDrawable(R.drawable.key_cap)
        setPadding(dp(14), dp(4), dp(14), dp(6))
    }

    private fun wrap(top: Int = 0, start: Int = 0) = LinearLayout.LayoutParams(WRAP, WRAP).apply {
        topMargin = dp(top); marginStart = dp(start)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Имя, под которым проектор виден в Win+K (настройки XGIMI), иначе — по умолчанию. */
    private fun deviceName(): String = try {
        contentResolver.query(Uri.parse("content://com.xgimi.appdbsettings/Settings"), arrayOf("XgimiDeviceName"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    } catch (_: Exception) { null } ?: "XGIMI Projector"

    private class PulseView(context: Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * context.resources.displayMetrics.density
            color = context.getColor(R.color.accent)
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.tile_idle) }
        var phase = 0f
            set(value) { field = value; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val max = minOf(cx, cy) - stroke.strokeWidth
            val min = max * 0.48f
            canvas.drawCircle(cx, cy, min, core)
            for (i in 0 until 3) {
                val t = (phase + i / 3f) % 1f
                stroke.alpha = ((1f - t) * 200).toInt()
                canvas.drawCircle(cx, cy, min + (max - min) * t, stroke)
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RING_DP = 220
        const val PULSE_MS = 2_400L
        const val FADE_MS = 220L
    }
}
