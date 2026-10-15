package com.maptechgh.adminalerts

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout

/** Full-screen alarm shown over the lock screen for critical alerts (complaints by default). */
class AlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val rawType = intent.getStringExtra("type") ?: ""
        val type = AlertType.from(rawType)
        val refId = intent.getIntExtra("ref_id", 0)
        val notifId = intent.getIntExtra("notif_id", 0)

        val root = vbox(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(C.BG)
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }
        val bell = tv(this, "🔔", 64f).apply { gravity = Gravity.CENTER }
        root.addView(bell, lp(MATCH, WRAP))
        ObjectAnimator.ofFloat(bell, "rotation", -14f, 14f).apply {
            duration = 260; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
        root.addView(badge(this, type?.label ?: "Alert", if (type?.critical == true) C.RED else C.GOLD).apply {
            layoutParams = lp(WRAP, WRAP).margins(t = dp(20), b = dp(14))
        })
        root.addView(tv(this, intent.getStringExtra("title") ?: "New alert", 24f, C.TEXT, true).apply { gravity = Gravity.CENTER })
        root.addView(tv(this, intent.getStringExtra("body") ?: "", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            layoutParams = lp().margins(t = dp(10), b = dp(36))
        })
        root.addView(btn(this, "Open and handle it") {
            AlertPlayer.stop(this)
            if (notifId != 0) Notifier.nm(this).cancel(notifId)
            unlockThen { Router.open(this, rawType, refId); finish() }
        }, lp().margins(b = dp(12)))
        root.addView(btn(this, "Silence", BTN_SECONDARY) {
            AlertPlayer.stop(this)
            finish()
        }, lp())
        setContentView(root, LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun unlockThen(action: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
            })
        } else action()
    }

    override fun onBackPressed() {
        AlertPlayer.stop(this)
        super.onBackPressed()
    }
}
