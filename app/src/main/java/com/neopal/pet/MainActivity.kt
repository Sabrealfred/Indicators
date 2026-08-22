package com.neopal.pet

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
