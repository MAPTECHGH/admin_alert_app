package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Draws alert cards (home "priority" list and the View-all page) in the theme's own style. */
object AlertCards {

    fun tone(type: String): Int = when (type) {
        "complaint", "needs_review", "afa_review", "failed_order", "afa_failed" -> C.RED
        "stuck_order" -> C.ORANGE
        "unclaimed_payment", "shop_withdrawal", "shop_application" -> C.YELLOW
        "new_user" -> C.GREEN
        else -> C.BLUE
    }

    fun label(type: String): String = AlertType.from(type)?.label ?: type

    /**
     * Alerts that should be shown: only types switched ON in Alert settings
     * (and that this admin's role can see). Turned-off types never show as priority.
     */
    fun visible(c: Context): List<JSONObject> {
        val arr = Prefs.history(c)
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (shows(c, e.optString("type"))) out.add(e)
        }
        return out
    }

    fun shows(c: Context, type: String): Boolean {
        val t = AlertType.from(type) ?: return true
        return Prefs.typeOn(c, t) && Prefs.hasAccess(c, t.access)
    }

    fun render(a: Activity, box: LinearLayout, items: List<JSONObject>, layout: HomeLayout) {
        if (layout == HomeLayout.COMMAND) {
            val log = vbox(a).apply { background = rounded(C.SURFACE, a.dp(C.R).toFloat(), C.STROKE, a.dp(1)); setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8)) }
            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            for (e in items) {
                val type = e.optString("type")
                val r = hbox(a).apply { setPadding(0, a.dp(5), 0, a.dp(5)); setOnClickListener { Router.open(a, type, e.optInt("ref_id")) } }
                r.addView(tv(a, fmt.format(e.optLong("received_at")), 11f, C.MUTED).apply { typeface = Typeface.MONOSPACE })
                r.addView(tv(a, "  " + label(type).uppercase(Locale.US).take(14).padEnd(14), 11f, tone(type), true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); maxLines = 1 })
                r.addView(tv(a, " " + e.optString("title"), 11f, C.TEXT).apply { typeface = Typeface.MONOSPACE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
                log.addView(r)
            }
            box.addView(log, lp().margins(b = a.dp(10)))
            return
        }
        for (e in items) {
            when (layout) {
                HomeLayout.QUEUE -> box.addView(queueCard(a, e), lp().margins(b = a.dp(10)))
                HomeLayout.EDITORIAL -> {
                    val type = e.optString("type")
                    val r = vbox(a).apply { setPadding(a.dp(2), a.dp(12), a.dp(2), a.dp(12)); setOnClickListener { Router.open(a, type, e.optInt("ref_id")) } }
                    r.addView(topRow(a, e), lp())
                    r.addView(tv(a, e.optString("title"), 15.5f, C.TEXT, true), lp().margins(t = a.dp(7)))
                    r.addView(tv(a, e.optString("body"), 13f, C.MUTED).apply { maxLines = 3 })
                    box.addView(r, lp())
                    box.addView(View(a).apply { setBackgroundColor(C.STROKE) }, lp(MATCH, a.dp(1)))
                }
                else -> {
                    val type = e.optString("type")
                    val c = card(a)
                    c.background = ripple(rounded(C.SURFACE, a.dp(C.R).toFloat(), C.STROKE, a.dp(1)))
                    c.addView(topRow(a, e), lp())
                    c.addView(tv(a, e.optString("title"), 15f, C.TEXT, true), lp().margins(t = a.dp(8)))
                    c.addView(tv(a, e.optString("body"), 12.5f, C.MUTED).apply { maxLines = 3 }, lp().margins(t = a.dp(2)))
                    c.setOnClickListener { Router.open(a, type, e.optInt("ref_id")) }
                    box.addView(c)
                }
            }
        }
    }

    /** Card with a coloured edge and Handle now / Silence buttons. */
    fun queueCard(a: Activity, e: JSONObject): View {
        val type = e.optString("type")
        val tone = tone(type)
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.SURFACE, a.dp(C.R).toFloat(), C.STROKE, a.dp(1)); clipToOutline = true
        }
        c.addView(View(a).apply { setBackgroundColor(tone) }, lp(a.dp(5), MATCH))
        val b = vbox(a).apply { setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12)) }
        b.addView(topRow(a, e), lp())
        b.addView(tv(a, e.optString("title"), 15f, C.TEXT, true), lp().margins(t = a.dp(8)))
        b.addView(tv(a, e.optString("body"), 12.5f, C.MUTED).apply { maxLines = 2 })
        val acts = hbox(a).apply { setPadding(0, a.dp(10), 0, 0) }
        acts.addView(tv(a, "Handle now", 12.5f, if (tone == C.YELLOW || tone == C.ORANGE) Color.parseColor("#111111") else Color.WHITE, true).apply {
            background = ripple(rounded(tone, a.dp(10).toFloat())); setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            setOnClickListener { Router.open(a, type, e.optInt("ref_id")) }
        })
        acts.addView(tv(a, "Silence", 12.5f, C.TEXT, true).apply {
            background = ripple(rounded(C.SURFACE2, a.dp(10).toFloat())); setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            setOnClickListener { AlertPlayer.stop(a); toast(a, "Silenced") }
        }, lp(WRAP, WRAP).margins(l = a.dp(8)))
        b.addView(acts, lp())
        c.addView(b, lp(0, WRAP, 1f))
        return c
    }

    private fun topRow(a: Activity, e: JSONObject): LinearLayout {
        val type = e.optString("type")
        val top = hbox(a)
        top.addView(badge(a, label(type), tone(type)))
        top.addView(View(a), lp(0, 1, 1f))
        top.addView(tv(a, ago(e.optLong("received_at")), 11.5f, C.MUTED))
        return top
    }
}
