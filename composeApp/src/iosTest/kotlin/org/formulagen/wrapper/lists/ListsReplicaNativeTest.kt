@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.lists

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises startup against the iPhone driver, including connection pragmas. */
class ListsReplicaNativeTest {
    @Test fun opensAndSavesWithNativeDriver() = runTest {
        val driver = NativeSqliteDriver(ListsJournalSchema, "replica-startup-test", onConfiguration = { it.copy(inMemory = true) })
        try {
            val store = ListsReplicaSql(driver)
            val scope = ListsJournalScope("https://hovel.test", "owner-a")
            val value = """{"revision":1,"cursor":"0","ready":true,"records":[],"operations":[],"acknowledged":[],"purged":[]}"""
            assertNull(store.read(scope))
            store.replace(scope, 0, value)
            assertEquals(value, store.read(scope))
        } finally { driver.close() }
    }

    @Test fun fileBackedReplicaSurvivesReopening() = runTest {
        val directory = NSTemporaryDirectory()
        val name = "lists-test-${NSUUID().UUIDString}.db"
        fun open() = ListsReplicaSql(NativeSqliteDriver(ListsJournalSchema, name, onConfiguration = {
            it.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = directory))
        }))
        val scope = ListsJournalScope("https://hovel.test", "owner-a")
        val value = """{"revision":1,"cursor":"7","ready":true,"records":[],"operations":[],"acknowledged":[],"purged":[]}"""
        try {
            val first = open()
            try { first.replace(scope, 0, value) } finally { first.close() }
            val reopened = open()
            try { assertEquals(value, reopened.read(scope)) } finally { reopened.close() }
        } finally {
            for (suffix in listOf("", "-wal", "-shm"))
                NSFileManager.defaultManager.removeItemAtPath(directory + name + suffix, null)
        }
    }
}
