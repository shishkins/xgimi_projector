package os.z7.launcher

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык нашего интерфейса — свой, не системный: системный агент держит на `eng-US`, потому что
 * `en` настройки XGIMI откатывают на китайский (см. агент, SystemLocale).
 * По умолчанию английский; русский выбирается в наших настройках.
 */
object UiLanguage {

    private const val PREFS = "ui"
    private const val KEY = "language"
    private const val DEFAULT = "en"

    /** Языки на выбор: код и название на самом языке. */
    val choices = listOf("en" to "English", "ru" to "Русский")

    fun code(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT)!!

    fun locale(context: Context): Locale = Locale(code(context))

    fun set(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** Контекст с ресурсами на языке интерфейса — для attachBaseContext. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale(base))
        return base.createConfigurationContext(config)
    }
}
