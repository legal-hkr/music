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
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withRotation
import kotlin.math.abs

/** Pictures the widgets are handed ready-made: a widget can't clip a view to a shape (SPEC-widgets §2.2). */
object WidgetBitmaps {
    /**
     * The shape the Playlist widgets cut mosaics into, in a unit square: MaterialShapes.Slanted as the
     * library draws it, dumped once (owner's choice after trying the handoff's 9-sided cookie and a
     * rounded square). The same path is res/drawable/widget_shape.xml, for the placeholders. The Now
     * playing widget's cover is a circle.
     */
    private const val SHAPE = "M0.8755,0.9140 C0.8677,0.9211 0.8593,0.9274 0.8504,0.9331 C0.8046,0.9620 0.7410,0.9618 0.6138,0.9614 C0.4765,0.9609 0.3392,0.9605 0.2020,0.9601 C0.1924,0.9600 0.1877,0.9600 0.1850,0.9599 C0.0806,0.9566 0.0000,0.8671 0.0075,0.7629 C0.0077,0.7602 0.0082,0.7555 0.0091,0.7460 C0.0228,0.6111 0.0365,0.4762 0.0501,0.3414 C0.0630,0.2148 0.0694,0.1515 0.1029,0.1090 C0.1160,0.0924 0.1318,0.0782 0.1496,0.0669 C0.1954,0.0380 0.2590,0.0382 0.3862,0.0386 C0.5235,0.0391 0.6608,0.0395 0.7980,0.0399 C0.8076,0.0400 0.8123,0.0400 0.8150,0.0401 C0.9194,0.0434 1.0000,0.1329 0.9925,0.2371 C0.9923,0.2398 0.9918,0.2445 0.9909,0.2540 C0.9772,0.3889 0.9635,0.5238 0.9499,0.6586 C0.9370,0.7852 0.9306,0.8485 0.8971,0.8910 C0.8905,0.8993 0.8833,0.9070 0.8755,0.9140 Z"

    /** The shape covers are cut into, [size] pixels across. */
    fun shapePath(size: Int): Path = PathParser.createPathFromPathData(SHAPE).apply {
        transform(Matrix().apply { setScale(size.toFloat(), size.toFloat()) })
    }

    /** [source] filling the shape (cropped to a square around its middle), transparent outside; [size] pixels across. */
    fun shaped(source: Bitmap, size: Int): Bitmap {
        val result = createBitmap(size, size)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
            },
        )
        Canvas(result).drawPath(shapePath(size), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        return result
    }

    /** [source] filling a circle [size] pixels across (the Now playing widget's cover, the Covers look's round mosaic). */
    fun circle(source: Bitmap, size: Int): Bitmap {
        val result = createBitmap(size, size)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / minOf(source.width, source.height)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                // The middle of a picture that isn't square
                postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
            },
        )
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

    /** How a fan is drawn (SPEC-widgets §4.1), in pixels. */
    class Fan(val width: Int, val height: Int, val card: Float, val step: Float, val radius: Float, val drop: Float, val shadowBlur: Float, val shadowOffset: Float, val shadowAlpha: Int)

    /**
     * Covers fanned out, transparent around them: the first of [cards] in the middle and on top, the
     * next ones alternately to its right and left, each a step further out, 9° more tilted outwards,
     * a little lower and under the one before; the whole fan in the middle of the picture. A card
     * without a cover is plain [placeholder], and [note], if given, is drawn on the first of them.
     */
    fun fan(cards: List<Bitmap?>, fan: Fan, placeholder: Int, note: Drawable?): Bitmap {
        val result = createBitmap(fan.width, fan.height)
        val canvas = Canvas(result)
        // Places counted from the middle: 0, +1, −1, +2, −2
        val places = cards.indices.map { i -> if (i == 0) 0 else if (i % 2 == 1) (i + 1) / 2 else -(i / 2) }
        // With an even number of cards the fan leans to one side: brought back to the middle
        val shift = -(places.minOrNull() ?: 0).plus(places.maxOrNull() ?: 0) / 2f * fan.step
        val top = (fan.height - fan.card - fan.drop * (places.maxOfOrNull { abs(it) } ?: 0)) / 2f
        for (i in cards.indices.reversed()) {
            val place = places[i]
            val left = fan.width / 2f - fan.card / 2f + place * fan.step + shift
            val y = top + abs(place) * fan.drop
            canvas.withRotation(place * FAN_TILT, left + fan.card / 2f, y + fan.card / 2f) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.setShadowLayer(fan.shadowBlur, 0f, fan.shadowOffset, Color.argb(fan.shadowAlpha, 0, 0, 0))
                val cover = cards[i]
                if (cover != null) {
                    val scale = fan.card / minOf(cover.width, cover.height)
                    paint.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(
                            Matrix().apply {
                                setScale(scale, scale)
                                postTranslate(left + (fan.card - cover.width * scale) / 2f, y + (fan.card - cover.height * scale) / 2f)
                            },
                        )
                    }
                } else {
                    paint.color = placeholder
                }
                drawRoundRect(left, y, left + fan.card, y + fan.card, fan.radius, fan.radius, paint)
                if (cover == null && note != null && i == 0) {
                    val inset = (fan.card * 0.32f).toInt()
                    note.setBounds(left.toInt() + inset, y.toInt() + inset, (left + fan.card).toInt() - inset, (y + fan.card).toInt() - inset)
                    note.draw(this)
                }
            }
        }
        return result
    }

    private const val FAN_TILT = 9f

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
