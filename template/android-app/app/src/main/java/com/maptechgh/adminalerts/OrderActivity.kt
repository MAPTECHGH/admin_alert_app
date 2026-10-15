package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Order details: info, idempotency/provider refs, current + previous API responses, full timeline, status actions. */
class OrderActivity : Activity() {

    private lateinit var s: Screen
    private var id = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        AlertPlayer.stop(this)
        id = intent.getIntExtra("id", 0)
        s = Screen(this, "Order #$id")
        s.action("⟳") { load() }
        load()
    }

    private fun load() {
        s.body.removeAllViews()
        s.body.addView(loadingView(this))
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/orders/$id") }) { res, err ->
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

        // ── Header ──────────────────────────────────────────
        val top = card(this, if (o.optBoolean("needs_review")) C.RED else statusColor(st))
        val badges = hbox(this)
        badges.addView(badge(this, st, statusColor(st)))
        if (o.optBoolean("needs_review")) badges.addView(badge(this, "⚠ needs review", C.RED).apply { layoutParams = lp(WRAP, WRAP).margins(l = dp(6)) })
        if (o.optBoolean("reprocessed")) badges.addView(badge(this, "re-sent", C.BLUE).apply { layoutParams = lp(WRAP, WRAP).margins(l = dp(6)) })
        top.addView(badges)
        top.addView(tv(this, "${o.str("network")} ${o.str("bundle")}", 19f, C.TEXT, true), lp().margins(t = dp(10)))
        top.addView(tv(this, "to ${o.str("recipient")} • ${money(o.optDouble("price", 0.0))}", 14f, C.MUTED))
        if (o.str("provider_message").isNotEmpty()) {
            top.addView(tv(this, "Provider: " + o.str("provider_message"), 13.5f, C.ORANGE), lp().margins(t = dp(8)))
        }
        s.body.addView(top)

        // ── Actions ─────────────────────────────────────────
        if (o.optBoolean("can_manage")) {
            val acts = hbox(this)
            acts.addView(btn(this, "Change status") { changeStatus(o) }, lp(0, WRAP, 1f).margins(r = dp(8)))
            val canRefund = st != "refunded" && st != "delivered" && st != "cancelled"
            acts.addView(btn(this, "Refund", if (canRefund) BTN_DANGER else BTN_GHOST) {
                if (canRefund) confirmRefund(o) else toast(this, "This order can't be refunded from here ($st).")
            }, lp(0, WRAP, 1f))
            s.body.addView(acts, lp().margins(b = dp(12)))
        }

        // ── Details ─────────────────────────────────────────
        val d = card(this)
        d.addView(sectionTitle(this, "Order details"))
        Detail.kv(this, d, "Customer", o.str("user_name").ifEmpty { "Guest / shop buyer" })
        Detail.kv(this, d, "Email", o.str("user_email"))
        Detail.kv(this, d, "Phone", o.str("user_phone"))
        Detail.kv(this, d, "Recipient", o.str("recipient"), copy = true)
        Detail.kv(this, d, "Reference", o.str("reference"), copy = true)
        Detail.kv(this, d, "Idempotency key", o.str("idempotency_key").ifEmpty { "—" }, copy = o.str("idempotency_key").isNotEmpty())
        Detail.kv(this, d, "Provider", o.str("provider").ifEmpty { "Manual / none" })
        Detail.kv(this, d, "Provider ref", o.str("provider_reference").ifEmpty { "—" }, copy = o.str("provider_reference").isNotEmpty())
        Detail.kv(this, d, "Send key", o.str("send_key"), copy = true)
        if (o.str("auto_reprocessed_from").isNotEmpty())
            Detail.kv(this, d, "Re-sent from", o.str("auto_reprocessed_from") + (if (o.str("auto_reprocessed_ref").isNotEmpty()) " (ref ${o.str("auto_reprocessed_ref")})" else ""), color = C.BLUE)
        Detail.kv(this, d, "Source", o.str("source"))
        Detail.kv(this, d, "Payment", o.str("payment_method") + if (o.str("momo_ref").isNotEmpty()) " • ${o.str("momo_ref")}" else "")
        if (!o.isNull("balance_before")) Detail.kv(this, d, "Wallet", "${money(o.optDouble("balance_before"))} → ${money(o.optDouble("balance_after"))}")
        Detail.kv(this, d, "Placed", o.str("created_at"))
        s.body.addView(d)

        // ── API response (current) ──────────────────────────
        val api = card(this)
        api.addView(sectionTitle(this, "Provider API response — current"))
        val cur = o.str("api_response")
        if (cur.isEmpty()) api.addView(tv(this, "No response yet (not sent to a provider, or it was reset for a re-send).", 13f, C.MUTED))
        else api.addView(Detail.jsonBlock(this, cur, 12))
        s.body.addView(api)

        // ── Previous responses (audit log + provider route) ─
        val audit = o.optJSONArray("audit") ?: JSONArray()
        val hist = o.optJSONArray("provider_history") ?: JSONArray()
        val prev = card(this)
        prev.addView(sectionTitle(this, "Earlier API responses"))
        var count = 0
        for (i in audit.length() - 1 downTo 0) {
            val r = audit.optJSONObject(i) ?: continue
            if (r.str("api_response").isEmpty()) continue
            val prov = r.str("old_provider").ifEmpty { r.str("new_provider") }
            val head = "${r.str("created_at")} • ${prov.ifEmpty { "provider" }}" +
                (if (r.str("old_status").isNotEmpty()) " • was ${r.str("old_status")}" else "")
            prev.addView(Detail.responseItem(this, head, r.str("api_message").ifEmpty { r.str("why") }, r.str("api_response")))
            count++
        }
        for (i in hist.length() - 1 downTo 0) {
            val h = hist.optJSONObject(i) ?: continue
            if (h.str("response").isEmpty()) continue
            prev.addView(Detail.responseItem(this, "${h.str("at")} • ${h.str("provider")} • ${h.str("outcome")}", h.str("reason"), h.str("response")))
            count++
        }
        if (count == 0) prev.addView(tv(this, "None — this order has only had one provider reply so far.", 13f, C.MUTED))
        s.body.addView(prev)

        // ── Timeline ────────────────────────────────────────
        val items = ArrayList<Triple<String, String, String?>>()
        val tl = o.optJSONArray("timeline") ?: JSONArray()
        for (i in 0 until tl.length()) tl.optJSONObject(i)?.let { items.add(Triple(it.str("label").replaceFirstChar { c -> c.uppercase() }, it.str("at"), null)) }
        for (i in 0 until audit.length()) {
            val r = audit.optJSONObject(i) ?: continue
            when (r.str("event")) {
                "status" -> {
                    val provChange = r.str("old_provider") != r.str("new_provider") && r.str("new_provider").isNotEmpty() && r.str("old_provider").isNotEmpty()
                    items.add(Triple("Status ${r.str("old_status").ifEmpty { "—" }} → ${r.str("new_status")}", r.str("created_at"),
                        if (provChange) "Provider ${r.str("old_provider")} → ${r.str("new_provider")}" else r.str("provider_reference").takeIf { it.isNotEmpty() }?.let { "Ref $it" }))
                }
                "created" -> items.add(Triple("Order created (${r.str("new_status")})", r.str("created_at"), r.str("new_provider").takeIf { it.isNotEmpty() }))
                "api_response" -> items.add(Triple("Provider response replaced", r.str("created_at"), r.str("api_message").takeIf { it.isNotEmpty() }))
                "snapshot" -> items.add(Triple("Attempt saved before reset", r.str("created_at"), r.str("why")))
            }
        }
        for (i in 0 until hist.length()) {
            val h = hist.optJSONObject(i) ?: continue
            val what = when (h.str("outcome")) {
                "reprocessed" -> "Re-sent ${h.str("provider")} → ${h.str("to")}"
                "refunded" -> "Auto-refunded (${h.str("provider")})"
                "review" -> "Needs review (${h.str("provider")})"
                else -> "${h.str("outcome").ifEmpty { "Provider step" }} (${h.str("provider")})"
            }
            items.add(Triple(what, h.str("at"), listOf(h.str("reason"), h.str("by")).filter { it.isNotEmpty() }.joinToString(" • ")))
        }
        val sorted = items.filter { it.second.isNotEmpty() }.distinct().sortedBy { it.second }
        val t = card(this)
        t.addView(sectionTitle(this, "Timeline"))
        if (sorted.isEmpty()) t.addView(tv(this, "No timestamps yet.", 13f, C.MUTED))
        else t.addView(Detail.timeline(this, sorted, Detail::timelineColor))
        s.body.addView(t)

        // ── Notes ───────────────────────────────────────────
        if (o.str("admin_note").isNotEmpty() || o.str("client_note").isNotEmpty()) {
            val n = card(this)
            if (o.str("admin_note").isNotEmpty()) { n.addView(sectionTitle(this, "Admin notes")); n.addView(tv(this, o.str("admin_note"), 13.5f, C.TEXT)) }
            if (o.str("client_note").isNotEmpty()) { n.addView(sectionTitle(this, "Note to client")); n.addView(tv(this, o.str("client_note"), 13.5f, C.TEXT)) }
            s.body.addView(n)
        }

        o.optJSONObject("complaint")?.let { c ->
            val cc = card(this, C.RED)
            cc.addView(sectionTitle(this, "Complaint on this order"))
            cc.addView(badge(this, c.str("status"), statusColor(c.str("status"))))
            cc.addView(tv(this, c.str("message"), 14f, C.TEXT), lp().margins(t = dp(6)))
            cc.addView(btn(this, "Open complaint", BTN_SECONDARY) {
                startActivity(Intent(this, ComplaintActivity::class.java).putExtra("id", c.optInt("id")))
            }, lp().margins(t = dp(10)))
            s.body.addView(cc)
        }

        val acts = hbox(this)
        acts.addView(btn(this, "Copy reference", BTN_SECONDARY) { copyText(this, "Reference", o.str("reference")) }, lp(0, WRAP, 1f).margins(r = dp(8)))
        acts.addView(btn(this, "Copy number", BTN_SECONDARY) { copyText(this, "Number", o.str("recipient")) }, lp(0, WRAP, 1f))
        s.body.addView(acts, lp().margins(b = dp(10)))
        s.body.addView(btn(this, "Open on website", BTN_GHOST) {
            openUrl(this, Prefs.serverUrl(this).trimEnd('/') + "/admin/orders/$id")
        }, lp())
    }

    private fun changeStatus(o: JSONObject) {
        val sts = ArrayList<String>()
        val arr = o.optJSONArray("statuses") ?: JSONArray()
        for (i in 0 until arr.length()) sts.add(arr.optString(i))
        Detail.statusDialog(this, "Order #$id status", sts, o.str("status"),
            if (o.optBoolean("can_change_provider")) o.optJSONArray("providers") else null, true) { st, pid, key ->
            Api.run(this, { Api.call(this, "POST", "/api/admin-app/orders/$id/status", body = Detail.statusBody(st, pid, key)) }) { res, err ->
                toast(this, res?.optString("message") ?: Api.friendly(err))
                load()
            }
        }
    }

    private fun confirmRefund(o: JSONObject) {
        val reason = input(this, "Reason (saved in admin note)")
        reason.setText(if (o.str("status") == "failed") "Order failed at provider" else "")
        val box = vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(reason) }
        AlertDialog.Builder(this)
            .setTitle("Refund ${money(o.optDouble("price", 0.0))}?")
            .setMessage("Uses the same refund logic as the website (wallet or Paystack). This can't be undone.")
            .setView(box)
            .setPositiveButton("Refund") { _, _ ->
                val body = JSONObject().put("reason", reason.text.toString().trim())
                Api.run(this, { Api.call(this, "POST", "/api/admin-app/orders/$id/refund", body = body) }) { res, err ->
                    if (res == null) toast(this, Api.friendly(err)) else { toast(this, res.optString("message", "Refunded.")); load() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
