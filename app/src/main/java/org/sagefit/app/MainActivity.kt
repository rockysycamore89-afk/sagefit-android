package org.sagefit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * SageFit for Android: shows the SageFit web app and adds all-day steps.
 * The phone keeps counting steps in Health Connect even when SageFit is closed
 * (fed by Samsung Health, Google Fit, Fitbit, etc.). The page asks for them through
 * window.SageFitNative, and gets the answer back in window.onNativeSteps(json).
 * Nothing is sent anywhere: the numbers go straight into the page on this phone.
 */
class MainActivity : ComponentActivity() {

    companion object { const val SITE = "https://sagefitfitnesstracker.netlify.app/" }

    private lateinit var web: WebView
    private val stepsPermission = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingDays = 7
    private val live = Handler(Looper.getMainLooper())
    private val liveTick = object : Runnable {       // while SageFit is on screen, refresh steps every 30 seconds
        override fun run() { readSteps(1, fromTimer = true); live.postDelayed(this, 30_000) }
    }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { StepService.start(this) }
    private val askMotion = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startAlwaysOn()
        readSteps(pendingDays)
    }

    /** Turns on always-on counting. On Android 13+ the notification needs permission first. */
    private fun startAlwaysOn() {
        if (!StepStore.alwaysOn(this)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        else StepService.start(this)
    }

    private val askHealth = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { readSteps(pendingDays) }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        geoCallback?.invoke(geoOrigin, ok, false); geoCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true          // SageFit saves everything on the phone
        web.settings.databaseEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "SageFitNative")
        web.webViewClient = object : WebViewClient() {
            // Keep SageFit inside the app; open other sites (privacy policy links etc.) in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == Uri.parse(SITE).host) return false
                startActivity(Intent(Intent.ACTION_VIEW, u)); return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                geoOrigin = origin; geoCallback = callback
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(SITE)
        StepWorker.schedule(this)                       // backup: saves the count every 15 minutes
        if (StepWorker.canCount(this)) startAlwaysOn()  // main: always-on counting with a quiet notification
    }

    override fun onResume() { super.onResume(); live.postDelayed(liveTick, 30_000) }
    override fun onPause() { super.onPause(); live.removeCallbacks(liveTick) }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    /** Called from the page's JavaScript. Runs off the main thread, so hop back before touching views. */
    inner class Bridge {
        @JavascriptInterface fun isAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE
        @JavascriptInterface fun hasStepSensor(): Boolean = StepStore.hasSensor(this@MainActivity)
        @JavascriptInterface fun isAlwaysOn(): Boolean = StepStore.alwaysOn(this@MainActivity) && StepService.running
        @JavascriptInterface fun setAlwaysOn(on: Boolean) = runOnUiThread {
            StepStore.setAlwaysOn(this@MainActivity, on)
            if (on) { if (StepWorker.canCount(this@MainActivity)) startAlwaysOn() else askMotion.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
            else StepService.stop(this@MainActivity)
        }
        @JavascriptInterface fun requestPermission() = runOnUiThread { askHealthConnect() }
        @JavascriptInterface fun requestSteps(days: Int) = runOnUiThread { askForSteps(days.coerceIn(1, 30)) }
        /** Opens the battery settings so the phone doesn't pause SageFit's 15-minute step saves. */
        @JavascriptInterface fun openBatterySettings() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    /** First makes sure SageFit may use the step-counter chip, then reads steps. */
    private fun askForSteps(days: Int) {
        pendingDays = days
        if (!StepWorker.canCount(this) && StepStore.hasSensor(this)) askMotion.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else readSteps(days)
    }

    /** Asks for Health Connect access (only when the person taps the button, never on its own). */
    private fun askHealthConnect() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> askHealth.launch(stepsPermission)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                val store = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store)).setPackage("com.android.vending")) }
                send(error("unavailable"))
            }
            else -> send(error("unavailable"))
        }
    }

    /**
     * Steps per day from two places, keeping the higher number for each day:
     *  1. the phone's own step-counter chip (saved by StepWorker, even while SageFit is closed)
     *  2. Health Connect (Samsung Health, Google Fit, Fitbit...), if the person allowed it
     */
    private fun readSteps(days: Int, fromTimer: Boolean = false) = lifecycleScope.launch {
        val out = JSONObject()
        val sources = mutableListOf<String>()
        if (StepWorker.canCount(this@MainActivity) && StepStore.sample(this@MainActivity)) {
            sources += "phone"
            StepStore.recent(this@MainActivity, days).forEach { (d, n) -> out.put(d, n) }
        }
        try {
            if (HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                if (client.permissionController.getGrantedPermissions().containsAll(stepsPermission)) {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    for (i in 0 until days) {
                        val day = today.minusDays(i.toLong())
                        val r = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
                            TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())))
                        val hc = r[StepsRecord.COUNT_TOTAL] ?: 0L
                        if (hc > out.optLong(day.toString(), 0L)) out.put(day.toString(), hc)
                    }
                    sources += "healthconnect"
                }
            }
        } catch (e: Exception) { /* Health Connect is optional; the phone's own counter still works */ }
        when {
            sources.isNotEmpty() -> send(JSONObject().put("ok", true).put("days", out).put("sources", sources.joinToString(",")).put("live", fromTimer))
            !fromTimer -> send(error(if (StepStore.hasSensor(this@MainActivity)) "motion" else "nosensor"))
        }
    }

    private fun error(code: String) = JSONObject().put("ok", false).put("error", code)

    private fun send(json: JSONObject) {
        val arg = JSONObject.quote(json.toString())
        runOnUiThread { web.evaluateJavascript("window.onNativeSteps && window.onNativeSteps($arg)", null) }
    }
}
