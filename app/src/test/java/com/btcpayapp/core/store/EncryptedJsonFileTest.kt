package com.btcpayapp.core.store

import com.btcpayapp.data.api.ApiJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncryptedJsonFileTest {
    @Test fun `update before startup read preserves existing data`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        try {
            file.writeText("41")
            val store = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
                encrypt = { it.copyOf() }, decrypt = { it.copyOf() })
            assertEquals(42, store.update { it + 1 })
            runCurrent()
            assertEquals(42, store.state.value)
            assertEquals("42", file.readText())
        } finally { file.delete(); dir.delete() }
    }

    @Test fun `clear before startup read cannot restore the removed data`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        try {
            file.writeText("41")
            val store = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
                encrypt = { it.copyOf() }, decrypt = { it.copyOf() })
            store.clear()
            runCurrent()
            assertEquals(0, store.state.value)
            assertFalse(file.exists())
        } finally { file.delete(); dir.delete() }
    }

    @Test fun `failed write keeps previously committed state`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        try {
            file.writeText("41")
            val store = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
                encrypt = { throw java.io.IOException("simulated unavailable key") }, decrypt = { it.copyOf() })
            val result = runCatching { store.update { it + 1 } }
            assertTrue(result.isFailure)
            assertEquals(41, store.state.value)
            assertEquals("41", file.readText())
        } finally { file.delete(); dir.delete() }
    }
}
