package com.colorup.app

import android.app.Application
import com.colorup.app.ui.ArtworkInk

class ColorUpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ArtworkInk.initialize(assets)
    }
}
