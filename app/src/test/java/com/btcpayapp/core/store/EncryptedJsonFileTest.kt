package com.btcpayapp.core.store

import com.btcpayapp.data.api.ApiJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    // The Keystore daemon is routinely not up yet just after boot. The first
    // read then fails, and before retry() nothing read the file again.
    @Test fun `a read that failed once for a temporary reason loads after retry`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        try {
            file.writeText("41")
            var failures = 1
            val store = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
                encrypt = { it.copyOf() },
                decrypt = {
                    if (failures-- > 0) throw java.security.KeyStoreException("Connection to keystore failed")
                    it.copyOf()
                })
            store.unreadable.first { it }
            assertFalse(store.loaded.value)
            assertTrue(store.retry())
            assertTrue(store.loaded.value)
            assertFalse(store.unreadable.value)
            assertEquals(41, store.state.value)
            assertTrue("the unread file must survive", file.exists())
        } finally { file.delete(); dir.delete() }
    }

    // The user leaves the screen while its save runs. The file is already
    // renamed into place, so memory must follow it, or the next update writes
    // the old document back over the change.
    @Test fun `an update cancelled during the write still publishes the new state`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        try {
            file.writeText("41")
            lateinit var caller: Job
            val store = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
                encrypt = { plaintext -> caller.cancel(); plaintext.copyOf() },
                decrypt = { it.copyOf() })
            store.loaded.first { it }
            caller = launch { store.update { it + 1 } }
            caller.join()
            assertTrue(caller.isCancelled)
            assertEquals("42", file.readText())
            assertEquals(42, store.state.value)
        } finally { file.delete(); dir.delete() }
    }

    // The copy moved aside is what tells the next start that the document is
    // still lost. Otherwise only the first start would use the recovered
    // value, and the second would open the settings with the app lock off.
    @Test fun `a document that cannot be decoded starts from the recovered value until the next save`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        fun openStore() = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
            encrypt = { it.copyOf() }, decrypt = { it.copyOf() }, recovered = { 7 })
        try {
            file.writeText("not a number")
            val first = openStore()
            first.loaded.first { it }
            assertEquals(7, first.state.value)
            assertTrue(first.lost.value)
            assertFalse(file.exists())
            assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".corrupt") })

            val second = openStore()
            second.loaded.first { it }
            assertEquals(7, second.state.value)
            assertTrue(second.lost.value)

            second.update { it + 1 }
            assertFalse(second.lost.value)
            val third = openStore()
            third.loaded.first { it }
            assertEquals(8, third.state.value)
            assertFalse(third.lost.value)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `clear also removes the copies moved aside`() = runTest {
        val dir = Files.createTempDirectory("btcpay-store-test").toFile()
        val file = File(dir, "document")
        fun openStore() = EncryptedJsonFile(file, Int.serializer(), 0, ApiJson.instance, backgroundScope,
            encrypt = { it.copyOf() }, decrypt = { it.copyOf() }, recovered = { 7 })
        try {
            file.writeText("not a number")
            val store = openStore()
            store.loaded.first { it }
            store.clear()
            assertEquals(0, dir.listFiles()!!.size)
            assertFalse(store.lost.value)
            val next = openStore()
            next.loaded.first { it }
            assertEquals(0, next.state.value)
            assertFalse(next.lost.value)
        } finally { dir.deleteRecursively() }
    }
}
