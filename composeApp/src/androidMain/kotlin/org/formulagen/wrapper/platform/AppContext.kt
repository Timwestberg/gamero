package org.formulagen.wrapper.platform

import android.content.Context

object AppContext {
    @Volatile
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx == null) {
            ctx = context.applicationContext
        }
    }

    fun get(): Context? = ctx
}



