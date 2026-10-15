package com.maptechgh.adminalerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/**
 * The "Live alerts" engine. Runs as a foreground service, asks the website
 * for new events every few seconds (/api/admin-app/feed) and rings the
 * moment something arrives. Survives screen-off and reboots; a watchdog
 * alarm restarts it if the phone's battery saver kills it.
 */
class AlertService : Service() {

    companion object {
        const val ACTION_POLL_NOW = "com.maptechgh.adminalerts.POLL_NOW"
        const val ACTION_UPDATED = "com.maptechgh.adminalerts.UPDATED"

        @Volatile var running = false
            private set

        fun shouldRun(c: Context) = Prefs.loggedIn(c) && Prefs.liveEnabled(c)

        fun start(c: Context) {
            if (!shouldRun(c)) return
            try {
                c.startForegroundService(Intent(c, AlertService::class.java))
            } catch (e: Exception) {
                // Background-start restriction (battery optimisation on). The watchdog retries.
            }
            scheduleWatchdog(c)
        }

        fun pollNow(c: Context) {
            if (!shouldRun(c)) return
            try {
                c.startForegroundService(Intent(c, AlertService::class.java).setAction(ACTION_POLL_NOW))
            } catch (e: Exception) { }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AlertService::class.java))
            cancelWatchdog(c)
        }

        private fun watchdogIntent(c: Context) = PendingIntent.getBroadcast(
            c, 77, Intent(c, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun scheduleWatchdog(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                watchdogIntent(c)
            )
        }

        fun cancelWatchdog(c: Context) {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchdogIntent(c))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var failures = 0
    private val pollTask = Runnable { pollOnce() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("Connecting…")
        running = true
        thread = HandlerThread("maptech-alerts").also { it.start() }
        worker = Handler(thread.looper)
        if (Prefs.keepAwake(this)) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaptechAdmin:live").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        worker.post(pollTask)
    }

    private fun goForeground(text: String) {
        val n = Notifier.liveNotification(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_LIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_LIVE, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusText())
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_POLL_NOW && ::worker.isInitialized) {
            worker.removeCallbacks(pollTask)
            worker.post(pollTask)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        if (::thread.isInitialized) thread.quitSafely()
        try { wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
        super.onDestroy()
    }

    private fun statusText(): String {
        val err = Prefs.lastError(this)
        val last = Prefs.lastCheck(this)
        val s = Prefs.summary(this)
        val parts = ArrayList<String>()
        if (s.has("complaints_open")) parts.add("${s.optInt("complaints_open")} open complaints")
        if (s.has("support_unread")) parts.add("${s.optInt("support_unread")} unread chats")
        val head = if (err.isNotEmpty()) "⚠ $err" else if (last > 0) "Checked ${clock(last)}" else "Connecting…"
        return if (parts.isEmpty()) head else head + " • " + parts.joinToString(" • ")
    }

    private fun pollOnce() {
        if (!shouldRun(this)) { stopSelf(); return }
        var delaySec = Prefs.pollSeconds(this).coerceAtLeast(10)
        try {
            val cur = Prefs.cursors(this)
            val q = HashMap<String, String>()
            val it = cur.keys()
            while (it.hasNext()) { val k = it.next(); q[k] = cur.optString(k) }
            q["stuck_min"] = Prefs.stuckMinutes(this).toString()

            val res = Api.call(this, "GET", "/api/admin-app/feed", q)
            res.optJSONObject("cursors")?.let { Prefs.saveCursors(this, it) }
            res.optJSONObject("summary")?.let { Prefs.saveSummary(this, it) }
            Prefs.setCheckResult(this, null)
            failures = 0

            val arr = res.optJSONArray("events")
            val fresh = ArrayList<JSONObject>()
            if (arr != null) for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (Prefs.markSeen(this, e.optString("key"))) fresh.add(e)
            }
            if (fresh.isNotEmpty()) handleEvents(fresh)
        } catch (e: ApiException) {
            if (e.code == 401) {
                Prefs.clearSession(this)
                Notifier.postInfo(this, Notifier.ID_SIGNED_OUT, "Signed out of Maptech Admin",
                    "Your app session ended (${e.message}). Open the app and sign in again to keep receiving alerts.")
                sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
                stopSelf()
                return
            }
            failures++
            Prefs.setCheckResult(this, e.message ?: "Server error")
        } catch (e: Exception) {
            failures++
            Prefs.setCheckResult(this, Api.friendly(e))
        }
        if (failures > 0) delaySec = minOf(120, delaySec * (1 shl minOf(failures - 1, 3)))
        Notifier.updateLive(this, statusText())
        sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
        worker.postDelayed(pollTask, delaySec * 1000L)
    }

    private fun handleEvents(events: List<JSONObject>) {
        // Only keep alert types that are switched on; turned-off types never show as priority.
        Prefs.addHistory(this, events.filter { AlertCards.shows(this, it.optString("type")) })
        if (!Prefs.masterNotify(this)) return

        val quiet = Prefs.inQuietHours(this)
        var loudest: AlertType? = null
        var shown = 0
        for (e in events) {
            val type = AlertType.from(e.optString("type")) ?: continue
            if (!Prefs.typeOn(this, type)) continue
            val soundAllowed = Prefs.masterSound(this) && (!quiet || (type.critical && Prefs.quietAllowCritical(this)))
            if (shown < 6) {
                Notifier.postAlert(this, e, type, soundAllowed && Prefs.typeFullScreen(this, type))
                shown++
            }
            if (soundAllowed && (loudest == null || rank(type) > rank(loudest))) loudest = type
        }
        if (shown >= 2) Notifier.postSummary(this, events.size)
        loudest?.let { AlertPlayer.play(this, it) }
    }

    private fun rank(t: AlertType): Int = when {
        t.critical -> 3
        t == AlertType.SUPPORT || t == AlertType.FAILED_ORDER || t == AlertType.NEEDS_REVIEW || t == AlertType.AFA_FAILED -> 2
        t == AlertType.STUCK_ORDER || t == AlertType.UNCLAIMED || t == AlertType.SHOP_WITHDRAWAL || t == AlertType.AFA_NEW -> 1
        else -> 0
    }
}
