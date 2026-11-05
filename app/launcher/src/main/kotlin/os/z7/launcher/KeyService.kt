package os.z7.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Перехват кнопок пульта. Фильтр служб специальных возможностей видит клавиши раньше,
 * чем их разбирает XGIMI (XgimiWindowManager.interceptKeyBeforeDispatch), поэтому
 * съеденная здесь клавиша до китайского обработчика не доходит.
 *
 * Отвечать надо мгновенно: если onKeyEvent думает дольше 500 мс (KeyEventDispatcher),
 * система отдаёт клавишу дальше — и XGIMI открывает своё меню поверх нашего.
 * Поэтому здесь только решение «съесть», а окна строятся следующим сообщением.
 *
 * Долгое нажатие «меню» пульт шлёт отдельной клавишей MOVE_HOME (сканкод 251,
 * Vendor_3697_Product_0002.kl); прошивка по ней открывает своё быстрое меню
 * (com.android.newsettings.MISCKEY) — мы открываем [QuickPanel].
 * Службу включает агент (enabled_accessibility_services); выключить: restore.ps1.
 */
class KeyService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var panel: QuickPanel
    private lateinit var cover: AkCover
    private lateinit var miracast: MiracastCover

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        KeyLog.init(this)
        KeyLog.w("service connected")
        panel = QuickPanel(this)
        cover = AkCover(this)
        miracast = MiracastCover(this)
        // классы gmpf грузятся из APK настроек ~1 с — заранее, а не при первом открытии
        Gmpf.warmUp(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::panel.isInitialized) return false
        when (event.keyCode) {
            KEY_QUICK_MENU -> {
                val at = SystemClock.uptimeMillis()
                KeyLog.w("key MOVE_HOME action=${event.action} repeat=${event.repeatCount} " +
                    "age=${at - event.eventTime}ms panelShown=${panel.isShown}")
                // XGIMI открывает своё меню на ОТПУСКАНИИ, а пульт шлёт «нажата» и «отпущена» почти
                // одновременно. Если строить панель по «нажата», ответ на «отпущена» ждёт постройку
                // в главном потоке дольше 500 мс — и клавиша уходит прошивке. Поэтому отвечаем на обе
                // половины сразу, а панель строим после «отпущена».
                if (event.action == KeyEvent.ACTION_UP) main.post {
                    KeyLog.w("toggle after ${SystemClock.uptimeMillis() - at}ms in queue")
                    panel.toggle()
                }
                return true
            }
            // HOME окну не доставляется — сами убираем панель, а клавишу пропускаем дальше
            KeyEvent.KEYCODE_HOME -> if (panel.isShown) main.post { panel.hide() }
            // тост XGIMI «OK — ручная трапеция» открыл бы их китайский экран — открываем свой
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (cover.mode == AkCover.Mode.DONE) {
                if (event.action == KeyEvent.ACTION_UP) main.post { KeystoneScreen.show(this) }
                return true
            }
        }
        return false
    }

    /**
     * Экраны автотрапеции XGIMI: калибровочное окно (полноэкранный TVUIDialog) и тост об окончании
     * (ToastDialog) — накрываем их китайские подписи своими. Окно калибровки в списке окон
     * специальных возможностей не видно, поэтому ловим событие его появления.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::cover.isInitialized) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        KeyLog.w("window ${event.packageName} / ${event.className} full=${event.isFullScreen}")
        onStockMenu(event)
        val fromSystemUi = event.packageName == XGIMI_SYSTEMUI
        val cls = event.className?.toString()
        onMiracastWindow(event, cls)
        onStockHdmi(event)
        when {
            fromSystemUi && cls == AK_DIALOG_CLASS && event.isFullScreen -> cover.show(AkCover.Mode.RUNNING)
            fromSystemUi && cls == AK_DONE_CLASS && cover.mode == AkCover.Mode.RUNNING -> cover.show(AkCover.Mode.DONE)
            // пользователь ушёл в другое окно — калибровку прервали
            !fromSystemUi && event.packageName != packageName && cover.mode == AkCover.Mode.RUNNING -> cover.hide()
        }
    }

    /**
     * Стоковый экран ожидания Miracast закрываем своим [MiracastCover]; картинку с Windows
     * (MirCastActivity) и любое другое полноэкранное окно — открываем. Диалоги и тосты стока
     * и чужие всплывашки (громкость) экран не снимают.
     */
    private fun onMiracastWindow(event: AccessibilityEvent, cls: String?) {
        val pkg = event.packageName?.toString()
        when {
            pkg == STOCK_CAST_PKG && cls == WFD_WAIT_CLASS -> miracast.show()
            pkg == STOCK_CAST_PKG && cls == WFD_MIRROR_CLASS -> miracast.hide()
            // свои окна-накладки (панель, этот экран) пропускаем, а наш главный экран — нет
            miracast.isShown && pkg != STOCK_CAST_PKG && event.isFullScreen &&
                (pkg != packageName || cls?.endsWith("Activity") == true) -> miracast.hide()
        }
    }

    /**
     * Стоковый плеер HDMI (его открывает прошивка при подключении устройства, если включено
     * автопереключение) — с китайскими подсказками. Сразу открываем свой экран HDMI поверх.
     */
    private fun onStockHdmi(event: AccessibilityEvent) {
        if (event.packageName != STOCK_HDMI_PKG) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHdmiRedirect < HDMI_REDIRECT_PAUSE_MS) return
        lastHdmiRedirect = now
        main.post { HdmiActivity.open(this, replacingStock = true) }
    }

    private var lastHdmiRedirect = 0L

    /**
     * Страховка: быстрое меню XGIMI всё-таки открылось (клавиша прошла мимо нас) — закрыть его
     * и открыть наш центр управления. Стоковые экраны настроек мы сами не открываем, поэтому
     * любое окно com.android.newsettings, кроме запасного HOME, — это их меню.
     */
    private fun onStockMenu(event: AccessibilityEvent) {
        if (event.packageName != STOCK_SETTINGS_PKG) return
        if (event.className?.toString()?.contains("FallbackHome") == true) return
        KeyLog.w("stock menu window — closing it, opening ours")
        main.post {
            sendBroadcast(android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                .putExtra("reason", packageName))
            if (!panel.isShown) panel.show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::panel.isInitialized) panel.dismiss()
        if (::cover.isInitialized) cover.hide()
        if (::miracast.isInitialized) miracast.hide()
        super.onDestroy()
    }

    companion object {
        private const val KEY_QUICK_MENU = KeyEvent.KEYCODE_MOVE_HOME

        /** Окна автотрапеции XGIMI: калибровка (AK2Window) и тост об окончании. */
        private const val XGIMI_SYSTEMUI = "com.xgimi.systemui"
        private const val AK_DIALOG_CLASS = "com.open.ui.widget.dialog.TVUIDialog"
        private const val AK_DONE_CLASS = "com.xgimi.systemui.tip.ToastDialog"

        /** Стоковый Miracast: экран ожидания и окно с картинкой Windows. */
        private const val STOCK_CAST_PKG = "com.xgimi.wirelessscreen"
        private const val WFD_WAIT_CLASS = "com.xgimi.wfd.WifiDisplayActivity"
        private const val WFD_MIRROR_CLASS = "com.xgimi.wfd.mirror.MirCastActivity"

        /** Стоковый плеер входа HDMI. */
        private const val STOCK_HDMI_PKG = "com.xgimi.xhplayer"
        private const val STOCK_SETTINGS_PKG = "com.android.newsettings"
        private const val HDMI_REDIRECT_PAUSE_MS = 3_000L

        /** Запущенная служба: окна поверх всего (трапеция) может показать только она. */
        @Volatile var instance: KeyService? = null
            private set
    }
}
