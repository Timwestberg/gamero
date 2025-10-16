package org.formulagen.wrapper.di

import org.formulagen.wrapper.managers.AnalyticManager
import org.formulagen.wrapper.managers.AppManager
import org.formulagen.wrapper.managers.ExternalLinkManager
import org.formulagen.wrapper.managers.GameManager
import org.formulagen.wrapper.managers.HapticManager
import org.formulagen.wrapper.managers.PermissionManager
import org.formulagen.wrapper.managers.SessionManager
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun initializationKoin() {
    startKoin {
        allowOverride(true)
        modules(
            module {
                single(createdAtStart = false) { AnalyticManager}
                single(createdAtStart = false) { AppManager}
                single(createdAtStart = false) { ExternalLinkManager}
                single(createdAtStart = false) { HapticManager}
                single(createdAtStart = false) { PermissionManager}
                single(createdAtStart = false) { GameManager}
                single(createdAtStart = false) { SessionManager}
            }
        )
    }
    }

