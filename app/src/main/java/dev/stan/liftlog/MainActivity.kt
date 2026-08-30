package dev.stan.liftlog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.stan.liftlog.data.Repo
import dev.stan.liftlog.timer.SessionManager
import dev.stan.liftlog.ui.AppRoot
import dev.stan.liftlog.ui.LiftLogTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the log works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Repo.init(this)
        SessionManager.restore(this)
        SessionManager.ensureServiceRunning(this)
        askForNotifications()

        setContent {
            LiftLogTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The lock-screen buttons may have logged sets while the UI was gone.
        SessionManager.refreshCounts()
    }

    /** Without this the lock-screen controls have nowhere to live on Android 13+. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
