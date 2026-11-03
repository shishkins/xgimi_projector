package os.z7.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Сброс к заводским настройкам — тем же вызовом, что кнопка «Восстановить заводские» XGIMI:
 * служба com.xgimi.xgimiservice (IXgimiService.getGimiFactory → IGimiFactory.setRestoryFactoryAndClearData).
 * Служба системная, стирает /data и перезагружает проектор: после этого снова стоковая GMUI,
 * наши приложения удаляются вместе с данными.
 */
class FactoryReset(private val context: Context) {

    private var binder: IBinder? = null
    private var onReady: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            onReady?.invoke(true)
            onReady = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    /** Подключиться к службе XGIMI; [ready] — удалось ли. */
    fun connect(ready: (Boolean) -> Unit) {
        onReady = ready
        val ok = try {
            context.bindService(Intent(ACTION).setPackage(PKG), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bind failed", e)
            false
        }
        if (!ok) {
            onReady = null
            ready(false)
        }
    }

    fun disconnect() {
        try { context.unbindService(connection) } catch (_: Exception) {}
        binder = null
    }

    /** Запустить сброс. Не возвращается при успехе — проектор уходит в перезагрузку. */
    fun run(): Boolean {
        val service = binder ?: return false
        return try {
            val factory = call(service, SERVICE_DESCRIPTOR, TX_GET_GIMI_FACTORY) { it.readStrongBinder() } ?: return false
            call(factory, FACTORY_DESCRIPTOR, TX_RESTORE_AND_CLEAR_DATA) { }
            true
        } catch (e: Exception) {
            Log.w(TAG, "factory reset failed", e)
            false
        }
    }

    private fun <T> call(target: IBinder, descriptor: String, code: Int, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            target.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "z7.FactoryReset"
        const val PKG = "com.xgimi.xgimiservice"
        const val ACTION = "com.xgimi.xgimiservice.services.XgimiServices"
        const val SERVICE_DESCRIPTOR = "com.xgimi.aidl.IXgimiService"
        const val FACTORY_DESCRIPTOR = "com.xgimi.aidl.IGimiFactory"
        const val TX_GET_GIMI_FACTORY = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TX_RESTORE_AND_CLEAR_DATA = IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
