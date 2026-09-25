package org.sagefit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat

/**
 * Always-on step counting. Shows a quiet notification ("SageFit is counting your steps")
 * so Android and Samsung's battery manager keep SageFit running while it's minimized or
 * the screen is off. Listens to the phone's step-counter chip, which uses almost no battery,
 * and saves every step to today's total as it happens.
 */
class StepService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL = "sagefit_steps"
        private const val NOTE_ID = 7
        @Volatile var running = false

        fun start(ctx: Context) {
            if (!StepWorker.canCount(ctx) || !StepStore.alwaysOn(ctx) || !StepStore.hasSensor(ctx)) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, StepService::class.java)) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, StepService::class.java)) }
    }

    private var sensors: SensorManager? = null
    private var lastNoteUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        makeChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note(StepStore.today(this)), type)
        } catch (e: Exception) {
            stopSelf(); return        // Android refused (for example, Physical activity was turned off)
        }
        running = true
        sensors = getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            // Deliver steps in batches of up to a minute: accurate, and easy on the battery.
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        } ?: stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY   // restart if the system ever stops it

    override fun onSensorChanged(e: SensorEvent) {
        StepStore.record(this, e.values[0])
        val now = System.currentTimeMillis()
        if (now - lastNoteUpdate > 60_000) {               // refresh the notification at most once a minute
            lastNoteUpdate = now
            getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note(StepStore.today(this)))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensors?.unregisterListener(this)
        running = false
        super.onDestroy()
    }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Step counting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while SageFit counts your steps in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun note(steps: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("SageFit is counting your steps")
            .setContentText("${NumberFormat.getIntegerInstance().format(steps)} steps today")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
    }
}
