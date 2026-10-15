package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** AFA registration details: info, API response, timeline, status change and refund. */
class AfaActivity : Activity() {

    private lateinit var s: Screen
    private var id = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        AlertPlayer.stop(this)
        id = intent.getIntExtra("id", 0)
        s = Screen(this, "AFA #$id")
        s.action("⟳") { load() }
        load()
    }

    private fun load() {
        s.body.removeAllViews()
        s.body.addView(loadingView(this))
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/afa/$id") }) { res, err ->
            s.body.removeAllViews()
            val o = res?.optJSONObject("order")
            if (o == null) {
                s.body.addView(emptyView(this, Api.friendly(err)))
                s.body.addView(btn(this, "Try again", BTN_SECONDARY) { load() })
                return@run
            }
            render(o)
        }
    }

    private fun render(o: JSONObject) {
        val st = o.str("status")
        s.setSubtitle(o.str("reference"))
        val top = card(this, if (o.optBoolean("needs_review")) C.RED else statusColor(st))
        val b = hbox(this)
        b.addView(badge(this, st, statusColor(st)))
        if (o.optBoolean("needs_review")) b.addView(badge(this, "⚠ needs review", C.RED).apply { layoutParams = lp(WRAP, WRAP).margins(l = dp(6)) })
        top.addView(b)
        top.addView(tv(this, o.str("full_name"), 19f, C.TEXT, true), lp().margins(t = dp(10)))
        top.addView(tv(this, "AFA registration • ${money(o.optDouble("price", 0.0))}", 14f, C.MUTED))
        val why = o.str("review_reason").ifEmpty { o.str("provider_message") }
        if (why.isNotEmpty()) top.addView(tv(this, "Provider: $why", 13.5f, C.ORANGE), lp().margins(t = dp(8)))
        s.body.addView(top)

        if (o.optBoolean("can_manage")) {
            val acts = hbox(this)
            acts.addView(btn(this, "Change status") { changeStatus(o) }, lp(0, WRAP, 1f).margins(r = dp(8)))
            val canRefund = st != "refunded" && st != "delivered"
            acts.addView(btn(this, "Refund", if (canRefund) BTN_DANGER else BTN_GHOST) {
                if (canRefund) confirmRefund(o) else toast(this, "This AFA order can't be refunded ($st).")
            }, lp(0, WRAP, 1f))
            s.body.addView(acts, lp().margins(b = dp(12)))
        }

        val d = card(this)
        d.addView(sectionTitle(this, "Registration details"))
        Detail.kv(this, d, "Full name", o.str("full_name"), copy = true)
        Detail.kv(this, d, "Phone", o.str("phone"), copy = true)
        Detail.kv(this, d, "Ghana Card", o.str("ghana_card"), copy = true)
        Detail.kv(this, d, "Location", o.str("location"))
        Detail.kv(this, d, "Customer", o.str("user_name").ifEmpty { "Guest / shop buyer" })
        Detail.kv(this, d, "Email", o.str("user_email"))
        Detail.kv(this, d, "Reference", o.str("reference"), copy = true)
        Detail.kv(this, d, "Idempotency key", o.str("idempotency_key").ifEmpty { "—" }, copy = o.str("idempotency_key").isNotEmpty())
        Detail.kv(this, d, "Provider", o.str("provider").ifEmpty { "Manual / none" })
        Detail.kv(this, d, "Provider ref", o.str("provider_reference").ifEmpty { "—" }, copy = o.str("provider_reference").isNotEmpty())
        Detail.kv(this, d, "Source", o.str("source"))
        Detail.kv(this, d, "Payment", o.str("payment_method") + if (o.str("momo_ref").isNotEmpty()) " • ${o.str("momo_ref")}" else "")
        if (!o.isNull("balance_before")) Detail.kv(this, d, "Wallet", "${money(o.optDouble("balance_before"))} → ${money(o.optDouble("balance_after"))}")
        s.body.addView(d)

        val api = card(this)
        api.addView(sectionTitle(this, "Provider API response"))
        if (o.str("api_response").isEmpty()) api.addView(tv(this, "No response yet.", 13f, C.MUTED))
        else api.addView(Detail.jsonBlock(this, o.str("api_response"), 12))
        s.body.addView(api)

        val items = ArrayList<Triple<String, String, String?>>()
        val tl = o.optJSONArray("timeline") ?: JSONArray()
        for (i in 0 until tl.length()) tl.optJSONObject(i)?.let { items.add(Triple(it.str("label").replaceFirstChar { c -> c.uppercase() }, it.str("at"), null)) }
        val t = card(this)
        t.addView(sectionTitle(this, "Timeline"))
        if (items.isEmpty()) t.addView(tv(this, "No timestamps yet.", 13f, C.MUTED)) else t.addView(Detail.timeline(this, items, Detail::timelineColor))
        s.body.addView(t)

        s.body.addView(btn(this, "Open AFA orders on website", BTN_GHOST) {
            openUrl(this, Prefs.serverUrl(this).trimEnd('/') + "/admin/afa/$id")
        }, lp())
    }

    private fun changeStatus(o: JSONObject) {
        val sts = ArrayList<String>()
        val arr = o.optJSONArray("statuses") ?: JSONArray()
        for (i in 0 until arr.length()) sts.add(arr.optString(i))
        Detail.statusDialog(this, "AFA #$id status", sts, o.str("status"), null, false) { st, _, _ ->
            Api.run(this, { Api.call(this, "POST", "/api/admin-app/afa/$id/status", body = JSONObject().put("status", st)) }) { res, err ->
                toast(this, res?.optString("message") ?: Api.friendly(err))
                load()
            }
        }
    }

    private fun confirmRefund(o: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Refund ${money(o.optDouble("price", 0.0))}?")
            .setMessage("The amount goes back to the customer's wallet (same as the website's AFA Refund button).")
            .setPositiveButton("Refund") { _, _ ->
                Api.run(this, { Api.call(this, "POST", "/api/admin-app/afa/$id/refund") }) { res, err ->
                    toast(this, res?.optString("message") ?: Api.friendly(err))
                    load()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
