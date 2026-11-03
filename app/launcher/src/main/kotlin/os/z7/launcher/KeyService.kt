package os.z7.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        panel = QuickPanel(this)
        // классы gmpf грузятся из APK настроек ~1 с — заранее, а не при первом открытии
        Gmpf.warmUp(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::panel.isInitialized) return false
        when (event.keyCode) {
            KEY_QUICK_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) main.post { panel.toggle() }
                return true
            }
            // HOME окну не доставляется — сами убираем панель, а клавишу пропускаем дальше
            KeyEvent.KEYCODE_HOME -> if (panel.isShown) main.post { panel.hide() }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::panel.isInitialized) panel.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val KEY_QUICK_MENU = KeyEvent.KEYCODE_MOVE_HOME

        /** Запущенная служба: окна поверх всего (трапеция) может показать только она. */
        @Volatile var instance: KeyService? = null
            private set
    }
}
