package com.btcpayapp.data.session

import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.core.store.saved
import com.btcpayapp.data.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository internal constructor(private val file: EncryptedJsonFile<AppSettings>) {

    val settings: StateFlow<AppSettings> = file.state
    val loaded: StateFlow<Boolean> = file.loaded

    /** True while the stored settings exist but cannot be read yet. See [EncryptedJsonFile.unreadable]. */
    val unreadable: StateFlow<Boolean> = file.unreadable

    /**
     * True when the stored settings could not be opened and were reset, until
     * the next change is saved. The app lock is then on where the phone has a
     * screen lock (see AppGraph), and the user should be told why.
     * See [EncryptedJsonFile.lost].
     */
    val lost: StateFlow<Boolean> = file.lost

    /**
     * Returns false when the change was not saved: the write failed, or the
     * stored settings could not be read (writing then would replace them with
     * defaults). Never throws, so a toggle cannot end the process.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): Boolean =
        saved("SettingsRepository") { file.update(transform) }

    suspend fun reset() = file.clear()
}
