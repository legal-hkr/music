package sh.hnet.music.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.abs

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

    /** [source] filling a circle [size] pixels across (the Playlist bar's round mosaic). */
    fun circle(source: Bitmap, size: Int): Bitmap {
        val result = createBitmap(size, size)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / minOf(source.width, source.height)
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        Canvas(result).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return result
    }

    /**
     * A playlist's picture as the Library shows it (SPEC-playlists §4.2): four covers as a 2 × 2 mosaic,
     * or one filling it; a tile whose track has no cover is plain [tile]. Square, [size] pixels across.
     */
    fun mosaic(covers: List<Bitmap?>, size: Int, tile: Int): Bitmap {
        val result = createBitmap(size, size)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(tile)
        fun draw(cover: Bitmap?, left: Int, top: Int, side: Int) {
            cover ?: return
            // The middle square of the cover
            val crop = minOf(cover.width, cover.height)
            val x = (cover.width - crop) / 2
            val y = (cover.height - crop) / 2
            canvas.drawBitmap(cover, Rect(x, y, x + crop, y + crop), Rect(left, top, left + side, top + side), paint)
        }
        if (covers.size == 4) {
            val half = size / 2
            covers.forEachIndexed { i, cover -> draw(cover, i % 2 * half, i / 2 * half, size - half) }
        } else {
            draw(covers.firstOrNull(), 0, 0, size)
        }
        return result
    }

    /**
     * A strip rising from nothing into [color] at 85 %, stretched over the bottom of the Covers look's
     * picture, under the text (SPEC-widgets §5.1). Its own view rather than part of the picture: the
     * picture is cropped to the widget's shape, and the scrim must stay where the text is.
     */
    fun scrim(color: Int): Bitmap {
        val result = createBitmap(1, FADE_STEPS)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, FADE_STEPS.toFloat(), ColorUtils.setAlphaComponent(color, 0), ColorUtils.setAlphaComponent(color, 217), Shader.TileMode.CLAMP)
        }
        Canvas(result).drawRect(0f, 0f, 1f, FADE_STEPS.toFloat(), paint)
        return result
    }

    /** A strip fading from nothing into [color], stretched over the end of the mosaic where it meets the panel. */
    fun fade(color: Int): Bitmap {
        val result = createBitmap(FADE_STEPS, 1)
        val paint = Paint().apply {
            // Most of the way by the middle: the text starts over the strip's second half
            shader = LinearGradient(
                0f, 0f, FADE_STEPS.toFloat(), 0f,
                intArrayOf(ColorUtils.setAlphaComponent(color, 0), ColorUtils.setAlphaComponent(color, 217), color),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(result).drawRect(0f, 0f, FADE_STEPS.toFloat(), 1f, paint)
        return result
    }

    private const val FADE_STEPS = 64
}

/**
 * The Covers look's colors (SPEC-widgets §5.1), all worked out from the playlist's picture and none
 * written in (owner's rule): a dark panel in the picture's own hue, light text on it, and an accent
 * from its most vivid part for Play.
 */
class CoverColors(val panel: Int, val onPanel: Int, val onPanelMuted: Int, val chip: Int, val accent: Int, val onAccent: Int) {
    companion object {
        /** From [pictures] taken together; with none, from [fallback] (a system color role), so there still is a hue. */
        fun of(pictures: List<Bitmap>, fallback: Int): CoverColors {
            val pixels = if (pictures.isEmpty()) intArrayOf(fallback) else IntArray(SAMPLE * SAMPLE * pictures.size).also { all ->
                pictures.forEachIndexed { i, picture -> picture.scale(SAMPLE, SAMPLE).getPixels(all, i * SAMPLE * SAMPLE, SAMPLE, 0, 0, SAMPLE, SAMPLE) }
            }
            var r = 0L
            var g = 0L
            var b = 0L
            val hsl = FloatArray(3)
            val vivid = FloatArray(3)
            var best = -1f
            for (pixel in pixels) {
                r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel)
                ColorUtils.colorToHSL(pixel, hsl)
                // Saturated and neither nearly black nor nearly white
                val score = hsl[1] * (1f - abs(hsl[2] - 0.5f) * 2f)
                if (score > best) { best = score; hsl.copyInto(vivid) }
            }
            val n = pixels.size
            ColorUtils.colorToHSL(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()), hsl)
            val hue = hsl[0]
            val panel = ColorUtils.HSLToColor(floatArrayOf(hue, hsl[1].coerceAtMost(0.45f), 0.16f))
            val onPanel = ColorUtils.HSLToColor(floatArrayOf(hue, hsl[1].coerceAtMost(0.25f), 0.95f))
            return CoverColors(
                panel = panel,
                onPanel = onPanel,
                onPanelMuted = ColorUtils.blendARGB(panel, onPanel, 0.7f),
                chip = ColorUtils.blendARGB(panel, onPanel, 0.16f),
                accent = ColorUtils.HSLToColor(floatArrayOf(vivid[0], vivid[1].coerceIn(0.55f, 0.85f), 0.74f)),
                onAccent = ColorUtils.HSLToColor(floatArrayOf(vivid[0], 0.5f, 0.14f)),
            )
        }

        private const val SAMPLE = 24
    }
}
