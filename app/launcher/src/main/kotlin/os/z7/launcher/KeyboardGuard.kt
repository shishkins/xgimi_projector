package os.z7.launcher

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import java.util.Collections
import java.util.WeakHashMap

/**
 * Чужая экранная клавиатура не должна висеть поверх наших экранов.
 *
 * Приложение может показать её «принудительно» (SHOW_FORCED — так делает киноприложение владельца
 * по OK с пульта), и тогда система при смене окна её не прячет: после «Домой» клавиатура оставалась
 * поверх главного экрана, убиралась только «Назад» (2026-11-06). Своих полей ввода с системной
 * клавиатурой у нас нет (своя [Keyboard]), поэтому любое наше окно, получив фокус, прячет её
 * явно — окну в фокусе система это разрешает, в том числе для принудительного показа.
 */
object KeyboardGuard {

    private val attached = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** Подключить к экрану (из onResume: окно уже создано). */
    fun attach(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (!attached.add(decor)) return
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            // после текущего сообщения: система сначала привязывает ввод к нашему окну
            if (hasFocus) decor.post { hide(activity, decor) }
        }
        if (decor.hasWindowFocus()) decor.post { hide(activity, decor) }
    }

    private fun hide(activity: Activity, decor: View) {
        val token = decor.windowToken ?: return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
    }
}
