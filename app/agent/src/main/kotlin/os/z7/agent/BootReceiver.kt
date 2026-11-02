package os.z7.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Запускает агента после загрузки проектора, после обновления самого агента
 * и по будильнику [ACTION_REVIVE] — если система убила процесс (мало памяти).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("z7.BootReceiver", "received ${intent.action}")
        AgentService.start(context)
    }

    companion object {
        const val ACTION_REVIVE = "os.z7.agent.REVIVE"
    }
}
