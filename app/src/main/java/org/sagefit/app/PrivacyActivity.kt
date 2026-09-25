package org.sagefit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Opens SageFit's privacy policy (Health Connect requires one before it shares step data). */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.SITE + "privacy.html")))
        finish()
    }
}
