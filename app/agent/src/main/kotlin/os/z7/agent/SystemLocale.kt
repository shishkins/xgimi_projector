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
 * и системные диалоги (установщик пакетов и т.п.), и меню XGIMI. Язык наших модулей задаётся
 * отдельно от системного. Меняем язык так же, как «Настройки → Язык»: updatePersistentConfiguration
 * (сразу и после перезагрузки). Разрешение CHANGE_CONFIGURATION — development, выдаётся shell'ом.
 * Откат: restore.ps1 возвращает zh-CN.
 */
class SystemLocale(private val context: Context, private val adb: AdbBridge) {

    /**
     * Системный язык → [Config.DEFAULT_LOCALE], если стоит другой.
     * Обычный `en` нельзя: настройки XGIMI при language=="en" возвращают систему на zh-CN и не
     * открываются («English is no longer supported», SettingService.showWindow). Поэтому английский
     * задаём трёхбуквенным кодом `eng`: проверку XGIMI он проходит, а приложения, у которых нет
     * ресурсов под `eng`, берут базовые — английские (Android, установщик, названия приложений).
     */
    fun ensureSupported() {
        val current = Resources.getSystem().configuration.locales[0]
        val target = Locale.forLanguageTag(Config.DEFAULT_LOCALE)
        if (current.language == target.language) return
        Logf.w("locale: system is $current — switching to $target")
        set(target)
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
