package com.maptechgh.adminalerts

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}

/** Restart live alerts after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (AlertService.shouldRun(c)) AlertService.start(c)
    }
}

/** "Silence" button / swiping an alert away stops the ringtone. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        AlertPlayer.stop(c)
        val id = intent.getIntExtra("notif_id", 0)
        if (id != 0 && intent.getBooleanExtra("cancel", false)) Notifier.nm(c).cancel(id)
    }
}

/** Every ~15 minutes: if the phone killed the live service, bring it back. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (!AlertService.shouldRun(c)) { AlertService.cancelWatchdog(c); return }
        if (!AlertService.running) AlertService.start(c) else AlertService.pollNow(c)
    }
}

/** Invisible router for notification taps: stop the ringing, then open the right screen. */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertPlayer.stop(this)
        val id = intent.getIntExtra("notif_id", 0)
        if (id != 0) Notifier.nm(this).cancel(id)
        Router.open(this, intent.getStringExtra("type") ?: "", intent.getIntExtra("ref_id", 0))
        finish()
    }
}

object Router {
    fun open(a: Activity, type: String, refId: Int) {
        if (!Prefs.loggedIn(a)) {
            a.startActivity(Intent(a, LoginActivity::class.java))
            return
        }
        val base = Prefs.serverUrl(a).trimEnd('/')
        if (refId == 0 && type != "contact" && !type.startsWith("shop_")) {   // test alerts
            a.startActivity(Intent(a, MainActivity::class.java))
            return
        }
        val i: Intent? = when (type) {
            "complaint" -> Intent(a, ComplaintActivity::class.java).putExtra("id", refId)
            "support" -> Intent(a, SupportChatActivity::class.java).putExtra("id", refId)
            "failed_order", "stuck_order", "needs_review", "reprocessed" -> Intent(a, OrderActivity::class.java).putExtra("id", refId)
            "afa_new", "afa_failed", "afa_review" -> Intent(a, AfaActivity::class.java).putExtra("id", refId)
            "shop_application" -> Intent(a, ListActivity::class.java).putExtra("mode", ListActivity.MODE_SHOPS).putExtra("filter", "pending")
            "shop_withdrawal" -> Intent(a, ListActivity::class.java).putExtra("mode", ListActivity.MODE_WITHDRAWALS).putExtra("filter", "pending")
            "contact" -> Intent(a, ListActivity::class.java).putExtra("mode", ListActivity.MODE_CONTACT)
            "unclaimed_payment" -> { openUrl(a, "$base/admin/send-claim"); null }
            "new_user" -> { openUrl(a, "$base/admin/users"); null }
            else -> Intent(a, MainActivity::class.java)
        }
        if (i != null) a.startActivity(i)
    }
}
