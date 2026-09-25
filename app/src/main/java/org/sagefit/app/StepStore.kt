package org.sagefit.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * All-day steps from the phone's step-counter chip.
 *
 * The chip counts every step since the phone was last turned on, whether SageFit is open,
 * minimized or closed. We read that running total now and then (every 15 minutes from
 * StepWorker, and whenever SageFit is open) and add the difference to today's total.
 * Totals are kept on the phone only, for the last 35 days.
 */
object StepStore {
    private const val PREFS = "sagefit_steps"

    fun hasSensor(ctx: Context): Boolean =
        ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Reads the chip once (or gives up after 5 seconds) and folds the new steps into today. */
    suspend fun sample(ctx: Context): Boolean {
        val value = readCounter(ctx) ?: return false
        record(ctx, value)
        return true
    }

    /** Adds the steps taken since the last reading to today. Called by sample() and by StepService. */
    @Synchronized
    fun record(ctx: Context, value: Float) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val days = JSONObject(prefs.getString("days", "{}") ?: "{}")
        val today = LocalDate.now().toString()
        val last = prefs.getFloat("last", -1f)
        if (last >= 0f) {
            // If the number went down, the phone restarted and the chip began again at 0.
            val delta = if (value >= last) value - last else value
            if (delta in 0f..60000f) days.put(today, days.optLong(today, 0L) + delta.toLong())
        }
        // Forget days older than 35 days.
        val oldest = LocalDate.now().minusDays(35).toString()
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }
        prefs.edit().putFloat("last", value).putString("days", days.toString()).apply()
    }

    fun today(ctx: Context): Long = recent(ctx, 1).values.firstOrNull() ?: 0L

    /** Whether the person wants always-on counting (on by default once they allow Physical activity). */
    fun alwaysOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("always_on", true)
    fun setAlwaysOn(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("always_on", on).apply()

    /** Saved daily totals, newest first, for the last [n] days. */
    fun recent(ctx: Context, n: Int): Map<String, Long> {
        val days = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("days", "{}") ?: "{}")
        return (0 until n).associate { i -> LocalDate.now().minusDays(i.toLong()).toString().let { it to days.optLong(it, 0L) } }
    }

    private suspend fun readCounter(ctx: Context): Float? = withTimeoutOrNull(5000L) {
        suspendCancellableCoroutine<Float?> { cont ->
            val sm = ctx.getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sm == null || sensor == null) { cont.resume(null); return@suspendCancellableCoroutine }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    sm.unregisterListener(this)
                    if (cont.isActive) cont.resume(e.values[0])
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            cont.invokeOnCancellation { sm.unregisterListener(listener) }
        }
    }
}
