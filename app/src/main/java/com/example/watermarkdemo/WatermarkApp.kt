package com.example.watermarkdemo

import android.app.Application
import org.opencv.android.OpenCVLoader
import timber.log.Timber

class WatermarkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        if (!OpenCVLoader.initLocal()) {
            Timber.e("OpenCV native library failed to load")
        }
    }
}
