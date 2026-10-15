package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class ApiException(val code: Int, message: String) : Exception(message)

/** Tiny HTTP/JSON client for the /api/admin-app endpoints (no extra libraries). */
object Api {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Blocking call — run it off the main thread (see [run]). */
    fun call(
        c: Context,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        auth: Boolean = true,
        server: String? = null,
    ): JSONObject {
        val base = (server ?: Prefs.serverUrl(c)).trim().trimEnd('/')
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(base + path + qs).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MaptechAdminApp/$APP_VERSION (Android)")
            if (auth) conn.setRequestProperty("X-App-Token", Prefs.token(c))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(code, if (code in 200..299) "Unexpected reply from server. Check the server address."
                                          else "Server error ($code). Please try again.")
            }
            if (code !in 200..299 || !json.optBoolean("success", false)) {
                throw ApiException(code, json.optString("message", "Request failed ($code)."))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Runs [work] in the background and hands the result to [done] on the
     * main thread. Skips the callback if the activity has gone away, and
     * sends the user back to sign-in when the server says the session ended.
     */
    fun <T> run(act: Activity?, work: () -> T, done: (T?, Exception?) -> Unit) {
        pool.execute {
            var result: T? = null
            var error: Exception? = null
            try { result = work() } catch (e: Exception) { error = e }
            main.post {
                if (act != null && (act.isFinishing || act.isDestroyed)) return@post
                if (error is ApiException && (error as ApiException).code == 401 && act != null && act !is LoginActivity) {
                    sessionEnded(act, error!!.message)
                    return@post
                }
                done(result, error)
            }
        }
    }

    fun sessionEnded(act: Activity, msg: String?) {
        Prefs.clearSession(act)
        AlertService.stop(act)
        AlertPlayer.stop(act)
        toast(act, msg ?: "Please sign in again.")
        act.startActivity(Intent(act, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        act.finish()
    }

    fun friendly(e: Exception?): String = when (e) {
        null -> "Something went wrong."
        is ApiException -> e.message ?: "Request failed."
        is java.net.UnknownHostException -> "No internet connection (or wrong server address)."
        is java.net.SocketTimeoutException -> "The server took too long to respond."
        is java.net.ConnectException -> "Could not reach the server."
        is javax.net.ssl.SSLException -> "Secure connection failed. Check the server address uses https."
        else -> e.message ?: e.javaClass.simpleName
    }
}
