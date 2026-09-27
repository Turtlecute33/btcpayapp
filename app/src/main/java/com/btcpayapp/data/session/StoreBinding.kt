package com.btcpayapp.data.session

import com.btcpayapp.data.api.dto.StoreData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The store a store screen works on, fixed once it is known.
 *
 * It is the active store when the screen opened. After a cold start the back
 * stack comes back before the store list, so it is then the first store to
 * load. It never follows a later switch: the shell closes every store screen
 * when the store changes, so a screen can only show, and write to, the store
 * it opened in. Reading the session's active store at the time of each call
 * would send an edit made on store A to store B.
 */
internal class StoreBinding(private val session: SessionManager) {
    private var bound: StoreData? = session.activeStore.value

    val store: StoreData? get() = bound ?: session.activeStore.value?.also { bound = it }

    val id: String? get() = store?.id

    /** For dialogs and prompts that must say which store a change hits. */
    val name: String get() = store?.let { it.name.ifBlank { it.id } } ?: "this store"

    /**
     * When no store is known yet (a cold start), binds the first one to load
     * and then runs [block], typically the screen's load. Does nothing when a
     * store is already bound.
     */
    fun retryWhenKnown(scope: CoroutineScope, block: () -> Unit) {
        if (store != null) return
        scope.launch {
            val first = session.activeStore.filterNotNull().first()
            if (bound == null) bound = first
            block()
        }
    }
}
