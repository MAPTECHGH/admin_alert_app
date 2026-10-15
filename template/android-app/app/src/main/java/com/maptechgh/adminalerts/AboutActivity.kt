package com.maptechgh.adminalerts

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** About the app and its developer, M-AVETECH IT SERVICES. */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val t = Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        val s = Screen(this, "About", "App & developer")
        val b = s.body

        // ── App hero ──
        val hero = vbox(this).apply {
            background = Themes.heroDrawable(dp(C.R + 6).toFloat())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            elevation = dp(6).toFloat()
        }
        hero.addView(logoView(this, 84))
        val admin = Prefs.admin(this)
        hero.addView(tv(this, "Maptech Admin", 22f, t.heroText, true).apply { gravity = Gravity.CENTER }, lp().margins(t = dp(14)))
        hero.addView(tv(this, "Official admin app for ${admin.optString("site_name", "MAPTECH GLOBAL")}", 13.5f, alpha(t.heroText, 210)).apply { gravity = Gravity.CENTER })
        hero.addView(tv(this, "Version $APP_VERSION", 12f, t.heroText, true).apply {
            background = rounded(alpha(t.heroText, 40), dp(20).toFloat())
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }, lp(WRAP, WRAP).margins(t = dp(12)))
        b.addView(hero, lp().margins(t = dp(4), b = dp(14)))

        // ── Developer ──
        b.addView(sectionTitle(this, "Developed by"))
        val dev = card(this, C.GOLD)
        val top = hbox(this)
        top.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.GOLD, C.BLUE)).apply { cornerRadius = dp(16).toFloat() }
            addView(tv(this@AboutActivity, "MA", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-black", Typeface.BOLD) },
                FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp(dp(58), dp(58)))
        val name = vbox(this).apply { setPadding(dp(14), 0, 0, 0) }
        name.addView(tv(this, "M-AVETECH IT SERVICES", 17f, C.TEXT, true).apply { letterSpacing = 0.02f })
        name.addView(tv(this, "Websites, apps, automation & IT support", 12.5f, C.MUTED))
        top.addView(name, lp(0, WRAP, 1f))
        dev.addView(top, lp())
        dev.addView(tv(this, "Need a website, an Android app or a custom system for your business? Reach us any time.", 13f, C.MUTED), lp().margins(t = dp(12)))
        dev.addView(contactRow(R.drawable.ic_globe, "Visit our website", "www.mavetechservices.com", C.BLUE) { openUrl(this, DEV_WEBSITE) }, lp().margins(t = dp(12)))
        dev.addView(contactRow(R.drawable.ic_wa, "Chat on WhatsApp", "+233 59 505 4017", Color.parseColor("#1FAF5A")) { openUrl(this, DEV_WHATSAPP) }, lp().margins(t = dp(8)))
        dev.addView(contactRow(R.drawable.ic_phone_dev, "Call us", "+233 59 505 4017", C.GOLD) { callPhone(this, "+233595054017") }, lp().margins(t = dp(8)))
        b.addView(dev)

        // ── What the app does ──
        b.addView(sectionTitle(this, "What this app does"))
        val feat = card(this)
        listOf(
            Triple(R.drawable.ic_bell, "Instant ringing alerts", "Complaints, failed, stuck and under-review orders ring your phone with your own tones."),
            Triple(R.drawable.ic_box, "Handle orders on the go", "Change status, refund and bulk-update orders and AFA registrations."),
            Triple(R.drawable.ic_chat, "Reply to customers", "Answer complaints and live chats straight from the app."),
            Triple(R.drawable.ic_store, "Run the shops", "Approve shops and withdrawals in one tap."),
            Triple(R.drawable.ic_palette, "10 built-in themes", "Pick a look and keep 5 favourites in the quick switcher.")
        ).forEachIndexed { i, (ic, h, d) ->
            val r = hbox(this).apply { gravity = Gravity.TOP; setPadding(0, if (i == 0) 0 else dp(12), 0, 0) }
            r.addView(iconChip(this, ic, C.GOLD, 36, 18))
            val tx = vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
            tx.addView(tv(this, h, 14.5f, C.TEXT, true))
            tx.addView(tv(this, d, 12.5f, C.MUTED))
            r.addView(tx, lp(0, WRAP, 1f))
            feat.addView(r, lp())
        }
        b.addView(feat)

        // ── App info ──
        b.addView(sectionTitle(this, "App info"))
        val info = card(this)
        Detail.kv(this, info, "Version", "$APP_VERSION")
        Detail.kv(this, info, "Website", Prefs.serverUrl(this))
        Detail.kv(this, info, "Signed in as", listOf(admin.optString("name"), admin.optString("role_name")).filter { it.isNotEmpty() }.joinToString(" • ").ifEmpty { "—" })
        Detail.kv(this, info, "Theme", t.name)
        Detail.kv(this, info, "Phone", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}")
        b.addView(info)

        b.addView(tv(this, "© 2026 M-AVETECH IT SERVICES. All rights reserved.", 11.5f, C.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6))
        }, lp())
    }

    private fun contactRow(ic: Int, title: String, sub: String, color: Int, onClick: () -> Unit): View = hbox(this).apply {
        background = ripple(rounded(alpha(color, if (C.LIGHT) 22 else 30), dp(14).toFloat(), alpha(color, 90), dp(1)))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(iconChip(this@AboutActivity, ic, color, 36, 18))
        val tx = vbox(this@AboutActivity).apply { setPadding(dp(12), 0, 0, 0) }
        tx.addView(tv(this@AboutActivity, title, 14f, C.TEXT, true))
        tx.addView(tv(this@AboutActivity, sub, 12f, C.MUTED))
        addView(tx, lp(0, WRAP, 1f))
        addView(icon(this@AboutActivity, R.drawable.ic_chevron, 16, C.MUTED))
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) }
}
