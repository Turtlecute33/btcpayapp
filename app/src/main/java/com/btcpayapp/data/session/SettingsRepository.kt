package com.btcpayapp.data.session

import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.data.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository internal constructor(private val file: EncryptedJsonFile<AppSettings>) {

    val settings: StateFlow<AppSettings> = file.state
    val loaded: StateFlow<Boolean> = file.loaded

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        file.update(transform)
    }

    suspend fun reset() = file.clear()
}
