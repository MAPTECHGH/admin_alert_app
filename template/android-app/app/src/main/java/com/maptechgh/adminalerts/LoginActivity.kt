package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import org.json.JSONObject

class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        val s = Screen(this, "", back = false)
        s.root.removeViewAt(0) // no top bar on the sign-in screen
        val b = s.body
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.setPadding(dp(24), dp(48), dp(24), dp(32))

        b.addView(logoView(this, 88).apply { layoutParams = lp(dp(88), dp(88)) })
        b.addView(tv(this, "Maptech Admin", 26f, C.TEXT, true).apply { gravity = Gravity.CENTER }, lp().margins(t = dp(10)))
        b.addView(tv(this, "Real-time alerts for administrators", 14f, C.MUTED).apply { gravity = Gravity.CENTER },
            lp().margins(t = dp(4), b = dp(28)))

        val form = card(this)
        form.addView(tv(this, "Sign in with your website admin account", 13f, C.MUTED), lp().margins(b = dp(12)))
        val email = input(this, "Admin email", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = input(this, "Password", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pass.imeOptions = EditorInfo.IME_ACTION_DONE
        form.addView(email); form.addView(pass)

        val server = input(this, "Website address", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        server.setText(Prefs.serverUrl(this))
        server.visibility = View.GONE
        val serverToggle = tv(this, "Website: ${Prefs.serverUrl(this)}  •  change", 12.5f, C.MUTED).apply {
            setPadding(0, dp(2), 0, dp(12))
        }
        serverToggle.setOnClickListener { server.visibility = View.VISIBLE; serverToggle.visibility = View.GONE }
        form.addView(serverToggle); form.addView(server)

        val error = tv(this, "", 13.5f, C.RED).apply { visibility = View.GONE }
        form.addView(error, lp().margins(b = dp(10)))

        lateinit var go: android.widget.TextView
        go = btn(this, "Sign in") {
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            var url = server.text.toString().trim().trimEnd('/')
            if (url.isEmpty()) url = DEFAULT_SERVER
            if (!url.startsWith("http")) url = "https://$url"
            if (e.isEmpty() || p.isEmpty()) { error.text = "Enter your email and password."; error.visibility = View.VISIBLE; return@btn }
            error.visibility = View.GONE
            go.isEnabled = false; go.text = "Signing in…"
            val body = JSONObject().put("email", e).put("password", p)
                .put("device_name", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                .put("app_version", APP_VERSION)
            Api.run(this, { Api.call(this, "POST", "/api/admin-app/login", body = body, auth = false, server = url) }) { res, err ->
                go.isEnabled = true; go.text = "Sign in"
                if (res == null) {
                    error.text = Api.friendly(err)
                    if (err is ApiException && err.code == 404) error.text = "This website doesn't have the admin-app API installed yet (404)."
                    error.visibility = View.VISIBLE
                    return@run
                }
                Prefs.saveSession(this, url, res.optString("token"), res.optJSONObject("admin") ?: JSONObject())
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
        pass.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { go.performClick(); true } else false }
        form.addView(go, lp())
        b.addView(form, lp())

        b.addView(tv(this, "Only accounts with the admin role can sign in. Each phone gets its own key, which you can sign out from the app at any time.",
            12f, C.MUTED).apply { gravity = Gravity.CENTER }, lp().margins(t = dp(12)))
        b.addView(creditsCard(this))
    }
}
