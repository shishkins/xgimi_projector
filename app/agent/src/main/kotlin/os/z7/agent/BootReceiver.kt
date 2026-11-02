package os.z7.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Запускает агента после загрузки проектора и после обновления самого агента. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("z7.BootReceiver", "received ${intent.action}")
        AgentService.start(context)
    }
}
