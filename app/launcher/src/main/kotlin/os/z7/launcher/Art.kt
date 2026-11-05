package os.z7.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * Цвета главного экрана из картинок приложений: фон квадратной плитки под баннер
 * и «подсветка» всего экрана в цвет выбранного приложения. Без размытия (на Android 9
 * оно дорогое) — плавный градиент из одного цвета выглядит так же мягко.
 */
object Art {

    /** Цвет фона баннера: угол картинки (логотип у TV-баннеров стоит по центру на сплошном фоне). */
    fun bannerBackdrop(banner: Drawable): Int {
        val bmp = render(banner, 32, 18)
        return opaque(bmp.getPixel(1, 1)).also { bmp.recycle() }
    }

    /** Средний цвет непрозрачных пикселей значка. */
    fun average(icon: Drawable): Int {
        val bmp = render(icon, 12, 12)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        bmp.recycle()
        if (n == 0L) return FALLBACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Цвет для подсветки экрана: насыщенный, но тёмный — белый текст поверх читается. */
    fun glow(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        // белые и серые баннеры (YouTube) дают серую подсветку — уводим в холодный синий
        if (hsv[1] < 0.15f) {
            hsv[0] = 222f
            hsv[1] = 0.35f
        }
        hsv[1] = hsv[1].coerceAtMost(0.85f)
        hsv[2] = 0.42f
        return Color.HSVToColor(hsv)
    }

    /** Фон экрана: свет из правого верхнего угла в почти чёрный. */
    fun wash(color: Int): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TR_BL,
        intArrayOf(glow(color), blend(glow(color), BASE, 0.55f), BASE),
    )

    /** Тёмный фон плитки для приложений без баннера. */
    fun tileBackdrop(color: Int): Int = blend(color, BASE, 0.45f)

    fun isLight(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 186

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun opaque(c: Int) = if (Color.alpha(c) < 200) FALLBACK else c or 0xFF000000.toInt()

    private fun render(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copy = d.constantState?.newDrawable()?.mutate() ?: d
        copy.setBounds(0, 0, w, h)
        copy.draw(Canvas(bmp))
        return bmp
    }

    private val BASE = Color.rgb(3, 5, 12)
    private val FALLBACK = Color.rgb(28, 40, 70)
}
