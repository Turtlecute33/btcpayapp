package com.btcpayapp.core.store

import com.btcpayapp.core.crypto.Keystore
import com.btcpayapp.core.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.io.FileOutputStream
import javax.crypto.AEADBadTagException

/**
 * A small, transactional, encrypted document store.
 *
 * This replaces DataStore/Room for the two documents this app persists. Both
 * are a few kilobytes, both are read whole and written whole, and one of them
 * holds API keys — so a general-purpose database with its own journal files,
 * WAL, and temp copies is more surface than the job needs.
 *
 * Properties:
 *  - **Confidential.** The whole document is AES-256-GCM sealed under a
 *    hardware-backed Keystore key (see [Keystore]). Nothing readable is written
 *    to disk, so a filesystem dump or an errant backup yields ciphertext.
 *  - **Tamper-evident.** GCM authenticates; a modified file fails to open
 *    rather than silently loading attacker-chosen values.
 *  - **Atomic.** Writes go to a temp file, are fsync'd, then renamed over the
 *    target. A crash mid-write leaves the previous document intact — losing a
 *    credential store to a half-written file is not an acceptable failure mode.
 *  - **Serialised.** A mutex orders concurrent updates, so a read-modify-write
 *    from two screens cannot lose an edit.
 */
internal class EncryptedJsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    private val json: Json,
    scope: CoroutineScope,
    private val encrypt: (ByteArray) -> ByteArray = { Keystore.encrypt(Keystore.secretKey(Keystore.ALIAS_VAULT), it) },
    private val decrypt: (ByteArray) -> ByteArray = { Keystore.decrypt(Keystore.secretKey(Keystore.ALIAS_VAULT), it) },
    /**
     * The document to start from while [lost] is true, instead of
     * [defaultValue]. The settings use it so that settings which cannot be
     * opened do not also turn the app lock off.
     */
    private val recovered: () -> T = { defaultValue },
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(defaultValue)
    private val _loaded = MutableStateFlow(false)
    private val _unreadable = MutableStateFlow(false)
    private val _lost = MutableStateFlow(false)
    private var initialized = false

    /** Current document. Emits [defaultValue] until [loaded] turns true. */
    val state: StateFlow<T> = _state.asStateFlow()

    /** False until the first read from disk finishes. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * True when the document exists but could not be opened for a reason that
     * may be temporary. While this is set, [update] refuses to write, because
     * persisting the in-memory default would destroy the very data that could
     * not be read.
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /**
     * True when the stored document could not be opened for good and was moved
     * aside (see [quarantine]), and nothing has been saved since. Read from the
     * files, so it survives a restart: a document lost during background sync
     * still reaches the user. [update] and [clear] end it.
     */
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    init {
        scope.launch {
            // The read happens *inside* the mutex. Outside it, an `update()`
            // that landed first would transform the default rather than the
            // stored document and persist that, and the arriving read would
            // then be discarded by the `if (!_loaded.value)` guard — silently
            // replacing the real document with a default-derived one.
            if (retry()) return@launch
            // A Retryable read (the Keystore daemon not up yet after boot, a
            // disk error) is tried again after 1, 2, 4, 8 and 16 s. Without
            // this nothing reads again until an `update()`, and nothing updates
            // while the UI has no document to draw. After the last
            // step only [retry] and [update] read again, so a key that is
            // broken for good does not keep the process busy.
            for (seconds in RETRY_BACKOFF_SECONDS) {
                delay(seconds * 1_000L)
                if (retry()) return@launch
            }
        }
    }

    /**
     * Reads the document again if it has not been loaded yet. Returns true
     * once it is loaded. Behind the "Try again" of an unreadable-storage state.
     */
    suspend fun retry(): Boolean = mutex.withLock {
        initialize()
        initialized
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        initialize()
        check(!_unreadable.value) {
            "refusing to write ${file.name}: the existing document could not be read"
        }
        val next = transform(_state.value)
        // Write and publish as one step that cancellation cannot split. With a
        // plain `withContext(IO)`, a caller cancelled mid-write (the user left
        // the screen) gets the file renamed into place and then a
        // CancellationException on resume, so memory keeps the old document
        // and the next update writes it back over the change.
        withContext(NonCancellable + Dispatchers.IO) {
            write(next)
            _state.value = next
            _loaded.value = true
            _lost.value = false
        }
        next
    }

    /** Deletes the document, and any copy moved aside, and resets to the default. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(!tempFile().exists() || tempFile().delete()) { "Could not remove temporary document" }
            check(!file.exists() || file.delete()) { "Could not remove document" }
            // Left behind, a copy would make the next start take the document for lost.
            check(quarantinedCopies().all { it.delete() }) { "Could not remove a quarantined document" }
        }
        _state.value = defaultValue
        // Must be set, otherwise an initial read still queued behind this mutex
        // would restore the wiped document straight back into memory.
        _loaded.value = true
        _unreadable.value = false
        _lost.value = false
        initialized = true
    }

    /** Called under the mutex even when an update wins the startup race. */
    private suspend fun initialize() {
        if (initialized) return
        when (val outcome = withContext(Dispatchers.IO) { read() }) {
            is ReadOutcome.Value -> {
                _state.value = outcome.value
                _loaded.value = true
                initialized = true
                _unreadable.value = false
            }
            ReadOutcome.Retryable -> _unreadable.value = true
        }
    }

    private sealed interface ReadOutcome<out T> {
        class Value<T>(val value: T) : ReadOutcome<T>

        /** The keystore daemon was unavailable, the disk errored, and so on. */
        data object Retryable : ReadOutcome<Nothing>
    }

    private fun read(): ReadOutcome<T> {
        if (!file.exists()) {
            // A copy moved aside, and nothing saved since: the document is
            // still lost. Only the first start would know it otherwise, and
            // the next one would open the settings with the app lock off.
            val lost = quarantinedCopies().isNotEmpty()
            _lost.value = lost
            return ReadOutcome.Value(if (lost) recovered() else defaultValue)
        }
        return try {
            val sealed = file.readBytes()
            if (sealed.isEmpty()) return ReadOutcome.Value(defaultValue)
            val plaintext = decrypt(sealed)
            val value = try {
                json.decodeFromString(serializer, plaintext.toString(Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
            ReadOutcome.Value(value)
        } catch (e: AEADBadTagException) {
            // GCM authentication failed: the file was modified, or the key was
            // invalidated (biometric re-enrolment, device reset). The content
            // is genuinely unrecoverable.
            quarantine(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            quarantine(e)
        } catch (e: SerializationException) {
            // A schema change this build cannot parse. Keep the bytes — a later
            // build may be able to migrate them.
            quarantine(e)
        } catch (e: Exception) {
            // Anything else is treated as temporary. This is the important
            // case: `KeyStoreException("Connection to keystore failed")` is
            // routine just after boot and during direct-boot starts, and a
            // blanket `catch (Exception) { file.delete() }` would respond to it
            // by permanently destroying every paired server and API key.
            Log.e("EncryptedJsonFile", e) { "could not open ${file.name}; keeping it and retrying later" }
            ReadOutcome.Retryable
        }
    }

    /**
     * Never `delete()`. The bytes are useless without the Keystore key, but
     * renaming keeps the failure diagnosable and leaves open the possibility of
     * recovery, at a cost of a few kilobytes. Only [clear] deletes the copies.
     * A file that cannot be moved aside is kept and read again later.
     */
    private fun quarantine(cause: Exception): ReadOutcome<T> {
        Log.e("EncryptedJsonFile", cause) { "${file.name} could not be opened; quarantining" }
        val moved = runCatching {
            file.renameTo(File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}$QUARANTINED"))
        }.getOrDefault(false)
        if (!moved) return ReadOutcome.Retryable
        _lost.value = true
        return ReadOutcome.Value(recovered())
    }

    private fun quarantinedCopies(): List<File> =
        file.parentFile?.listFiles { copy -> copy.name.startsWith("${file.name}.") && copy.name.endsWith(QUARANTINED) }
            ?.toList()
            .orEmpty()

    private fun write(value: T) {
        val plaintext = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        val sealed = try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }

        val temp = tempFile()
        FileOutputStream(temp).use { out ->
            out.write(sealed)
            out.flush()
            // Durability before visibility: without this the rename can be
            // ordered ahead of the data on some filesystems.
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            error("could not commit ${file.name}")
        }
    }

    private fun tempFile() = File(file.parentFile, "${file.name}.tmp")

    private companion object {
        val RETRY_BACKOFF_SECONDS = longArrayOf(1, 2, 4, 8, 16)
        const val QUARANTINED = ".corrupt"
    }
}

/**
 * Runs a document write and reports whether it was saved, so a failed write
 * (disk full, a Keystore fault, an unreadable document) becomes a message the
 * caller shows instead of an exception that ends the process.
 * Cancellation still propagates. Only the exception class is logged: messages
 * can carry file names and key details.
 */
internal suspend fun saved(tag: String, write: suspend () -> Unit): Boolean = try {
    write()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(tag) { "not saved: ${e.javaClass.simpleName}" }
    false
}
