package com.airmirror.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
 * Google Material Design, light ("white") theme: white surfaces on a near-white background,
 * Google Blue accents, soft elevation shadows and Google Material icons. TV remote focus is
 * shown the Material way: a blue-tinted fill with a blue outline.
 */
internal object MaterialStyle {

    // Google Material palette
    @JvmField val BACKGROUND = Color.rgb(248, 249, 250) // Grey 50
    const val SURFACE = Color.WHITE
    @JvmField val SURFACE_VARIANT = Color.rgb(241, 243, 244) // Grey 100
    @JvmField val OUTLINE = Color.rgb(218, 220, 224) // Grey 300
    @JvmField val PRIMARY = Color.rgb(26, 115, 232) // Blue 600
    @JvmField val PRIMARY_DARK = Color.rgb(25, 103, 210) // Blue 700
    @JvmField val PRIMARY_PRESSED = Color.rgb(21, 87, 176) // Blue 800
    @JvmField val PRIMARY_CONTAINER = Color.rgb(232, 240, 254) // Blue 50
    @JvmField val PRIMARY_RING = Color.rgb(138, 180, 248) // Blue 300
    @JvmField val GREEN = Color.rgb(30, 142, 62) // Green 600
    @JvmField val GREEN_CONTAINER = Color.rgb(230, 244, 234)
    @JvmField val AMBER = Color.rgb(227, 116, 0) // Orange 700 (readable on white)
    @JvmField val AMBER_CONTAINER = Color.rgb(254, 239, 227)
    @JvmField val RED = Color.rgb(217, 48, 37) // Red 600
    @JvmField val RED_CONTAINER = Color.rgb(252, 232, 230)

    @JvmField val TEXT_PRIMARY = Color.rgb(32, 33, 36) // Grey 900
    @JvmField val TEXT_SECONDARY = Color.rgb(95, 99, 104) // Grey 700
    @JvmField val TEXT_TERTIARY = Color.rgb(128, 134, 139) // Grey 600

    /** Roboto Medium, the Material weight for titles and buttons. */
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(ctx: Context, value: Float): Int = (value * ctx.resources.displayMetrics.density).roundToInt()

    fun rounded(ctx: Context, radiusDp: Float, fill: Int, strokeDp: Float = 0f, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0f) setStroke(maxOf(1, dp(ctx, strokeDp)), stroke)
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** A white Material card with a hairline outline; pair with [elevate] for its shadow. */
    fun card(ctx: Context, radiusDp: Float): GradientDrawable = rounded(ctx, radiusDp, SURFACE, 1f, OUTLINE)

    /** Real Material elevation shadow (the view's background supplies the outline shape). */
    fun elevate(ctx: Context, view: View, elevationDp: Float) {
        view.elevation = dp(ctx, elevationDp).toFloat()
    }

    private fun states(normal: Drawable, focused: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

    /** Material focus for a list row / card: blue-tinted fill and a blue outline. */
    fun focusableRow(ctx: Context, radiusDp: Float, normalFill: Int = SURFACE, normalStroke: Int = OUTLINE): StateListDrawable =
        states(
            rounded(ctx, radiusDp, normalFill, 1f, normalStroke),
            rounded(ctx, radiusDp, PRIMARY_CONTAINER, 2f, PRIMARY),
        )

    /** Filled (primary) button background. */
    fun filledButton(ctx: Context, radiusDp: Float): StateListDrawable =
        states(
            rounded(ctx, radiusDp, PRIMARY),
            rounded(ctx, radiusDp, PRIMARY_PRESSED, 3f, PRIMARY_RING),
        )

    /** Tonal button background: light blue normally, solid blue when focused. */
    fun tonalButton(ctx: Context, radiusDp: Float): StateListDrawable =
        states(
            rounded(ctx, radiusDp, PRIMARY_CONTAINER),
            rounded(ctx, radiusDp, PRIMARY, 3f, PRIMARY_RING),
        )

    /** Outlined button background. */
    fun outlinedButton(ctx: Context, radiusDp: Float): StateListDrawable =
        states(
            rounded(ctx, radiusDp, SURFACE, 1f, OUTLINE),
            rounded(ctx, radiusDp, PRIMARY_CONTAINER, 2f, PRIMARY),
        )

    /** Colour that switches when the (parent) view is focused or pressed. */
    fun focusColors(normal: Int, focused: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        ),
        intArrayOf(focused, focused, normal),
    )

    fun text(ctx: Context, sp: Float, color: Int, medium: Boolean = false): TextView = TextView(ctx).apply {
        textSize = sp
        setTextColor(color)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = if (medium) MEDIUM else Typeface.DEFAULT
        includeFontPadding = false
    }

    /** A Google Material icon, tinted. */
    fun icon(ctx: Context, res: Int, tint: ColorStateList): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = tint
        scaleType = ImageView.ScaleType.FIT_CENTER
        isDuplicateParentStateEnabled = true
    }

    fun icon(ctx: Context, res: Int, tint: Int): ImageView = icon(ctx, res, ColorStateList.valueOf(tint))

    /** A Material icon centred in a tinted circle (leading avatars, step markers, dialog headers). */
    fun circleIcon(ctx: Context, res: Int, circleDp: Float, iconDp: Float, fill: Int, tint: Int): View =
        FrameLayout(ctx).apply {
            background = oval(fill)
            val size = dp(ctx, iconDp)
            addView(icon(ctx, res, tint), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, circleDp), dp(ctx, circleDp))
        }

    /**
     * A remote-focusable Material button with a leading icon. `filled` is the primary action;
     * otherwise it is a tonal button that turns solid blue when focused.
     */
    fun iconButton(ctx: Context, iconRes: Int?, label: CharSequence, filled: Boolean, radiusDp: Float = 24f): LinearLayout {
        val content = if (filled) {
            ColorStateList.valueOf(Color.WHITE)
        } else {
            focusColors(PRIMARY_DARK, Color.WHITE)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = if (filled) filledButton(ctx, radiusDp) else tonalButton(ctx, radiusDp)
            setPadding(dp(ctx, if (iconRes != null) 20f else 28f), dp(ctx, 12f), dp(ctx, 28f), dp(ctx, 12f))
            if (iconRes != null) {
                val size = dp(ctx, 22f)
                addView(icon(ctx, iconRes, content), LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dp(ctx, 10f)
                })
            }
            addView(text(ctx, 17f, Color.WHITE, medium = true).apply {
                text = label
                setTextColor(content)
                isDuplicateParentStateEnabled = true
            })
        }
    }

    fun space(ctx: Context, heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, heightDp.toFloat()))
    }
}
