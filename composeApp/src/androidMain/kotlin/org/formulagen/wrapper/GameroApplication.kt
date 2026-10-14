package org.formulagen.wrapper

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.initialize
import org.formulagen.wrapper.di.initializationKoin
import org.formulagen.wrapper.platform.AppContext

/** Starts process-wide services once, before any activity is created. */
class GameroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(context = this)
        AppContext.init(this)
        initializationKoin()
    }
}
