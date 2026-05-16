package com.example.splitscreenmanager.model

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val iconBitmap: ImageBitmap? = null,
    val activityName: String? = null
)
