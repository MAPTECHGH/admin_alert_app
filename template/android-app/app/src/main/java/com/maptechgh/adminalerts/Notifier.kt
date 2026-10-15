package com.maptechgh.adminalerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONObject

object Notifier {
    const val CH_LIVE = "live_status_v1"
    const val CH_INFO = "app_info_v1"
    const val ID_LIVE = 1001
    const val ID_SUMMARY = 1002
    const val ID_SIGNED_OUT = 1003
    private const val GROUP = "maptech_alerts"

    fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Channels are silent — the app plays each type's own ringtone itself (AlertPlayer). */
    fun createChannels(c: Context) {
        val nm = nm(c)
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Live alerts status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The small ongoing notification that keeps live alerts running"
            setShowBadge(false)
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "App messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Sign-in and connection messages"
        })
        for (t in AlertType.values()) {
            nm.createNotificationChannel(NotificationChannel(t.channelId, t.label, NotificationManager.IMPORTANCE_HIGH).apply {
                description = t.description + " (sound is set inside the app)"
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                lightColor = C.GOLD
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            })
        }
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openIntent(c: Context, type: String, refId: Int, notifId: Int): PendingIntent {
        val i = Intent(c, OpenActivity::class.java)
            .putExtra("type", type).putExtra("ref_id", refId).putExtra("notif_id", notifId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, notifId, i, flags())
    }

    fun ackIntent(c: Context, notifId: Int): PendingIntent {
        val i = Intent(c, AckReceiver::class.java).putExtra("notif_id", notifId)
        return PendingIntent.getBroadcast(c, notifId, i, flags())
    }

    fun liveNotification(c: Context, text: String): Notification {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), flags())
        return Notification.Builder(c, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Live alerts are on")
            .setContentText(text)
            .setColor(C.GOLD)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    fun updateLive(c: Context, text: String) {
        try { nm(c).notify(ID_LIVE, liveNotification(c, text)) } catch (e: Exception) { }
    }

    /** One alert → one heads-up notification (optionally a full-screen alarm). */
    fun postAlert(c: Context, e: JSONObject, type: AlertType, fullScreen: Boolean) {
        val key = e.optString("key")
        val id = 5000 + (key.hashCode() and 0x0FFFFFFF)
        val refId = e.optInt("ref_id")
        val b = Notification.Builder(c, type.channelId)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(e.optString("title"))
            .setContentText(e.optString("body").lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(e.optString("body")))
            .setColor(if (type.critical) C.RED else C.GOLD)
            .setAutoCancel(true)
            .setCategory(if (type.critical) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(parseServerTime(e.optString("created_at")) ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(GROUP)
            .setContentIntent(openIntent(c, e.optString("type", type.key), refId, id))
            .setDeleteIntent(ackIntent(c, id))
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_bell), "Open", openIntent(c, e.optString("type", type.key), refId, id)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_bell), "Silence", ackIntent(c, id)).build())

        if (fullScreen && canFullScreen(c)) {
            val alarm = Intent(c, AlarmActivity::class.java)
                .putExtra("type", e.optString("type", type.key)).putExtra("ref_id", refId).putExtra("notif_id", id)
                .putExtra("title", e.optString("title")).putExtra("body", e.optString("body"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            b.setFullScreenIntent(PendingIntent.getActivity(c, id + 1, alarm, flags()), true)
        }
        try { nm(c).notify(id, b.build()) } catch (ex: SecurityException) { }
    }

    fun postSummary(c: Context, count: Int) {
        val b = Notification.Builder(c, AlertType.COMPLAINT.channelId)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("$count new alerts")
            .setContentText("Open the app to see everything")
            .setColor(C.GOLD)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, ID_SUMMARY, Intent(c, MainActivity::class.java), flags()))
        try { nm(c).notify(ID_SUMMARY, b.build()) } catch (ex: SecurityException) { }
    }

    fun postInfo(c: Context, id: Int, title: String, text: String) {
        val b = Notification.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java), flags()))
        try { nm(c).notify(id, b.build()) } catch (ex: SecurityException) { }
    }

    fun canFullScreen(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) nm(c).canUseFullScreenIntent() else true

    fun notificationsAllowed(c: Context): Boolean = nm(c).areNotificationsEnabled()
}
