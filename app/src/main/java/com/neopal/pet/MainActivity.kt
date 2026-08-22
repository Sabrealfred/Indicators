package com.neopal.pet

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neopal.pet.data.Notifier
import com.neopal.pet.ui.NeoPalApp
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoPalTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* reminders are optional */ }

    private var viewModelRef: PetViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeoPalTheme {
                val vm: PetViewModel = viewModel(factory = PetViewModel.Factory)
                LaunchedEffect(vm) { viewModelRef = vm }
                NeoPalApp(viewModel = vm)
            }
        }
        askForNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Fold in whatever happened while the app was away as soon as it is visible again.
        viewModelRef?.onResumed()
    }

    override fun onPause() {
        super.onPause()
        // Stop the foreground clock and flush the save; from here on, time counts as time away.
        viewModelRef?.onPaused()
    }

    /**
     * Asks once, on the first launch that could ask, and then never again.
     *
     * The previous version asked on every cold start. Android answers the second refusal for
     * you — permanently, silently, with no dialog — so re-asking spends the player's two chances
     * on launches where they were not thinking about notifications at all, and the switch is
     * dead for the life of the install with nothing on screen to explain why. Notifier keeps the
     * flag because Notifier is what will have to say "you turned this off in system settings"
     * when the player later goes looking for it.
     */
    private fun askForNotificationPermission() {
        if (!Notifier.shouldRequestPermission(this)) return
        Notifier.markPermissionAsked(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
