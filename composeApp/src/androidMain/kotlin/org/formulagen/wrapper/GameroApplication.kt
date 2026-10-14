package org.formulagen.wrapper

import android.app.Application
import org.formulagen.wrapper.di.initializationKoin

/** Starts process-wide services once, before any activity is created. */
class GameroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializationKoin()
    }
}
