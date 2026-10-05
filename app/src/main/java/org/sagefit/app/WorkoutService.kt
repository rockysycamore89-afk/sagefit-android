package org.sagefit.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * GPS for workouts. Runs ONLY between "Start GPS workout" and "Finish" (or Discard),
 * with a "SageFit is tracking your workout" notification, so the route keeps recording
 * while the screen is off or the phone is in a pocket.
 *
 * - Never runs on its own: no all-day tracking and no background-location permission.
 * - The fixes stay on the phone. The SageFit page picks them up every 2 seconds
 *   (workoutPoints) and does the filtering, distance and map, same as on the website.
 * - Turns itself off after 6 hours in case a workout is never finished.
 */
class WorkoutService : Service(), LocationListener {

    companion object {
        private const val CHANNEL = "sagefit_workout"
        private const val NOTE_ID = 8
        private const val MAX_MS = 6 * 60 * 60 * 1000L

        // Shared with the page through MainActivity's bridge. "off", "starting", "running", "gpsoff", "denied", "ended" (6-hour limit)
        @Volatile var state = "off"
        @Volatile private var live: WorkoutService? = null
        private var mode = "precise"
        private var act = ""
        private var pid = ""
        private var saver = false
        private var t0 = 0L
        private val pts = ArrayList<DoubleArray>()      // lat, lon, accuracy (m), time (ms)

        fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts a workout, or updates the settings (Energy saver) of the one already running. */
        fun start(ctx: Context, json: String) {
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val fresh = synchronized(pts) {
                val f = state == "off" || state == "denied" || state == "ended" || live == null
                if (f) { pts.clear(); t0 = System.currentTimeMillis(); state = "starting" }
                mode = if (o.optString("mode") == "approx") "approx" else "precise"
                act = o.optString("act", "Walking (brisk)")
                pid = o.optString("pid", "")
                saver = o.optBoolean("saver", false)
                f
            }
            if (!hasLocation(ctx)) { state = "denied"; return }
            live?.let { svc -> if (!fresh) { Handler(Looper.getMainLooper()).post { svc.listen() }; return } }
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WorkoutService::class.java)) }
                .onFailure { state = "denied" }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WorkoutService::class.java))
            synchronized(pts) { state = "off"; pts.clear() }
        }

        /** Everything the page needs, starting at fix number [from]. */
        fun points(from: Int): String = synchronized(pts) {
            val list = JSONArray()
            for (i in from.coerceAtLeast(0) until pts.size) {
                val p = pts[i]
                list.put(JSONArray().put(p[0]).put(p[1]).put(p[2]).put(p[3].toLong()))
            }
            JSONObject().put("state", state).put("mode", mode).put("act", act).put("pid", pid)
                .put("t0", t0).put("n", pts.size).put("pts", list).toString()
        }
    }

    private var lm: LocationManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeLimit = Runnable { state = "ended"; stopSelf() }   // keeps the fixes so the page can still save the workout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        makeChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note(), type)
        } catch (e: Exception) {
            state = "denied"; stopSelf(); return   // Android refused: Location permission is off
        }
        lm = getSystemService(LocationManager::class.java)
        live = this
        main.postDelayed(timeLimit, MAX_MS)
    }

    // Called on start and again whenever the page changes a setting (Energy saver).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listen()
        return START_NOT_STICKY        // if Android ever kills it, don't restart GPS on its own
    }

    @SuppressLint("MissingPermission")   // checked in hasLocation() / fine below
    fun listen() {
        val lm = lm ?: return
        if (!hasLocation(this)) { state = "denied"; stopSelf(); return }
        lm.removeUpdates(this)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val useGps = fine && mode == "precise" && lm.allProviders.contains(LocationManager.GPS_PROVIDER)
        val provider = when {
            useGps -> LocationManager.GPS_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        // Precise: a fix every second (every 5 with Energy saver). Approximate: every 5 seconds.
        val every = if (provider == LocationManager.GPS_PROVIDER && !saver) 1_000L else 5_000L
        try {
            lm.requestLocationUpdates(provider, every, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) { state = "denied"; stopSelf(); return }
        if (!lm.isProviderEnabled(provider)) state = "gpsoff"
    }

    override fun onLocationChanged(loc: Location) {
        synchronized(pts) {
            if (state == "off" || state == "ended") return
            val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 999.0
            pts.add(doubleArrayOf(loc.latitude, loc.longitude, acc, loc.time.toDouble()))
            state = "running"
        }
    }

    override fun onProviderDisabled(provider: String) { if (state != "off") state = "gpsoff" }
    override fun onProviderEnabled(provider: String) { if (state == "gpsoff") state = if (pts.isEmpty()) "starting" else "running" }
    @Deprecated("Needed on Android 9 and older") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onDestroy() {
        lm?.removeUpdates(this)
        main.removeCallbacks(timeLimit)
        if (live === this) live = null
        super.onDestroy()
    }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "GPS workouts", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while SageFit records a GPS workout you started"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun note(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SageFit is tracking your workout")
            .setContentText(if (act.isNotEmpty()) "$act. Open SageFit to finish." else "Open SageFit to finish.")
            .setWhen(t0)
            .setUsesChronometer(true)          // shows the workout time counting up
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }
}
