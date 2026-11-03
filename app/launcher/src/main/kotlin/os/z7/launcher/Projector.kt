package os.z7.launcher

import android.content.Context
import android.content.Intent

/**
 * Команды проектора через штатные обработчики XGIMI — те же, что вызывают кнопки пульта
 * (/vendor/etc/xgimi_action_config.xml). Фокус и трапеция живут в нативном слое gmpf,
 * обычному приложению он недоступен, поэтому идём через сервис com.xgimi.windowsystem:
 * он пересылает `type` в com.xgimi.systemui (FocusUIV2.receiveIntent), а тот рисует свой
 * графический экран без текста.
 */
object Projector {

    private const val AF_AK_ACTION = "com.xgimi.action.WINODWSYSTEM"
    private const val AF_AK_PKG = "com.xgimi.windowsystem"

    private const val TYPE_MANUAL_FOCUS = 1
    private const val TYPE_AUTO_FOCUS = 8
    private const val TYPE_AUTO_KEYSTONE = 10

    fun autoFocus(context: Context) = afAk(context, TYPE_AUTO_FOCUS)
    fun autoKeystone(context: Context) = afAk(context, TYPE_AUTO_KEYSTONE)
    fun manualFocus(context: Context) = afAk(context, TYPE_MANUAL_FOCUS)

    private fun afAk(context: Context, type: Int): Boolean = try {
        context.startService(Intent(AF_AK_ACTION).setPackage(AF_AK_PKG).putExtra("type", type)) != null
    } catch (e: Exception) {
        false
    }
}
