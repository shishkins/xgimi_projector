package os.z7.launcher

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык нашего интерфейса — свой, не системный. Системный язык агент держит русским:
 * английский настройки XGIMI откатывают на китайский (см. агент, SystemLocale).
 * По умолчанию английский; русский — выбором в будущем модуле настроек.
 */
object UiLanguage {

    private const val PREFS = "ui"
    private const val KEY = "language"
    private const val DEFAULT = "en"

    fun locale(context: Context): Locale =
        Locale(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT)!!)

    /** Контекст с ресурсами на языке интерфейса — для attachBaseContext. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale(base))
        return base.createConfigurationContext(config)
    }
}
