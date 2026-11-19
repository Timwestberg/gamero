package org.formulagen.wrapper.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens `link.open` URLs with an `ACTION_VIEW` intent from the application
 * context, so it needs no activity. With no app to handle the URL,
 * `startActivity` throws `ActivityNotFoundException` and the call answers
 * `failed`.
 *
 * On Android 10+ a call made while the app has no visible window can answer
 * `ok` without opening anything: the system drops background activity starts
 * silently, so `startActivity` returns normally. iOS reports the same case as
 * `failed`. A foreground check before the call (for example
 * `ActivityManager.getMyMemoryState`) is a candidate once lifecycle
 * (FOR-125 part 3) lands.
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
