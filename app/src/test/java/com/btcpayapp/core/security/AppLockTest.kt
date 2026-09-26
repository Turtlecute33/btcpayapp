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
        withSettings(AppLockMode.Biometric) { settings, scope ->
            val lock = AppLock(settings, scope)
            withTimeout(2000) { settings.loaded.first { it } }
            assertTrue(lock.locked.value)
        }
    }

    @Test fun `lock is released only after disabled settings finish loading`() = runBlocking {
        withSettings(AppLockMode.Off) { settings, scope ->
            val lock = AppLock(settings, scope)
            withTimeout(2000) { lock.locked.first { !it } }
            assertTrue(settings.loaded.value)
        }
    }

    private suspend fun withSettings(mode: AppLockMode, block: suspend (SettingsRepository, CoroutineScope) -> Unit) {
        val dir = Files.createTempDirectory("btcpay-lock-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val file = EncryptedJsonFile(File(dir, "settings"), AppSettings.serializer(), AppSettings(appLock = mode), ApiJson.instance, scope)
            block(SettingsRepository(file), scope)
        } finally {
            scope.cancel()
            dir.delete()
        }
    }
}
