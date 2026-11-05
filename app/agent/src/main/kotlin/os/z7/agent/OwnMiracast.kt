package os.z7.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Наш приёмник Miracast (os.z7.miracast): установлен ли и как его открыть. */
object OwnMiracast {
    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(Config.OWN_MIRACAST_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun open(context: Context) {
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(Config.OWN_MIRACAST_PKG, Config.OWN_MIRACAST_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
