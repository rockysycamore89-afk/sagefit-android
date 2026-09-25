package org.sagefit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts (or SageFit updates), turn always-on step counting back on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            StepWorker.schedule(ctx)
            StepService.start(ctx)
        }
    }
}
