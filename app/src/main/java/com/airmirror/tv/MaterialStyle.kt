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
 * Google Material Design in a light ("white") or dark theme: cards on a plain background,
 * Google Blue accents, soft elevation shadows and Google Material icons. TV remote focus is
 * shown the Material way: a blue-tinted fill with a blue outline.
 *
 * Colours are read from the active [Palette]; call [apply] before building views.
 */
internal object MaterialStyle {

    /** One full set of Material colour roles. */
    class Palette(
        val background: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val outline: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryDark: Int,
        val primaryPressed: Int,
        val primaryContainer: Int,
        val primaryRing: Int,
        val green: Int,
        val greenContainer: Int,
        val amber: Int,
        val amberContainer: Int,
        val red: Int,
        val redContainer: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val dialogStyle: Int,
    )

    /** Google Material light palette. */
    val LIGHT = Palette(
        background = Color.rgb(248, 249, 250), // Grey 50
        surface = Color.WHITE,
        surfaceVariant = Color.rgb(241, 243, 244), // Grey 100
        outline = Color.rgb(218, 220, 224), // Grey 300
        primary = Color.rgb(26, 115, 232), // Blue 600
        onPrimary = Color.WHITE,
        primaryDark = Color.rgb(25, 103, 210), // Blue 700
        primaryPressed = Color.rgb(21, 87, 176), // Blue 800
        primaryContainer = Color.rgb(232, 240, 254), // Blue 50
        primaryRing = Color.rgb(138, 180, 248), // Blue 300
        green = Color.rgb(30, 142, 62), // Green 600
        greenContainer = Color.rgb(230, 244, 234),
        amber = Color.rgb(227, 116, 0), // Orange 700 (readable on white)
        amberContainer = Color.rgb(254, 239, 227),
        red = Color.rgb(217, 48, 37), // Red 600
        redContainer = Color.rgb(252, 232, 230),
        textPrimary = Color.rgb(32, 33, 36), // Grey 900
        textSecondary = Color.rgb(95, 99, 104), // Grey 700
        textTertiary = Color.rgb(128, 134, 139), // Grey 600
        dialogStyle = R.style.AppDialog,
    )

    /** Google Material dark palette (Grey 900 background, Blue 200 accents). */
    val DARK = Palette(
        background = Color.rgb(32, 33, 36), // Grey 900
        surface = Color.rgb(41, 42, 45),
        surfaceVariant = Color.rgb(60, 64, 67), // Grey 800
        outline = Color.rgb(77, 81, 86),
        primary = Color.rgb(138, 180, 248), // Blue 300
        onPrimary = Color.rgb(32, 33, 36),
        primaryDark = Color.rgb(174, 203, 250), // Blue 200
        primaryPressed = Color.rgb(174, 203, 250), // Blue 200
        primaryContainer = Color.rgb(45, 59, 84),
        primaryRing = Color.rgb(210, 227, 252), // Blue 100
        green = Color.rgb(129, 201, 149), // Green 300
        greenContainer = Color.rgb(36, 58, 44),
        amber = Color.rgb(253, 214, 99), // Yellow 300
        amberContainer = Color.rgb(64, 54, 26),
        red = Color.rgb(242, 139, 130), // Red 300
        redContainer = Color.rgb(70, 40, 38),
        textPrimary = Color.rgb(232, 234, 237), // Grey 200
        textSecondary = Color.rgb(189, 193, 198), // Grey 400
        textTertiary = Color.rgb(154, 160, 166), // Grey 500
        dialogStyle = R.style.AppDialogDark,
    )

    @Volatile
    var palette: Palette = LIGHT
        private set

    val isDark: Boolean
        get() = palette === DARK

    /** Picks the palette for a theme setting ([THEME_LIGHT], [THEME_DARK] or [THEME_AUTO]). */
    fun apply(ctx: Context, themeSetting: String) {
        val systemDark = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        palette = when (themeSetting) {
            THEME_DARK -> DARK
            THEME_AUTO -> if (systemDark) DARK else LIGHT
            else -> LIGHT
        }
    }

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_AUTO = "auto"

    // Colour roles of the active palette.
    val BACKGROUND get() = palette.background
    val SURFACE get() = palette.surface
    val SURFACE_VARIANT get() = palette.surfaceVariant
    val OUTLINE get() = palette.outline
    val PRIMARY get() = palette.primary
    val ON_PRIMARY get() = palette.onPrimary
    val PRIMARY_DARK get() = palette.primaryDark
    val PRIMARY_PRESSED get() = palette.primaryPressed
    val PRIMARY_CONTAINER get() = palette.primaryContainer
    val PRIMARY_RING get() = palette.primaryRing
    val GREEN get() = palette.green
    val GREEN_CONTAINER get() = palette.greenContainer
    val AMBER get() = palette.amber
    val AMBER_CONTAINER get() = palette.amberContainer
    val RED get() = palette.red
    val RED_CONTAINER get() = palette.redContainer
    val TEXT_PRIMARY get() = palette.textPrimary
    val TEXT_SECONDARY get() = palette.textSecondary
    val TEXT_TERTIARY get() = palette.textTertiary
    val DIALOG_STYLE get() = palette.dialogStyle

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
            ColorStateList.valueOf(ON_PRIMARY)
        } else {
            focusColors(PRIMARY_DARK, ON_PRIMARY)
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
            addView(text(ctx, 17f, ON_PRIMARY, medium = true).apply {
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
