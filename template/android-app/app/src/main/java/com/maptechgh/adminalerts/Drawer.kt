package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Floating slide-in menu: glowing gradient edge, curved header, a tile grid for
 * the work areas (with live counts) and a short list for app settings.
 */
class Drawer(private val a: Activity) {

    class Item(val label: String, val icon: Int, val color: Int, val count: Int = 0, val danger: Boolean = false, val onClick: () -> Unit)

    private var overlay: FrameLayout? = null
    private var panel: View? = null
    private var scrim: View? = null
    private var closing = false

    val isOpen: Boolean get() = overlay != null

    private val gap get() = a.dp(14)                 // room for the glow around the panel
    private val rBig get() = a.dp(34).toFloat()
    private val rSmall get() = a.dp(16).toFloat()
    private fun radii() = floatArrayOf(rSmall, rSmall, rBig, rBig, rBig, rBig, rSmall, rSmall)

    fun open(admin: JSONObject, liveOn: Boolean, work: List<Item>, links: List<Item>, footer: List<Item>) {
        if (overlay != null) return
        closing = false
        val t = C.THEME
        val dm = a.resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.74f).toInt(), a.dp(292))
        val glowColors = intArrayOf(C.GOLD, C.BLUE, C.PURPLE, C.GOLD)

        val root = FrameLayout(a).apply { isClickable = true; isFocusable = true }
        val sc = View(a).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0)); alpha = 0f
            setOnClickListener { close() }
        }
        root.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))

        // Holder = soft glow along the right edge + the full-height panel.
        val holder = FrameLayout(a).apply { clipChildren = false; clipToPadding = false }
        val glow = View(a).apply {
            background = InsetDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, glowColors).apply {
                cornerRadii = floatArrayOf(0f, 0f, rBig, rBig, rBig, rBig, 0f, 0f)
            }, 0, gap, gap, gap)
            alpha = if (t.light) 0.45f else 0.8f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(a.dp(12).toFloat(), a.dp(12).toFloat(), Shader.TileMode.DECAL))
            else alpha = 0.3f
        }
        holder.addView(glow, FrameLayout.LayoutParams(MATCH, MATCH))

        val p = vbox(a).apply {
            background = panelBackground(glowColors)
            clipToOutline = true
            isClickable = true
            setPadding(0, 0, a.dp(2), 0)
        }

        // ── Header ──
        val fg = t.heroText
        val head = FrameLayout(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (t.hero.size > 1) t.hero else intArrayOf(t.hero[0], t.hero[0])).apply {
                cornerRadii = floatArrayOf(0f, 0f, rBig, rBig, 0f, 0f, 0f, 0f)
            }
            clipChildren = true
        }
        head.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(alpha(fg, 26)) } },
            FrameLayout.LayoutParams(a.dp(150), a.dp(150), Gravity.TOP or Gravity.END).apply { topMargin = -a.dp(70); rightMargin = -a.dp(50) })
        head.addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(alpha(fg, 16)) } },
            FrameLayout.LayoutParams(a.dp(90), a.dp(90), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = -a.dp(30); rightMargin = a.dp(60) })
        val hc = vbox(a).apply { setPadding(a.dp(16), a.dp(18), a.dp(12), a.dp(28)) }
        val row = hbox(a)
        row.addView(logoView(a, 46))
        val names = vbox(a).apply { setPadding(a.dp(11), 0, a.dp(6), 0) }
        names.addView(tv(a, admin.optString("site_name", "MAPTECH GLOBAL").uppercase(Locale.US), 15f, fg, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = 0.03f
        })
        names.addView(tv(a, admin.optString("name").ifEmpty { "Admin" }, 12.5f, alpha(fg, 215)).apply { maxLines = 1 })
        row.addView(names, lp(0, WRAP, 1f))
        row.addView(FrameLayout(a).apply {
            background = ripple(rounded(alpha(fg, 40), a.dp(12).toFloat()))
            addView(icon(a, R.drawable.ic_close, 16, fg), FrameLayout.LayoutParams(a.dp(16), a.dp(16), Gravity.CENTER))
            setOnClickListener { close() }
        }, lp(a.dp(34), a.dp(34)))
        hc.addView(row, lp())
        val chips = hbox(a).apply { layoutParams = lp().margins(t = a.dp(12)) }
        chips.addView(pill(admin.optString("role_name", "Admin"), alpha(fg, 45), fg, null))
        chips.addView(pill(if (liveOn) "Live alerts on" else "Live alerts off", alpha(fg, 45), fg, if (liveOn) C.GREEN else C.RED).apply {
            layoutParams = lp(WRAP, WRAP).margins(l = a.dp(8))
        })
        hc.addView(chips)
        head.addView(hc, FrameLayout.LayoutParams(MATCH, WRAP))
        head.addView(Wave(a, innerColor()), FrameLayout.LayoutParams(MATCH, a.dp(18), Gravity.BOTTOM))
        p.addView(head, lp())

        // ── Body (scrolls if needed) ──
        val body = vbox(a).apply { setPadding(a.dp(10), 0, a.dp(10), a.dp(6)) }
        if (work.isNotEmpty()) { body.addView(caption("Work")); for (it in work) body.addView(row(it)) }
        if (links.isNotEmpty()) { body.addView(caption("App")); for (it in links) body.addView(row(it)) }
        p.addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, lp())
        }, lp(MATCH, 0, 1f))

        // ── Footer ──
        p.addView(View(a).apply { setBackgroundColor(alpha(C.TEXT, 22)) }, lp(MATCH, 1).margins(l = a.dp(16), r = a.dp(16)))
        val foot = hbox(a).apply { setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(6)) }
        footer.forEachIndexed { i, it -> foot.addView(footBtn(it), lp(0, a.dp(40), 1f).margins(l = if (i == 0) 0 else a.dp(8))) }
        p.addView(foot, lp())
        p.addView(tv(a, "v$APP_VERSION  •  by M-AVETECH IT SERVICES", 10.5f, alpha(C.MUTED, 200)).apply {
            gravity = Gravity.CENTER; setPadding(0, a.dp(2), 0, a.dp(12))
            setOnClickListener { openUrl(a, DEV_WEBSITE) }
        }, lp())

        holder.addView(p, FrameLayout.LayoutParams(w, MATCH))
        root.addView(holder, FrameLayout.LayoutParams(w + gap, MATCH, Gravity.START))
        enableSwipeClose(holder, w)

        a.addContentView(root, ViewGroup.LayoutParams(MATCH, MATCH))
        overlay = root; panel = holder; scrim = sc
        holder.translationX = -(w + gap).toFloat()
        holder.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
        sc.animate().alpha(1f).setDuration(260).start()
    }

    fun close(after: (() -> Unit)? = null) {
        val root = overlay ?: return
        if (closing) return
        closing = true
        val p = panel!!
        p.animate().translationX(-p.width.toFloat()).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        scrim?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            (root.parent as? ViewGroup)?.removeView(root)
            overlay = null; panel = null; scrim = null; closing = false
            after?.invoke()
        }?.start()
    }

    // ── Pieces ───────────────────────────────────────────────
    /** Solid inner colour (never see-through, so the home screen doesn't show through). */
    private fun innerColor(): Int {
        val t = C.THEME
        val bg = opaque(t.bgGradColors?.first() ?: t.bg)
        if (t.light) return Color.WHITE
        val sf = t.surface
        return if (Color.alpha(sf) > 200) blend(bg, opaque(sf), 0.6f) else blend(bg, Color.WHITE, 0.05f)
    }

    /** Gradient 2dp edge + solid inner fill, with the drawer's asymmetric shape. */
    private fun panelBackground(edge: IntArray): Drawable {
        val r = floatArrayOf(0f, 0f, rBig, rBig, rBig, rBig, 0f, 0f)
        val border = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, edge).apply { cornerRadii = r }
        val inner = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(innerColor(), blend(innerColor(), C.GOLD, if (C.LIGHT) 0.04f else 0.07f))).apply { cornerRadii = r }
        return LayerDrawable(arrayOf(border, inner)).apply { setLayerInset(1, 0, 0, a.dp(2), 0) }
    }

    private fun caption(text: String): TextView = tv(a, text.uppercase(Locale.US), 10f, C.MUTED, true).apply {
        letterSpacing = 0.16f; setPadding(a.dp(8), a.dp(10), 0, a.dp(4))
    }

    private fun row(it: Item): View {
        val r = hbox(a).apply {
            background = ripple(rounded(Color.TRANSPARENT, a.dp(14).toFloat()), alpha(it.color, 50))
            setPadding(a.dp(6), a.dp(5), a.dp(8), a.dp(5))
            minimumHeight = a.dp(48)
            setOnClickListener { _ -> close { it.onClick() } }
        }
        r.addView(badgeIcon(it.icon, it.color))
        r.addView(tv(a, it.label, 14.5f, C.TEXT, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(a.dp(13), 0, a.dp(6), 0)
        }, lp(0, WRAP, 1f))
        if (it.count > 0) r.addView(countBadge(it.count, it.color), lp(WRAP, WRAP).margins(r = a.dp(4)))
        else r.addView(icon(a, R.drawable.ic_chevron, 14, alpha(C.MUTED, 140)))
        return r.apply { layoutParams = lp().margins(b = a.dp(1)) }
    }

    /** Glossy gradient tile with a white icon and a soft coloured shadow. */
    private fun badgeIcon(res: Int, col: Int): View = FrameLayout(a).apply {
        val light = blend(col, Color.WHITE, 0.28f)
        val dark = blend(col, Color.BLACK, 0.30f)
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(light, col, dark)).apply { cornerRadius = a.dp(12).toFloat() }
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(70, 255, 255, 255), Color.TRANSPARENT)).apply {
            cornerRadii = floatArrayOf(a.dp(12).toFloat(), a.dp(12).toFloat(), a.dp(12).toFloat(), a.dp(12).toFloat(), 0f, 0f, 0f, 0f)
        }
        background = LayerDrawable(arrayOf(base, shine)).apply { setLayerInset(1, 0, 0, 0, a.dp(18)) }
        elevation = a.dp(5).toFloat()
        if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = col; outlineAmbientShadowColor = col }
        val fgc = if (col == C.YELLOW || col == C.GOLD) Color.parseColor("#1A1405") else Color.WHITE
        addView(icon(a, res, 18, fgc), FrameLayout.LayoutParams(a.dp(18), a.dp(18), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(a.dp(36), a.dp(36)).apply { setMargins(a.dp(2), a.dp(2), 0, a.dp(4)) }
    }

    private fun footBtn(it: Item): View {
        val col = if (it.danger) C.RED else it.color
        return hbox(a).apply {
            gravity = Gravity.CENTER
            background = ripple(rounded(alpha(col, if (C.LIGHT) 18 else 28), a.dp(20).toFloat(), alpha(col, 90), a.dp(1)))
            addView(icon(a, it.icon, 15, col))
            addView(tv(a, it.label, 12f, col, true).apply { maxLines = 1; setPadding(a.dp(6), 0, 0, 0) })
            setOnClickListener { _ -> close { it.onClick() } }
        }
    }

    private fun countBadge(n: Int, col: Int): TextView = TextView(a).apply {
        text = if (n > 99) "99+" else n.toString()
        textSize = 10.5f
        setTextColor(if (col == C.YELLOW || col == C.GOLD || col == C.ORANGE) Color.parseColor("#111111") else Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = a.dp(20)
        setPadding(a.dp(6), a.dp(1), a.dp(6), a.dp(1))
        background = rounded(col, a.dp(20).toFloat())
    }

    private fun pill(text: String, bg: Int, fg: Int, dotColor: Int?): LinearLayout = hbox(a).apply {
        background = rounded(bg, a.dp(20).toFloat())
        setPadding(a.dp(10), a.dp(4), a.dp(11), a.dp(4))
        if (dotColor != null) {
            addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } },
                lp(a.dp(7), a.dp(7)).margins(r = a.dp(6)))
        }
        addView(tv(a, text, 11f, fg, true).apply { maxLines = 1 })
    }

    /** Drag the drawer to the left to dismiss it. */
    private fun enableSwipeClose(v: View, w: Int) {
        var downX = 0f
        var dragging = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (dx < -a.dp(10)) dragging = true
                    if (dragging) { view.translationX = minOf(0f, dx); scrim?.alpha = (1f + view.translationX / w).coerceIn(0f, 1f) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (view.translationX < -w / 3f) close()
                        else { view.animate().translationX(0f).setDuration(160).start(); scrim?.animate()?.alpha(1f)?.setDuration(160)?.start() }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun opaque(c: Int) = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(c1: Int, c2: Int, f: Float): Int {
        fun ch(s: Int, e: Int) = (s + (e - s) * f).toInt()
        return Color.rgb(ch(Color.red(c1), Color.red(c2)), ch(Color.green(c1), Color.green(c2)), ch(Color.blue(c1), Color.blue(c2)))
    }

    /** Curved bottom edge of the header. */
    private class Wave(c: Context, private val color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Wave.color; style = Paint.Style.FILL }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            path.reset()
            path.moveTo(0f, h * 0.15f)
            path.cubicTo(w * 0.30f, h * 1.15f, w * 0.62f, -h * 0.35f, w, h * 0.55f)
            path.lineTo(w, h); path.lineTo(0f, h); path.close()
            canvas.drawPath(path, paint)
        }
    }
}
