package os.z7.launcher

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Пульт проектора управляет приставкой на HDMI (PS5 и т.п.) по HDMI-CEC:
 * <User Control Pressed>/<User Control Released> на логический адрес устройства.
 *
 * Кадры шлём сами через gmpf ([Gmpf.Bridge.sendCec]): системный HdmiControlService отправляет
 * клавиши только «активному источнику», а им прошивка считает сам проектор — нажатия
 * выбрасываются (так было у нас и при стоковом плеере без сеанса CEC).
 *
 * Пересылаются стрелки, OK и «Назад» (как у стокового плеера XGIMI). «Назад» уходит приставке
 * коротким нажатием, долгое — выход из HDMI ([onExit]). «Домой» и долгое «меню» сюда не доходят.
 */
class CecRemote(private val context: Context, private val onExit: () -> Unit) {

    /** Устройство CEC на входе: адрес и имя. null — устройство без CEC, пульт не пересылаем. */
    class Device(val address: Int, val name: String)

    var device: Device? = null

    private var sending = 0
    private var lastPress = 0L
    private var backArmed = false
    private var backLong = false

    /** true — клавиша ушла приставке (или это «Назад», который мы разбираем сами). */
    fun dispatch(event: KeyEvent): Boolean {
        val dev = device ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return back(event, dev)
        val code = CODES[event.keyCode] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // зажатая кнопка: Android повторяет каждые ~50 мс, а кадр CEC идёт ~100 мс —
                // повторяем не чаще REPEAT_MS и только когда очередь пуста
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0 || (sending == 0 && now - lastPress >= REPEAT_MS)) press(dev, code)
            }
            KeyEvent.ACTION_UP -> release(dev)
        }
        return true
    }

    private fun back(event: KeyEvent, dev: Device): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                backArmed = true
                backLong = false
            } else if (backArmed && !backLong && event.eventTime - event.downTime >= LONG_BACK_MS) {
                backLong = true
                onExit()
            }
            KeyEvent.ACTION_UP -> {
                if (backArmed && !backLong && !event.isCanceled) {
                    press(dev, UI_EXIT)
                    release(dev)
                }
                backArmed = false
            }
        }
        return true
    }

    private fun press(dev: Device, code: Int) {
        lastPress = SystemClock.uptimeMillis()
        send { it.sendCec(dev.address, OP_PRESSED, code) }
    }

    private fun release(dev: Device) = send { it.sendCec(dev.address, OP_RELEASED) }

    private fun send(work: (Gmpf.Bridge) -> Boolean) {
        sending++
        Gmpf.run(context, work) { sending-- }
    }

    companion object {
        private const val OP_PRESSED = 0x44
        private const val OP_RELEASED = 0x45

        // коды UI command (HDMI-CEC 1.4, CEC Table 27)
        private const val UI_EXIT = 0x0D
        private val CODES = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to 0x00,
            KeyEvent.KEYCODE_ENTER to 0x00,
            KeyEvent.KEYCODE_DPAD_UP to 0x01,
            KeyEvent.KEYCODE_DPAD_DOWN to 0x02,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x03,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x04,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 0x44,
            KeyEvent.KEYCODE_MEDIA_PLAY to 0x44,
            KeyEvent.KEYCODE_MEDIA_PAUSE to 0x46,
            KeyEvent.KEYCODE_MEDIA_STOP to 0x45,
            KeyEvent.KEYCODE_MEDIA_REWIND to 0x48,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to 0x49,
        )

        private const val REPEAT_MS = 250L
        private const val LONG_BACK_MS = 700L

        /** Адрес первого воспроизводящего устройства — если прошивка не сказала адрес. */
        private const val DEFAULT_ADDRESS = 4

        /**
         * Устройство CEC на физическом входе [inputId]: прошивка заводит для него дочерний вход
         * (`…/HDMI300004` под `…/HW1`) с HdmiDeviceInfo. getHdmiDeviceInfo — системный API, зовём рефлексией.
         */
        fun find(context: Context, inputId: String): Device? = try {
            context.getSystemService(TvInputManager::class.java).tvInputList
                .firstOrNull { it.parentId == inputId }
                ?.let { child -> Device(address(child), child.loadLabel(context)?.toString().orEmpty()) }
        } catch (_: Exception) {
            null
        }

        private fun address(info: TvInputInfo): Int = try {
            val hdmi = TvInputInfo::class.java.getMethod("getHdmiDeviceInfo").invoke(info)
            (hdmi?.javaClass?.getMethod("getLogicalAddress")?.invoke(hdmi) as? Int)
                ?.takeIf { it in 1..14 } ?: DEFAULT_ADDRESS
        } catch (_: Exception) {
            DEFAULT_ADDRESS
        }
    }
}
