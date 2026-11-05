package os.z7.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Приложение на главном экране. */
class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** Баннер 16:9 (у TV-приложений), иначе рисуем иконку на карточке. */
    val banner: Drawable?,
    val launch: Intent,
)

/**
 * Список приложений для главного экрана: всё, что можно запустить с пульта,
 * без служебных и китайских приложений XGIMI.
 */
object AppCatalog {

    /** Порядок избранного по умолчанию (показываются только установленные). */
    private val FAVORITES = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.falcofemoralis.hdrezkaapp",
        "mobi.zona",
        "org.videolan.vlc",
        "com.aurora.store",
    )

    /**
     * Не показываем: наши служебные точки входа (они живут в ряду управления),
     * китайские сервисы XGIMI и старые помощники. Приложения не отключаются — только скрыты.
     */
    private val HIDDEN = setOf(
        "os.z7.agent",
        "os.z7.launcher",
        "os.z7.airplay",
        "io.github.jqssun.airplay",
        "local.z7x.adb",
        "com.android.newsettings",
        "com.xgimi.user",
        "com.xgimi.atmosphere",
        "com.xgimi.doubanfm",
        "com.xgimi.newappmarket",
        "com.xgimi.upgrade",
        "com.xgimi.vcontrol",
        "com.xgimi.instruction30",
        "com.xgimi.manager",
        "com.cibn.tv",
        "com.ionitech.airscreen",
    )

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, AppEntry>()
        // сначала TV-точки входа (у них есть баннеры), потом обычные
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in HIDDEN || pkg in result) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                    ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                } else null
                result[pkg] = AppEntry(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm),
                    banner = banner,
                    launch = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun favorites(all: List<AppEntry>): List<AppEntry> {
        val byPkg = all.associateBy { it.packageName }
        return FAVORITES.mapNotNull { byPkg[it] }
    }

    /**
     * Ряд главного экрана: сначала недавно открытые (последнее — первым), затем избранное,
     * затем остальные по алфавиту — не больше [limit].
     */
    fun homeRow(context: Context, all: List<AppEntry>, limit: Int): List<AppEntry> {
        val byPkg = all.associateBy { it.packageName }
        val order = LinkedHashSet<String>()
        recent(context).forEach { if (it in byPkg) order += it }
        FAVORITES.forEach { if (it in byPkg) order += it }
        all.forEach { order += it.packageName }
        return order.take(limit).mapNotNull { byPkg[it] }
    }

    /** Запомнить запуск — приложение встанет первым в ряду. */
    fun noteLaunch(context: Context, pkg: String) {
        val list = listOf(pkg) + recent(context).filter { it != pkg }
        prefs(context).edit().putString(KEY_RECENT, list.take(RECENT_MAX).joinToString(",")).apply()
    }

    private fun recent(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, "")!!.split(',').filter { it.isNotEmpty() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Установлено пользователем (можно удалить), а не частью прошивки. */
    fun isUserApp(context: Context, pkg: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
        (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private const val PREFS = "home"
    private const val KEY_RECENT = "recent"
    private const val RECENT_MAX = 20

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
