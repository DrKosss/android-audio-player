package com.saptac.app

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class SaptacApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/* ------------------------------------------------------------------ *
 * Merne i grafičke pomoćne funkcije — preslikane iz CSS prototipa
 * ------------------------------------------------------------------ */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radiusDp: Float, ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

fun gradientCover(index: Int, radiusDp: Float, ctx: Context): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, COVERS[index % COVERS.size]).apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

fun ripple(content: Drawable?): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(Color.argb(38, 255, 255, 255)), content, ColorDrawable(Color.WHITE))

fun View.click(action: (View) -> Unit) {
    isClickable = true
    setOnClickListener { action(this) }
}

fun View.longClick(action: (View) -> Boolean) {
    isLongClickable = true
    setOnLongClickListener { action(this) }
}

fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
fun horizontal(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

fun space(ctx: Context, wDp: Int, hDp: Int = 0): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(ctx.dp(wDp), ctx.dp(hDp))
}

fun text(ctx: Context, value: CharSequence, sizeSp: Float, color: Int,
         bold: Boolean = false, serif: Boolean = false): TextView =
    TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold || serif) typeface = if (serif) Typeface.SERIF else Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
    }

fun icon(ctx: Context, resId: Int, color: Int = C_T2, sizeDp: Int = 22): ImageView =
    ImageView(ctx).apply {
        setImageResource(resId)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        setColorFilter(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

fun iconBox(ctx: Context, resId: Int, color: Int = C_T2, sizeDp: Int = 22,
            padDp: Int = 6, action: (View) -> Unit): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp + padDp * 2), ctx.dp(sizeDp + padDp * 2))
        background = ripple(rounded(C_S2, 10f, ctx))
        val iv = ImageView(ctx).apply {
            setImageResource(resId); setColorFilter(color); scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val p = FrameLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp), Gravity.CENTER)
        addView(iv, p)
        click(action)
    }

fun cover(ctx: Context, index: Int, initial: String, sizeDp: Int, radiusDp: Float,
          groupKey: String? = null, onEdit: (() -> Unit)? = null): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        background = gradientCover(index, radiusDp, ctx)
        val bmp = groupKey?.let { Bus.covers[it] ?: LibraryManager.customCoverBitmap(ctx, it) }
        if (bmp != null) {
            addView(ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(radiusDp).toFloat())
                }
            }
            clipToOutline = true
        } else {
            val t = text(ctx, initial, 16f, Color.argb(180, 255, 255, 255), serif = true)
            t.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            t.gravity = Gravity.CENTER
            addView(t, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        if (onEdit != null) longClick { onEdit(); true }
    }

fun rowView(ctx: Context, action: (View) -> Unit, longAction: ((View) -> Boolean)? = null): LinearLayout =
    horizontal(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(10), ctx.dp(9), ctx.dp(10), ctx.dp(9))
        minimumHeight = ctx.dp(60)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        background = ripple(rounded(C_TRANSPARENT, 14f, ctx))
        click(action)
        if (longAction != null) longClick(longAction)
    }

private val C_TRANSPARENT = Color.TRANSPARENT

fun pill(ctx: Context, label: String, active: Boolean): TextView =
    text(ctx, label, 12f, if (active) C_INK else C_T2, bold = active).apply {
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        background = rounded(if (active) C_AMBER else C_S2, 99f, ctx)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(6) }
        isClickable = true
    }

fun actionButton(ctx: Context, label: String, primary: Boolean, iconRes: Int? = null,
                 action: (View) -> Unit): LinearLayout =
    horizontal(ctx).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ctx.dp(42), 1f)
        background = ripple(rounded(if (primary) C_AMBER else C_S2, 14f, ctx))
        setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        if (iconRes != null) {
            val iv = icon(ctx, iconRes, if (primary) C_INK else C_T1, 15)
            (iv.layoutParams as LinearLayout.LayoutParams).marginEnd = ctx.dp(7)
            addView(iv)
        }
        addView(text(ctx, label, 13.5f, if (primary) C_INK else C_T1, bold = true))
        click(action)
    }

fun circleButton(ctx: Context, sizeDp: Int, bg: Int, iconRes: Int, iconColor: Int,
                 iconSizeDp: Int, action: (View) -> Unit): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        background = ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) })
        val iv = ImageView(ctx).apply {
            setImageResource(iconRes); setColorFilter(iconColor); scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(iv, FrameLayout.LayoutParams(ctx.dp(iconSizeDp), ctx.dp(iconSizeDp), Gravity.CENTER))
        click(action)
    }

/** Prototipska i-b30 / i-f30 ikona: kružna strelica + "30" u njenom središtu. */
fun jumpIcon(ctx: Context, forward: Boolean, sizeDp: Int, color: Int): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        val iv = ImageView(ctx).apply {
            setImageResource(if (forward) R.drawable.ic_f30 else R.drawable.ic_b30)
            setColorFilter(color); scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(iv, FrameLayout.LayoutParams(-1, -1))
        // SVG u prototipu ima font-size:7 u viewBox-u 24 -> razmera 7/24.
        val n = text(ctx, Store.settings.jump.toString(), sizeDp * 7f / 24f, color, bold = true)
        n.gravity = Gravity.CENTER
        addView(n, FrameLayout.LayoutParams(ctx.dp(sizeDp * 0.6f), ctx.dp(sizeDp * 0.4f),
            Gravity.CENTER).apply { topMargin = ctx.dp(sizeDp * 0.05f) })
    }

fun jumpButton(ctx: Context, sizeDp: Int, forward: Boolean, action: (View) -> Unit): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        addView(jumpIcon(ctx, forward, 40, C_T1),
            FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.CENTER))
        click(action)
    }

fun progressLine(ctx: Context, fraction: Float, topMarginDp: Int = 7): LinearLayout =
    horizontal(ctx).apply {
        val f = fraction.coerceIn(0f, 1f)
        layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(3)).apply { topMargin = ctx.dp(topMarginDp) }
        background = rounded(C_LINE, 2f, ctx)
        if (f > 0f) {
            addView(View(ctx).apply { background = rounded(C_AMBER, 2f, ctx) },
                LinearLayout.LayoutParams(0, -1, f))
            addView(View(ctx), LinearLayout.LayoutParams(0, -1, 1f - f))
        }
    }

fun seek(ctx: Context, maxMs: Long, posMs: Long, listener: SeekBar.OnSeekBarChangeListener): SeekBar =
    SeekBar(ctx).apply {
        max = (maxMs / 1000).toInt().coerceAtLeast(1)
        progress = (posMs / 1000).toInt().coerceIn(0, max)
        progressDrawable = ctx.getDrawable(R.drawable.seek_bg)
        thumb = ctx.getDrawable(R.drawable.seek_thumb)
        thumbOffset = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(26))
        setOnSeekBarChangeListener(listener)
    }

fun scroll(ctx: Context, child: View): ScrollView =
    ScrollView(ctx).apply {
        tag = "mainScroll"
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child, ViewGroup.LayoutParams(-1, -2))
    }

fun sectionHeader(ctx: Context, label: String): TextView =
    text(ctx, label, 10.5f, C_AMBER, bold = true).apply {
        setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(2))
        letterSpacing = 0.14f
    }

fun settingRow(ctx: Context, label: String, value: String? = null,
               switchOn: Boolean? = null, ok: Boolean = false,
               action: (View) -> Unit): LinearLayout =
    horizontal(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(46)
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        background = ripple(null)
        addView(text(ctx, label, 14f, C_T1),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (switchOn != null) addView(switchView(ctx, switchOn))
        else if (ok) {
            val h = horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            h.addView(icon(ctx, R.drawable.ic_check, C_AMBER, 14))
            h.addView(text(ctx, "urađeno", 12.5f, C_AMBER).apply {
                setPadding(ctx.dp(4), 0, 0, 0)
            })
            addView(h)
        } else if (value != null) addView(text(ctx, value, 13f, C_T2))
        click(action)
    }

fun switchView(ctx: Context, on: Boolean): FrameLayout =
    FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(21))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(11).toFloat()
            setColor(if (on) C_AMBER else C_LINE)
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) C_INK else C_T2)
            }
        }
        addView(dot, FrameLayout.LayoutParams(ctx.dp(15), ctx.dp(15), Gravity.CENTER_VERTICAL).apply {
            leftMargin = if (on) ctx.dp(18) else ctx.dp(3)
        })
    }
