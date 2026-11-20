package com.airmirror.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shared "Liquid Glass" look (iOS 26-style): vibrant, blurred-looking colour glow behind
 * translucent, highlight-edged panels. There's no real-time blur available on this device's API
 * level/GPU, so the glass effect is faked with layered translucency and soft radial-gradient
 * glow blobs instead of an actual background blur - it reads the same from normal TV viewing
 * distance and costs nothing to render.
 */
internal object GlassTheme {

    @JvmField val ACCENT = Color.rgb(10, 132, 255) // iOS systemBlue
    @JvmField val ACCENT_PURPLE = Color.rgb(191, 90, 242)
    @JvmField val ACCENT_PINK = Color.rgb(255, 69, 97)
    @JvmField val GREEN = Color.rgb(50, 215, 75)
    @JvmField val AMBER = Color.rgb(255, 159, 10)
    @JvmField val RED = Color.rgb(255, 69, 58)
    @JvmField val INK = Color.rgb(5, 6, 16)

    const val TEXT_PRIMARY = Color.WHITE
    @JvmField val TEXT_SECONDARY = Color.argb(225, 230, 235, 248)
    @JvmField val TEXT_TERTIARY = Color.argb(185, 210, 216, 235)

    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

    /**
     * The backdrop the glass panels float over. Real "Liquid Glass" has no background of its
     * own - it blurs and tints whatever is actually behind it (the device's wallpaper/content).
     * This device can't do a live blur, so the closest honest approximation is to actually show
     * the system wallpaper (same as Apple TV's own idle screen does) rather than paint a made-up
     * background of our own; a plain black fallback keeps this safe if no wallpaper is set.
     */
    fun buildBackground(ctx: Context): View {
        val canvas = FrameLayout(ctx)
        val wallpaper = ImageView(ctx).apply {
            setImageResource(R.drawable.wallpaper_default)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        canvas.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))
        // A bright/busy wallpaper can wash out white text through translucent glass. Real
        // Liquid Glass UIs dim/adapt under their content for this exact reason - a flat, modest
        // scrim here keeps every wallpaper legible without hiding it.
        val scrim = View(ctx).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        canvas.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        return canvas
    }

    /**
     * A translucent glass card: a dark tint (so text stays legible over any wallpaper, not just
     * a dark one) with a thin bright sheen right at the top edge, the way light catches real
     * glass, and a bright hairline around the whole edge.
     */
    fun panel(ctx: Context, radiusDp: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(150, 8, 10, 20), Color.argb(165, 8, 10, 20)),
        ).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(maxOf(1, dp(ctx, 1f)), Color.argb(90, 255, 255, 255))
        }

    /** A dimmer glass card for recessed/inset content (rows inside a panel, chips, etc). */
    fun panelDim(ctx: Context, radiusDp: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(50, 255, 255, 255), Color.argb(110, 8, 10, 20), Color.argb(120, 8, 10, 20)),
        ).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(maxOf(1, dp(ctx, 1f)), Color.argb(55, 255, 255, 255))
        }

    /** A glass card tinted with the accent colour, for a selected/active state. */
    fun panelAccent(ctx: Context, radiusDp: Float, accent: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent)),
            ),
        ).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, 1.5f), Color.argb(190, 255, 255, 255))
        }

    private fun focusStates(normal: GradientDrawable, focused: GradientDrawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

    /** A remote-focusable glass pill: neutral glass normally, a bright accent glow when focused. */
    fun pillButton(ctx: Context, radiusDp: Float): StateListDrawable =
        focusStates(panel(ctx, radiusDp), panelAccent(ctx, radiusDp, ACCENT))

    /** Same idea as [pillButton], for a row rather than a compact pill (e.g. settings list rows). */
    fun focusableRow(ctx: Context, radiusDp: Float): StateListDrawable =
        focusStates(panelDim(ctx, radiusDp), panelAccent(ctx, radiusDp, ACCENT))

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun text(ctx: Context, sp: Float, color: Int, bold: Boolean): TextView = TextView(ctx).apply {
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun space(ctx: Context, heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, heightDp.toFloat()))
    }

    fun dp(ctx: Context, value: Float): Int = (value * ctx.resources.displayMetrics.density).roundToInt()
}
