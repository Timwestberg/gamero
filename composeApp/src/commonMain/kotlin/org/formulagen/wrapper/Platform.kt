package org.formulagen.wrapper

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform