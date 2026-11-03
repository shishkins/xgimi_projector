package os.z7.launcher

import android.content.Context
import android.content.Intent

/**
 * Стоковые настройки XGIMI — до появления нашего модуля настроек.
 * Это не activity, а оверлей сервиса com.android.newsettings/SettingService: открывается
 * startService с адресом страницы в extra `data` (как в их SettingActionContract).
 * Единственная launcher-activity пакета (MockActivity) — отладочная заглушка, сразу закрывается.
 */
object StockSettings {

    private const val PKG = "com.android.newsettings"
    private const val ACTION = "com.xgimi.settings.SETTINGS"

    const val MAIN = "main"
    const val WIFI = "Settings://com.xgimi.settings.net/wifi"
    const val BLUETOOTH = "Settings://com.xgimi.settings.bluetooth"
    const val KEYSTONE = "Settings://com.xgimi.settings.picture/keyStone"
    const val ZOOM = "Settings://com.xgimi.settings.picture/zoom"
    const val BRIGHTNESS = "Settings://com.xgimi.settings.brightness/brightness_misckey"
    const val PICTURE_MODE = "Settings://com.xgimi.settings.image/mode_misckey"

    /** false — сервис не нашёлся или не запустился. */
    fun open(context: Context, page: String): Boolean = try {
        context.startService(Intent(ACTION).setPackage(PKG).putExtra("data", page)) != null
    } catch (e: Exception) {
        false
    }
}
