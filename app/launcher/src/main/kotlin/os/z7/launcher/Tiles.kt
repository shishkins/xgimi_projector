package os.z7.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Плитки главного экрана. Фокус с пульта: карточка увеличивается и подсвечивается
 * (фон tile_bg реагирует на состояние focused родителя).
 * Только лёгкие анимации scale — проектор слабый, навигация должна идти на 60 fps.
 */
object Tiles {

    enum class Size(val widthDp: Int, val heightDp: Int) {
        /** верхний ряд избранного */
        BIG(256, 144),
        /** ряд управления и сетка всех приложений (6 в ряд на 1280 dp) */
        SMALL(160, 90),
    }

    fun app(context: Context, app: AppEntry, size: Size, onClick: () -> Unit): View {
        val card = card(context, size)
        if (app.banner != null) {
            card.addView(ImageView(context).apply {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = card.outlineProvider
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            card.addView(icon(context, app.icon, if (size == Size.BIG) 72 else 48))
        }
        return tile(context, card, app.label, onClick)
    }

    fun action(context: Context, iconRes: Int, label: String, onClick: () -> Unit): View {
        val card = card(context, Size.SMALL)
        card.addView(icon(context, context.getDrawable(iconRes)!!, 40))
        return tile(context, card, label, onClick)
    }

    private fun card(context: Context, size: Size) = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, size.widthDp), dp(context, size.heightDp))
        background = context.getDrawable(R.drawable.tile_bg)
        isDuplicateParentStateEnabled = true
        clipToOutline = true
    }

    private fun icon(context: Context, drawable: Drawable, sizeDp: Int) = ImageView(context).apply {
        setImageDrawable(drawable)
        layoutParams = FrameLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp), Gravity.CENTER)
    }

    private fun tile(context: Context, card: FrameLayout, label: String, onClick: () -> Unit): View {
        val text = TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            alpha = LABEL_IDLE
            layoutParams = LinearLayout.LayoutParams(card.layoutParams.width, WRAP).apply {
                topMargin = dp(context, 10)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            addView(card)
            addView(text)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, focused ->
                val s = if (focused) FOCUS_SCALE else 1f
                card.animate().scaleX(s).scaleY(s).translationZ(if (focused) dp(context, 12).toFloat() else 0f)
                    .setDuration(ANIM_MS).start()
                text.animate().alpha(if (focused) 1f else LABEL_IDLE).setDuration(ANIM_MS).start()
            }
        }
    }

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val FOCUS_SCALE = 1.08f
    private const val LABEL_IDLE = 0.6f
    private const val ANIM_MS = 120L
}
