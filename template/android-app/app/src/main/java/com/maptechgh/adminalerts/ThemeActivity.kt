package com.maptechgh.adminalerts

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * All 10 themes. Tap a theme to use it; tap ★ to put it in the quick
 * switcher on the home screen (up to 5).
 */
class ThemeActivity : Activity() {

    private lateinit var s: Screen

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        s = Screen(this, "Themes", "Tap to apply • ★ = show in quick switcher (max ${Themes.MAX_QUICK})")
        build()
    }

    private fun build() {
        val b = s.body
        b.removeAllViews()
        val quick = Prefs.quickThemes(this).toMutableList()
        val cur = Prefs.themeId(this)

        val info = card(this)
        info.addView(tv(this, "Quick switcher: ${quick.size} of ${Themes.MAX_QUICK}", 14.5f, C.TEXT, true))
        info.addView(tv(this, "Starred themes appear as round buttons at the top of the home screen so you can switch with one tap.", 12.5f, C.MUTED))
        b.addView(info)

        for (th in Themes.all) {
            val selected = th.id == cur
            val starred = th.id in quick
            val c = card(this, if (selected) C.GOLD else null)
            if (selected) c.background = rounded(C.SURFACE, dp(C.R).toFloat(), C.GOLD, dp(2))
            val row = hbox(this).apply { gravity = Gravity.TOP }
            row.addView(preview(th), lp(dp(92), dp(150)))
            val tx = vbox(this).apply { setPadding(dp(14), 0, 0, 0) }
            val head = hbox(this)
            head.addView(tv(this, "${th.id}. ${th.name}", 16f, C.TEXT, true), lp(0, WRAP, 1f))
            val star = FrameLayout(this).apply {
                background = ripple(rounded(if (starred) alpha(C.GOLD, 40) else C.SURFACE2, dp(12).toFloat(), if (starred) C.GOLD else C.STROKE, dp(1)))
                addView(icon(this@ThemeActivity, R.drawable.ic_star, 18, if (starred) C.GOLD else C.MUTED), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                setOnClickListener {
                    if (starred) {
                        quick.remove(th.id)
                    } else {
                        if (quick.size >= Themes.MAX_QUICK) {
                            toast(this@ThemeActivity, "The quick switcher holds ${Themes.MAX_QUICK}. Remove a star first.")
                            return@setOnClickListener
                        }
                        quick.add(th.id)
                    }
                    Prefs.setQuickThemes(this@ThemeActivity, quick)
                    build()
                }
            }
            head.addView(star, lp(dp(38), dp(38)))
            tx.addView(head, lp())
            tx.addView(badge(this, th.layout.label, C.BLUE).apply { layoutParams = lp(WRAP, WRAP).margins(t = dp(4), b = dp(6)) })
            tx.addView(tv(this, th.desc, 12.5f, C.MUTED))
            tx.addView(swatches(th), lp().margins(t = dp(10)))
            tx.addView(if (selected) tv(this, "✓ In use", 13.5f, C.GOLD, true).apply { setPadding(0, dp(10), 0, 0) }
                       else btn(this, "Use this theme", BTN_SECONDARY) { apply(th) }.apply { layoutParams = lp().margins(t = dp(10)) })
            row.addView(tx, lp(0, WRAP, 1f))
            c.addView(row, lp())
            c.setOnClickListener { if (!selected) apply(th) }
            b.addView(c)
        }
        b.addView(creditsCard(this))
    }

    private fun apply(th: AppTheme) {
        Prefs.setThemeId(this, th.id)
        toast(this, "${th.name} applied")
        recreate()
    }

    private fun swatches(th: AppTheme): LinearLayout {
        val r = hbox(this)
        for (col in listOf(th.bg, th.surface, th.accent, th.red, th.orange, th.blue, th.green)) {
            r.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(dp(1), alpha(C.TEXT, 50)) }
            }, lp(dp(16), dp(16)).margins(r = dp(5)))
        }
        return r
    }

    /** A tiny phone-screen mock in the theme's own colours and layout. */
    private fun preview(th: AppTheme): View {
        val r = dp(6).toFloat()
        val phone = vbox(this).apply {
            background = if (th.bgGradColors != null) GradientDrawable(GradientDrawable.Orientation.TL_BR, th.bgGradColors).apply { cornerRadius = dp(14).toFloat(); setStroke(dp(2), alpha(C.TEXT, 40)) }
                         else GradientDrawable().apply { setColor(th.bg); cornerRadius = dp(14).toFloat(); setStroke(dp(2), alpha(C.TEXT, 40)) }
            setPadding(dp(6), dp(8), dp(6), dp(6))
            clipToOutline = true
        }
        fun block(color: Int, h: Int, stroke: Int? = null): View = View(this).apply {
            background = GradientDrawable().apply { setColor(color); cornerRadius = r; if (stroke != null) setStroke(dp(1), stroke) }
            layoutParams = lp(MATCH, dp(h)).margins(b = dp(4))
        }
        fun heroBlock(h: Int): View = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (th.hero.size > 1) th.hero else intArrayOf(th.hero[0], th.hero[0])).apply { cornerRadius = r }
            layoutParams = lp(MATCH, dp(h)).margins(b = dp(4))
        }
        fun tileRow(colors: List<Int>): LinearLayout = hbox(this).apply {
            colors.forEachIndexed { i, col ->
                addView(View(this@ThemeActivity).apply {
                    background = GradientDrawable().apply { setColor(th.surface); cornerRadius = r; setStroke(dp(1), col) }
                }, lp(0, dp(20), 1f).margins(r = if (i < colors.size - 1) dp(4) else 0))
            }
            layoutParams = lp().margins(b = dp(4))
        }
        // logo row
        val top = hbox(this).apply { layoutParams = lp().margins(b = dp(5)) }
        top.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_full); background = rounded(Color.WHITE, dp(3).toFloat()); setPadding(dp(1), dp(1), dp(1), dp(1))
        }, lp(dp(12), dp(12)))
        top.addView(View(this).apply { background = rounded(th.text, dp(2).toFloat()) }, lp(dp(34), dp(4)).margins(l = dp(4)))
        when (th.layout) {
            HomeLayout.BANNER -> {
                phone.setPadding(0, 0, 0, dp(6))
                val ban = vbox(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, th.hero)
                    setPadding(dp(6), dp(8), dp(6), dp(10))
                }
                ban.addView(top)
                ban.addView(View(this).apply { background = rounded(th.heroText, dp(2).toFloat()) }, lp(dp(22), dp(10)))
                phone.addView(ban, lp(MATCH, dp(46)))
                val body = vbox(this).apply { setPadding(dp(6), dp(4), dp(6), 0) }
                body.addView(block(th.surface, 14, th.stroke))
                body.addView(tileRow(listOf(th.red, th.orange)))
                body.addView(tileRow(listOf(th.blue, th.green)))
                body.addView(block(th.surface, 16, th.stroke))
                phone.addView(body, lp(MATCH, 0, 1f))
                phone.addView(block(th.surface, 10))
            }
            HomeLayout.QUEUE -> {
                phone.addView(top)
                phone.addView(block(th.surface, 10, th.stroke))
                phone.addView(tileRow(listOf(th.red, th.red, th.orange, th.red)))
                for (col in listOf(th.red, th.orange, th.blue)) {
                    phone.addView(hbox(this).apply {
                        addView(View(this@ThemeActivity).apply { setBackgroundColor(col) }, lp(dp(3), MATCH))
                        addView(View(this@ThemeActivity).apply { background = GradientDrawable().apply { setColor(th.surface); cornerRadius = r } }, lp(0, MATCH, 1f))
                        layoutParams = lp(MATCH, dp(20)).margins(b = dp(4))
                    })
                }
            }
            HomeLayout.COMMAND -> {
                phone.addView(top)
                phone.addView(block(th.surface, 12, th.stroke))
                phone.addView(tileRow(listOf(th.red, th.red)))
                phone.addView(tileRow(listOf(th.orange, th.green)))
                val log = vbox(this).apply { background = GradientDrawable().apply { setColor(th.surface); cornerRadius = r }; setPadding(dp(4), dp(4), dp(4), dp(4)) }
                for (col in listOf(th.red, th.orange, th.blue, th.green)) log.addView(View(this).apply { background = rounded(col, dp(1).toFloat()) }, lp(dp((30..60).random()), dp(3)).margins(b = dp(3)))
                phone.addView(log, lp(MATCH, WRAP))
            }
            HomeLayout.EDITORIAL -> {
                phone.addView(top)
                phone.addView(View(this).apply { background = rounded(th.text, dp(2).toFloat()) }, lp(dp(60), dp(9)).margins(b = dp(3)))
                phone.addView(View(this).apply { background = rounded(th.text, dp(2).toFloat()) }, lp(dp(40), dp(9)).margins(b = dp(6)))
                val list = vbox(this).apply { background = GradientDrawable().apply { setColor(th.surface); cornerRadius = r; setStroke(dp(1), th.stroke) }; setPadding(dp(5), dp(5), dp(5), dp(5)) }
                for (col in listOf(th.red, th.red, th.orange, th.blue)) {
                    list.addView(hbox(this).apply {
                        addView(View(this@ThemeActivity).apply { background = rounded(th.muted, dp(1).toFloat()) }, lp(dp(30), dp(3)))
                        addView(View(this@ThemeActivity), lp(0, 1, 1f))
                        addView(View(this@ThemeActivity).apply { background = rounded(col, dp(2).toFloat()) }, lp(dp(10), dp(6)))
                        layoutParams = lp(MATCH, dp(13))
                    })
                }
                phone.addView(list, lp())
            }
            HomeLayout.CLASSIC -> {
                phone.addView(top)
                phone.addView(heroBlock(26))
                phone.addView(tileRow(listOf(th.red, th.red)))
                phone.addView(tileRow(listOf(th.orange, th.red)))
                phone.addView(tileRow(listOf(th.orange, th.gold)))
            }
        }
        return phone
    }
}
