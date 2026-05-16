package com.example.splitscreenmanager

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.splitscreenmanager.ui.AppListScreen
import com.example.splitscreenmanager.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register Shizuku permission result listener to refresh ViewModel instantly
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                viewModel.refreshPermissions(this)
            }
        }
        setContent {
            MaterialTheme {
                val isShizukuAvailable by viewModel.isShizukuAvailable.collectAsState()
                val hasSecureSettingsPermission by viewModel.hasSecureSettingsPermission.collectAsState()
                val isBatteryExempt by viewModel.isBatteryExempt.collectAsState()
                val systemLogs by viewModel.systemLogs.collectAsState()

                // Main App UI
                AppListScreen(
                    viewModel = viewModel,
                    context = this,
                    isShizukuAvailable = isShizukuAvailable,
                    hasSecureSettingsPermission = hasSecureSettingsPermission,
                    isBatteryExempt = isBatteryExempt,
                    systemLogs = systemLogs
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure permissions are refreshed on resume via DefaultLifecycleObserver logic
        viewModel.refreshPermissions(this)
    }

}