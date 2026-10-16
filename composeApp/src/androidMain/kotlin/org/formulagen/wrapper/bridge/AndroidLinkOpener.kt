package org.formulagen.wrapper.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens `link.open` URLs with an `ACTION_VIEW` intent from the application
 * context, so it needs no activity. With no app to handle the URL,
 * `startActivity` throws `ActivityNotFoundException` and the call answers
 * `failed`.
 */
internal class AndroidLinkOpener(context: Context) : LinkOpener {
    private val context = context.applicationContext

    override suspend fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
