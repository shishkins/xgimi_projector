package os.z7.launcher

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Карточка поверх экрана активности (подтверждение, ввод пароля): затемнение, карточка по центру,
 * «Назад» закрывает. Своя, а не AlertDialog — тот рисуется темой прошивки.
 */
class Sheet(private val activity: Activity) {

    private val host = activity.findViewById<FrameLayout>(android.R.id.content)
    private var view: View? = null
    private var restoreFocus: View? = null

    val isShown: Boolean get() = view != null

    /** Показать [content] в карточке; [focus] получит фокус. */
    fun show(content: View, focus: View? = null) {
        dismiss()
        restoreFocus = activity.currentFocus
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.panel_bg))
                cornerRadius = Ui.dp(activity, 28).toFloat()
            }
            setPadding(Ui.dp(activity, 40), Ui.dp(activity, 32), Ui.dp(activity, 40), Ui.dp(activity, 32))
            addView(content)
        }
        val root = object : FrameLayout(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(activity.getColor(R.color.scrim))
            isClickable = true
            addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            alpha = 0f
        }
        host.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        view = root
        card.scaleX = CARD_START_SCALE
        card.scaleY = CARD_START_SCALE
        root.animate().alpha(1f).setDuration(ANIM_MS).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_MS).start()
        (focus ?: card).requestFocus()
    }

    /** Вопрос с кнопками; любая кнопка закрывает карточку и выполняет своё действие. */
    fun confirm(title: String, message: String?, actions: List<Pair<String, () -> Unit>>) {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Ui.text(activity, title, 26f).apply { maxLines = 2 })
        if (message != null) {
            column.addView(Ui.text(activity, message, 17f, secondary = true).apply {
                maxLines = 6
                setPadding(0, Ui.dp(activity, 12), 0, 0)
                maxWidth = Ui.dp(activity, MESSAGE_WIDTH_DP)
            })
        }
        var first: View? = null
        for ((label, action) in actions) {
            val row = Ui.Row(activity, null, label)
            row.setOnClickListener { dismiss(); action() }
            column.addView(row, LinearLayout.LayoutParams(Ui.dp(activity, MESSAGE_WIDTH_DP), WRAP).apply {
                topMargin = Ui.dp(activity, if (first == null) 24 else 8)
            })
            if (first == null) first = row
        }
        show(column, first)
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        host.removeView(v)
        restoreFocus?.requestFocus()
        restoreFocus = null
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ANIM_MS = 160L
        const val CARD_START_SCALE = 0.94f
        const val MESSAGE_WIDTH_DP = 560
    }
}
