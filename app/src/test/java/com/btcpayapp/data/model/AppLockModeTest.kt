package com.btcpayapp.data.model

import com.btcpayapp.data.api.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored lock mode must never read back as off unless it was stored as off. */
class AppLockModeTest {

    private val json = ApiJson.instance

    @Test
    fun `the lock mode is stored by name and reads back`() {
        for (mode in AppLockMode.entries) {
            val stored = json.encodeToString(AppSettings.serializer(), AppSettings(appLock = mode))
            assertTrue(stored, stored.contains("\"appLock\":\"${mode.name}\""))
            assertEquals(mode, json.decodeFromString(AppSettings.serializer(), stored).appLock)
        }
    }

    @Test
    fun `a lock mode this build does not know reads as on`() {
        val settings = json.decodeFromString(AppSettings.serializer(), """{"appLock":"FaceOnly"}""")
        assertEquals(AppLockMode.Biometric, settings.appLock)
    }

    @Test
    fun `an unknown value elsewhere still falls back without touching the lock`() {
        val settings = json.decodeFromString(AppSettings.serializer(), """{"appLock":"Biometric","themeMode":"Sepia"}""")
        assertEquals(AppLockMode.Biometric, settings.appLock)
        assertEquals(ThemeMode.System, settings.themeMode)
    }
}
