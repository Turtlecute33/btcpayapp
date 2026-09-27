package com.btcpayapp.core.security

import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.session.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppLockTest {
    @Test fun `cold start remains locked when lock is configured`() = runBlocking {
        withSettings(AppSettings(appLock = AppLockMode.Biometric)) { settings, scope ->
            val lock = AppLock(settings, scope)
            withTimeout(2000) { settings.loaded.first { it } }
            assertTrue(lock.locked.value)
        }
    }

    @Test fun `lock is released only after disabled settings finish loading`() = runBlocking {
        withSettings(AppSettings(appLock = AppLockMode.Off)) { settings, scope ->
            val lock = AppLock(settings, scope)
            withTimeout(2000) { lock.locked.first { !it } }
            assertTrue(settings.loaded.value)
        }
    }

    @Test fun `idle lock waits at least a minute`() {
        assertFalse(idleExpired(nowMs = 59_999, lastInteractionMs = 0, lockAfterSeconds = 0, armed = 1))
        assertTrue(idleExpired(nowMs = 60_000, lastInteractionMs = 0, lockAfterSeconds = 0, armed = 1))
    }

    @Test fun `idle lock honours a longer lock delay`() {
        assertFalse(idleExpired(nowMs = 299_999, lastInteractionMs = 0, lockAfterSeconds = 300, armed = 1))
        assertTrue(idleExpired(nowMs = 300_000, lastInteractionMs = 0, lockAfterSeconds = 300, armed = 1))
    }

    @Test fun `idle lock does nothing unless armed`() {
        assertFalse(idleExpired(nowMs = 3_600_000, lastInteractionMs = 0, lockAfterSeconds = 0, armed = 0))
    }

    @Test fun `a customer screen locks after 15 minutes without a touch`() {
        val floor = CUSTOMER_IDLE_FLOOR_SECONDS
        assertFalse(idleExpired(nowMs = 899_999, lastInteractionMs = 0, lockAfterSeconds = 0, armed = 1, floorSeconds = floor))
        assertTrue(idleExpired(nowMs = 900_000, lastInteractionMs = 0, lockAfterSeconds = 0, armed = 1, floorSeconds = floor))
        // A longer lock delay still wins.
        assertFalse(idleExpired(nowMs = 1_799_999, lastInteractionMs = 0, lockAfterSeconds = 1_800, armed = 1, floorSeconds = floor))
    }

    @Test fun `a short trip to the PIN screen of a prompt does not lock`() = runBlocking {
        assertFalse(lockedOnReturn(awaySeconds = 30, duringPrompt = true))
    }

    @Test fun `a long absence during a prompt still locks`() = runBlocking {
        assertTrue(lockedOnReturn(awaySeconds = 300, duringPrompt = true))
    }

    @Test fun `the same short trip without a prompt locks`() = runBlocking {
        assertTrue(lockedOnReturn(awaySeconds = 30, duringPrompt = false))
    }

    /** Unlocked app with "Immediately", sent to the background and brought back [awaySeconds] later. */
    private suspend fun lockedOnReturn(awaySeconds: Long, duringPrompt: Boolean): Boolean {
        var locked = false
        withSettings(AppSettings(appLock = AppLockMode.Biometric, lockAfterSeconds = 0)) { settings, scope ->
            withTimeout(2000) { settings.loaded.first { it } }
            var now = 1_000_000L
            // Built after loading: on the unconfined scope its settings
            // collectors have then run by the time the constructor returns.
            val lock = AppLock(settings, scope) { now }
            lock.markUnlocked()
            Biometrics.promptShowing = duringPrompt
            try {
                lock.onEnterBackground()
            } finally {
                Biometrics.promptShowing = false
            }
            now += awaySeconds * 1000
            lock.onEnterForeground()
            locked = lock.locked.value
        }
        return locked
    }

    private suspend fun withSettings(initial: AppSettings, block: suspend (SettingsRepository, CoroutineScope) -> Unit) {
        val dir = Files.createTempDirectory("btcpay-lock-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val file = EncryptedJsonFile(File(dir, "settings"), AppSettings.serializer(), initial, ApiJson.instance, scope)
            block(SettingsRepository(file), scope)
        } finally {
            scope.cancel()
            dir.delete()
        }
    }
}
