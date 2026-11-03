package os.z7.agent

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.provider.Settings
import java.util.Locale

/**
 * Язык системы. Прошивка XGIMI стоит на zh-CN (persist.sys.locale), из-за этого по-китайски
 * и системные диалоги (установщик пакетов и т.п.). Наша система — английская по умолчанию,
 * русский по выбору. Меняем язык так же, как «Настройки → Язык»: updatePersistentConfiguration
 * (сразу и после перезагрузки). Разрешение CHANGE_CONFIGURATION — development, выдаётся shell'ом.
 * Откат: restore.ps1 возвращает zh-CN.
 */
class SystemLocale(private val context: Context, private val adb: AdbBridge) {

    /** Китайский системный язык → английский. Выбранный пользователем язык не трогаем. */
    fun ensureNotChinese() {
        val current = Resources.getSystem().configuration.locales[0]
        if (current.language != "zh") return
        Logf.w("locale: system is $current — switching to ${Config.DEFAULT_LOCALE}")
        set(Locale.forLanguageTag(Config.DEFAULT_LOCALE))
    }

    fun set(locale: Locale) {
        if (context.checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            adb.run("pm grant ${context.packageName} $PERM")
        }
        // updatePersistentConfiguration проверяет ещё и WRITE_SETTINGS (app-op)
        if (!Settings.System.canWrite(context)) {
            adb.run("appops set ${context.packageName} WRITE_SETTINGS allow")
        }
        try {
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val config = am.javaClass.getMethod("getConfiguration").invoke(am) as Configuration
            config.setLocales(LocaleList(locale))
            Configuration::class.java.getField("userSetLocale").setBoolean(config, true)
            am.javaClass.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(am, config)
            Logf.w("locale: set to $locale")
        } catch (e: Exception) {
            // запасной путь: применится после перезагрузки
            val c = e.cause ?: e
            Logf.w("locale: live switch failed (${c.javaClass.simpleName}: ${c.message}) — setprop")
            adb.run("setprop persist.sys.locale ${locale.toLanguageTag()}")
        }
    }

    private companion object {
        const val PERM = "android.permission.CHANGE_CONFIGURATION"
    }
}
