package com.example.qsdpi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

internal fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
internal fun View.dp(v: Float): Float = context.dp(v)

internal object Ui {
    val BG = 0xFF090A16.toInt()
    val CYAN = 0xFF22D3EE.toInt()
    val INDIGO = 0xFF6366F1.toInt()
    val VIOLET = 0xFFA855F7.toInt()
    val PINK = 0xFFF472B6.toInt()
    val GREEN = 0xFF34D399.toInt()
    val RED = 0xFFF87171.toInt()
    val GRAY = 0xFF8A8FA3.toInt()

    /** cyan -> indigo -> violet -> pink */
    val RAMP = intArrayOf(CYAN, INDIGO, VIOLET, PINK)

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    fun ramp(t: Float): Int {
        val s = t.coerceIn(0f, 1f) * (RAMP.size - 1)
        val i = s.toInt().coerceAtMost(RAMP.size - 2)
        return lerp(RAMP[i], RAMP[i + 1], s - i)
    }

    /** Frosted-glass card background. */
    fun glass(ctx: Context, radiusDp: Float, strong: Boolean = false): GradientDrawable {
        val density = ctx.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(alpha(Color.WHITE, if (strong) 0x34 else 0x24), alpha(Color.WHITE, 0x0C)),
        ).apply {
            cornerRadius = radiusDp * density
            setStroke(maxOf(1, density.toInt()), alpha(Color.WHITE, 0x30))
        }
    }
}
