package os.z7.launcher

import android.content.Context
import android.content.Intent

/**
 * Фокус через штатный обработчик XGIMI — тот же, что у кнопок пульта
 * (/vendor/etc/xgimi_action_config.xml): сервис com.xgimi.windowsystem пересылает `type`
 * в com.xgimi.systemui (FocusUIV2.receiveIntent), а тот рисует графический экран фокуса
 * без текста и сам ведёт мотор. Трапеция, яркость и масштаб — напрямую, см. [Gmpf].
 */
object Projector {

    private const val AF_AK_ACTION = "com.xgimi.action.WINODWSYSTEM"
    private const val AF_AK_PKG = "com.xgimi.windowsystem"

    private const val TYPE_MANUAL_FOCUS = 1
    private const val TYPE_AUTO_FOCUS = 8

    fun autoFocus(context: Context) = afAk(context, TYPE_AUTO_FOCUS)
    fun manualFocus(context: Context) = afAk(context, TYPE_MANUAL_FOCUS)

    private fun afAk(context: Context, type: Int): Boolean = try {
        context.startService(Intent(AF_AK_ACTION).setPackage(AF_AK_PKG).putExtra("type", type)) != null
    } catch (e: Exception) {
        false
    }
}
