package sh.hnet.music.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap

/** Pictures the widgets are handed ready-made: a widget can't clip a view to a shape (SPEC-widgets §2.2). */
object WidgetBitmaps {
    /**
     * The Material Expressive 9-sided cookie in a unit square: MaterialShapes.Cookie9Sided as the
     * library draws it, the same path as res/drawable/widget_cookie.xml.
     */
    private const val COOKIE = "M0.5000,0.0142 C0.5365,0.0142 0.5730,0.0273 0.6019,0.0535 C0.6313,0.0801 0.6700,0.0942 0.7096,0.0927 C0.7876,0.0897 0.8551,0.1463 0.8657,0.2237 C0.8711,0.2630 0.8916,0.2986 0.9230,0.3229 C0.9847,0.3708 1.0000,0.4576 0.9584,0.5236 C0.9372,0.5572 0.9301,0.5977 0.9385,0.6365 C0.9550,0.7128 0.9109,0.7891 0.8366,0.8130 C0.7988,0.8251 0.7673,0.8515 0.7488,0.8867 C0.7124,0.9557 0.6296,0.9858 0.5573,0.9563 C0.5206,0.9413 0.4794,0.9413 0.4427,0.9563 C0.3704,0.9858 0.2876,0.9557 0.2512,0.8867 C0.2327,0.8515 0.2012,0.8251 0.1634,0.8130 C0.0891,0.7891 0.0450,0.7128 0.0615,0.6365 C0.0699,0.5977 0.0628,0.5572 0.0416,0.5236 C0.0000,0.4576 0.0153,0.3708 0.0770,0.3229 C0.1084,0.2986 0.1289,0.2630 0.1343,0.2237 C0.1449,0.1463 0.2124,0.0897 0.2904,0.0927 C0.3300,0.0942 0.3687,0.0801 0.3981,0.0535 C0.4270,0.0273 0.4635,0.0142 0.5000,0.0142 Z"

    /** The cookie [size] pixels across. */
    fun cookiePath(size: Int): Path = PathParser.createPathFromPathData(COOKIE).apply {
        transform(Matrix().apply { setScale(size.toFloat(), size.toFloat()) })
    }

    /** [source] filling the cookie (cropped to a square around its middle), transparent outside; [size] pixels across. */
    fun cookie(source: Bitmap, size: Int): Bitmap {
        val result = createBitmap(size, size)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
            },
        )
        Canvas(result).drawPath(cookiePath(size), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return result
    }
}
