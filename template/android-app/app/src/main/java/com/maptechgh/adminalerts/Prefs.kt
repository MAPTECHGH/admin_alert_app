package com.maptechgh.adminalerts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

const val APP_VERSION = "1.2.3"
const val DEV_WEBSITE = "https://www.mavetechservices.com"
const val DEV_WHATSAPP = "https://wa.me/233595054017"
const val DEFAULT_SERVER = "https://maptechgh.com"

/** Every kind of alert the server can send, with its out-of-the-box behaviour. */
enum class AlertType(
    val key: String,
    val label: String,
    val description: String,
    val access: String,          // key in admin.access returned by the server
    val critical: Boolean,
    val defaultOn: Boolean,
    val defaultTone: String,
    val defaultRing: Int,        // index into RING_MODES
    val defaultVibrate: Boolean,
    val defaultFullScreen: Boolean,
) {
    COMPLAINT("complaint", "Order complaints", "A customer or shop buyer files a complaint on an order",
        "complaints", true, true, "builtin:tone_alarm", 4, true, true),
    SUPPORT("support", "Live Support messages", "A client sends a new message in Live Support",
        "support", false, true, "builtin:tone_chime", 2, true, false),
    FAILED_ORDER("failed_order", "Failed orders", "A data order fails at the provider",
        "orders", false, true, "builtin:tone_siren", 1, true, false),
    STUCK_ORDER("stuck_order", "Orders stuck pending", "An order stays pending longer than your limit",
        "orders", false, true, "builtin:tone_pulse", 1, true, false),
    UNCLAIMED("unclaimed_payment", "Unclaimed MoMo payments", "A MoMo payment arrives that no account could claim",
        "payments", false, true, "builtin:tone_chime", 0, true, false),
    CONTACT("contact", "Contact form messages", "Someone writes through the website contact form",
        "contact", false, true, "builtin:tone_pulse", 0, false, false),
    NEW_USER("new_user", "New registrations", "A new customer account is created",
        "users", false, false, "builtin:tone_pulse", 0, false, false),
    NEEDS_REVIEW("needs_review", "Orders needing review", "An order or AFA goes to ⚠️ Needs Review (provider refused/refunded, unverified number on 'review', or low provider balance)",
        "orders|afa", false, true, "builtin:tone_siren", 2, true, false),
    REPROCESSED("reprocessed", "Orders auto re-sent", "An order is re-sent to another provider (e.g. unverified number rule)",
        "orders", false, true, "builtin:tone_pulse", 0, false, false),
    AFA_NEW("afa_new", "New AFA registrations", "A new AFA registration order comes in",
        "afa", false, true, "builtin:tone_chime", 0, true, false),
    AFA_FAILED("afa_failed", "Failed AFA registrations", "An AFA registration fails at the provider",
        "afa", false, true, "builtin:tone_siren", 1, true, false),
    SHOP_APPLICATION("shop_application", "New shop applications", "Someone applies to open a shop (waiting for approval)",
        "shops", false, true, "builtin:tone_chime", 0, true, false),
    SHOP_WITHDRAWAL("shop_withdrawal", "Shop withdrawal requests", "A shop owner requests a commission withdrawal",
        "shops", false, true, "builtin:tone_chime", 0, true, false);

    val channelId get() = "alert_${key}_v1"

    companion object {
        fun from(key: String?): AlertType? =
            if (key == "afa_review") NEEDS_REVIEW else values().firstOrNull { it.key == key }
    }
}

val RING_MODES = arrayOf("Play once", "Ring for 15 seconds", "Ring for 30 seconds", "Ring for 1 minute", "Ring until I respond")
val RING_SECONDS = intArrayOf(0, 15, 30, 60, 600)   // "until I respond" stops itself after 10 minutes

val BUILTIN_TONES = linkedMapOf(
    "builtin:tone_alarm" to "Urgent alarm",
    "builtin:tone_siren" to "Siren",
    "builtin:tone_chime" to "Bright chime",
    "builtin:tone_pulse" to "Soft pulse",
)

val POLL_CHOICES = intArrayOf(10, 15, 20, 30, 60)
val STUCK_CHOICES = intArrayOf(10, 15, 20, 30, 45, 60)

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("maptech_admin", Context.MODE_PRIVATE)

    // ── Session ──────────────────────────────────────────────
    fun serverUrl(c: Context) = sp(c).getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
    fun token(c: Context) = sp(c).getString("token", "") ?: ""
    fun loggedIn(c: Context) = token(c).isNotEmpty()

    fun admin(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("admin", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** accessKey may list alternatives: "orders|afa". */
    fun hasAccess(c: Context, accessKey: String): Boolean {
        val acc = admin(c).optJSONObject("access") ?: return false
        return accessKey.split('|').any { acc.optBoolean(it, false) }
    }

    fun saveSession(c: Context, server: String, token: String, admin: JSONObject) {
        sp(c).edit()
            .putString("server_url", server)
            .putString("token", token)
            .putString("admin", admin.toString())
            .remove("cursors")
            .putBoolean("live_enabled", true)
            .apply()
    }

    fun saveAdmin(c: Context, admin: JSONObject) = sp(c).edit().putString("admin", admin.toString()).apply()

    fun clearSession(c: Context) {
        sp(c).edit().remove("token").remove("admin").remove("cursors").remove("summary")
            .remove("history").remove("seen").remove("last_error").apply()
    }

    // ── Live service state ───────────────────────────────────
    fun liveEnabled(c: Context) = sp(c).getBoolean("live_enabled", true)
    fun setLiveEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("live_enabled", v).apply()

    fun cursors(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("cursors", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun saveCursors(c: Context, o: JSONObject) = sp(c).edit().putString("cursors", o.toString()).apply()

    fun summary(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("summary", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun saveSummary(c: Context, o: JSONObject) = sp(c).edit().putString("summary", o.toString()).apply()

    fun lastCheck(c: Context) = sp(c).getLong("last_check", 0L)
    fun lastError(c: Context) = sp(c).getString("last_error", "") ?: ""
    fun setCheckResult(c: Context, error: String?) {
        val e = sp(c).edit()
        if (error == null) e.putLong("last_check", System.currentTimeMillis()).putString("last_error", "")
        else e.putString("last_error", error)
        e.apply()
    }

    /** Remembers which alerts already rang, so a retry never rings twice. */
    fun markSeen(c: Context, key: String): Boolean {
        val arr = try { JSONArray(sp(c).getString("seen", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return false
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.optString(i))
        list.add(key)
        while (list.size > 400) list.removeAt(0)
        sp(c).edit().putString("seen", JSONArray(list).toString()).apply()
        return true
    }

    // ── General alert settings ───────────────────────────────
    fun masterNotify(c: Context) = sp(c).getBoolean("master_notify", true)
    fun masterSound(c: Context) = sp(c).getBoolean("master_sound", true)
    fun keepAwake(c: Context) = sp(c).getBoolean("keep_awake", true)
    fun volume(c: Context) = sp(c).getInt("volume", 100)
    fun ringOnSilent(c: Context) = sp(c).getBoolean("ring_on_silent", true)
    fun forceLoud(c: Context) = sp(c).getBoolean("force_loud", false)
    fun pollSeconds(c: Context) = sp(c).getInt("poll_sec", 15)
    fun stuckMinutes(c: Context) = sp(c).getInt("stuck_min", 20)

    fun quietEnabled(c: Context) = sp(c).getBoolean("quiet_on", false)
    fun quietStart(c: Context) = sp(c).getInt("quiet_start", 22 * 60)
    fun quietEnd(c: Context) = sp(c).getInt("quiet_end", 6 * 60)
    fun quietAllowCritical(c: Context) = sp(c).getBoolean("quiet_critical", true)

    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun setInt(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()

    fun inQuietHours(c: Context): Boolean {
        if (!quietEnabled(c)) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = quietStart(c)
        val e = quietEnd(c)
        return if (s == e) false else if (s < e) now in s until e else (now >= s || now < e)
    }

    // ── Per-alert-type settings ──────────────────────────────
    fun typeOn(c: Context, t: AlertType) = sp(c).getBoolean("t_${t.key}_on", t.defaultOn)
    fun typeTone(c: Context, t: AlertType) = sp(c).getString("t_${t.key}_tone", t.defaultTone) ?: t.defaultTone
    fun typeToneName(c: Context, t: AlertType): String {
        val tone = typeTone(c, t)
        BUILTIN_TONES[tone]?.let { return it }
        if (tone == "silent") return "Silent"
        if (tone == "default") return "Phone default sound"
        return sp(c).getString("t_${t.key}_tone_name", "Custom sound") ?: "Custom sound"
    }
    fun typeRing(c: Context, t: AlertType) = sp(c).getInt("t_${t.key}_ring", t.defaultRing).coerceIn(0, RING_MODES.size - 1)
    fun typeVibrate(c: Context, t: AlertType) = sp(c).getBoolean("t_${t.key}_vib", t.defaultVibrate)
    fun typeFullScreen(c: Context, t: AlertType) = sp(c).getBoolean("t_${t.key}_full", t.defaultFullScreen)

    fun setTypeOn(c: Context, t: AlertType, v: Boolean) = sp(c).edit().putBoolean("t_${t.key}_on", v).apply()
    fun setTypeTone(c: Context, t: AlertType, tone: String, name: String) =
        sp(c).edit().putString("t_${t.key}_tone", tone).putString("t_${t.key}_tone_name", name).apply()
    fun setTypeRing(c: Context, t: AlertType, v: Int) = sp(c).edit().putInt("t_${t.key}_ring", v).apply()
    fun setTypeVibrate(c: Context, t: AlertType, v: Boolean) = sp(c).edit().putBoolean("t_${t.key}_vib", v).apply()
    fun setTypeFullScreen(c: Context, t: AlertType, v: Boolean) = sp(c).edit().putBoolean("t_${t.key}_full", v).apply()

    // ── Appearance ───────────────────────────────────────────
    fun themeId(c: Context) = sp(c).getInt("theme_id", Themes.DEFAULT_ID)
    fun setThemeId(c: Context, id: Int) = sp(c).edit().putInt("theme_id", id).apply()

    /** Up to 5 favourite themes shown in the quick switcher on the home screen. */
    fun quickThemes(c: Context): List<Int> =
        (sp(c).getString("quick_themes", "10,1,2,3,6") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().take(Themes.MAX_QUICK)

    fun setQuickThemes(c: Context, ids: List<Int>) =
        sp(c).edit().putString("quick_themes", ids.distinct().take(Themes.MAX_QUICK).joinToString(",")).apply()

    // ── Local alert history (newest first, max 100) ──────────
    fun history(c: Context): JSONArray =
        try { JSONArray(sp(c).getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    fun addHistory(c: Context, events: List<JSONObject>) {
        if (events.isEmpty()) return
        val old = history(c)
        val out = JSONArray()
        for (e in events.reversed()) {
            e.put("received_at", System.currentTimeMillis())
            out.put(e)
        }
        var i = 0
        while (i < old.length() && out.length() < 100) { out.put(old.get(i)); i++ }
        sp(c).edit().putString("history", out.toString()).apply()
    }

    fun clearHistory(c: Context) = sp(c).edit().putString("history", "[]").apply()
}
