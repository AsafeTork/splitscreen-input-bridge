package com.example.splitscreenmanager.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class PermissionManager(private val context: Context) {

    private val _isShizukuGranted = MutableStateFlow(false)
    val isShizukuGranted: StateFlow<Boolean> = _isShizukuGranted.asStateFlow()

    private val _isBatteryExempt = MutableStateFlow(false)
    val isBatteryExempt: StateFlow<Boolean> = _isBatteryExempt.asStateFlow()

    fun updateStatus() {
        _isShizukuGranted.value = Shizuku.pingBinder() && 
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _isBatteryExempt.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestShizuku() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(0)
        }
    }

    fun requestBatteryExemption() {
        if (_isBatteryExempt.value) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
