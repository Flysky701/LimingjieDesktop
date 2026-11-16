package com.landosol.toolbox

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.landosol.toolbox.ui.LandosolToolboxApp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Keep the existing 1080p / 320 dpi layout (960 dp wide), including dialog windows.
        // This configuration belongs to the UI activity; game capture and system DPI stay unchanged.
        val metrics = newBase.resources.displayMetrics
        val layoutDensity = maxOf(metrics.widthPixels, metrics.heightPixels) / 960f
        val layoutConfiguration = Configuration().apply {
            densityDpi = (layoutDensity * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
            screenWidthDp = 960
            screenHeightDp = (minOf(metrics.widthPixels, metrics.heightPixels) / layoutDensity).roundToInt()
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        }
        super.attachBaseContext(newBase.createConfigurationContext(layoutConfiguration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LandosolToolboxApp() }
    }
}

