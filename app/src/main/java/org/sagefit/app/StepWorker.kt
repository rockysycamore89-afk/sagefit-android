package org.sagefit.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs about every 15 minutes, even when SageFit is minimized or closed, to save the step count. */
class StepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (canCount(applicationContext)) StepStore.sample(applicationContext)
        return Result.success()
    }

    companion object {
        fun canCount(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** 15 minutes is the shortest repeat Android allows. It keeps going after restarts. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sagefit-steps", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
