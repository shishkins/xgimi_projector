package os.z7.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserManager

/**
 * Домашний экран на время загрузки — замена FallbackHome из стоковых настроек XGIMI.
 *
 * Пока пользователь не разблокирован (BOOTING), Android запускает только HOME с directBootAware.
 * Загрузка завершается (sys.boot_completed), лишь когда такой экран открылся; без него экран
 * остаётся чёрным навсегда (2026-11-06: выключили com.android.newsettings — и проектор не
 * догрузился). С этим экраном стоковые настройки (а с ними и быстрое меню XGIMI) можно выключить.
 *
 * Сам экран чёрный и ничего не читает из хранилища (до разблокировки оно может быть недоступно):
 * дождаться разблокировки — открыть наш главный экран — закрыться. Приоритет −1000, как у
 * стокового: после разблокировки HOME всегда наш [HomeActivity].
 */
class BootHomeActivity : Activity() {

    private var registered = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = openHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            openHome()
            return
        }
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        registered = true
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(unlocked)
        registered = false
        super.onDestroy()
    }

    override fun onBackPressed() {
        // некуда возвращаться — ждём разблокировки
    }

    private fun openHome() {
        try {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
