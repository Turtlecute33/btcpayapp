package com.btcpayapp.ui

import androidx.compose.runtime.Immutable
import com.btcpayapp.data.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The load state every list and detail screen shares.
 *
 * Errors are kept alongside the data rather than replacing it, so a failed
 * refresh shows a banner over the last-known list instead of blanking the
 * screen — which matters when the connection is a Tor circuit that drops every
 * so often.
 */
@Immutable
data class Loadable<out T>(
    val data: T? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
) {
    val isEmpty: Boolean get() = data == null
    val hasData: Boolean get() = data != null
}

fun <T> Loadable<T>.startLoading(refresh: Boolean = false): Loadable<T> =
    copy(loading = !refresh && data == null, refreshing = refresh, error = null)

fun <T> Loadable<T>.success(value: T): Loadable<T> =
    Loadable(data = value, loading = false, refreshing = false, error = null)

fun <T> Loadable<T>.failure(error: ApiException): Loadable<T> =
    copy(loading = false, refreshing = false, error = error)

/**
 * One-shot events: a snackbar, a "copied" confirmation, a navigation request.
 *
 * Deliberately a [SharedFlow] with no replay. A message that arrives while the
 * screen is gone should be lost, not replayed onto an unrelated screen later.
 */
class UiEvents {
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun emit(scope: CoroutineScope, event: UiEvent) {
        scope.launch { _events.emit(event) }
    }

    fun message(scope: CoroutineScope, text: String) = emit(scope, UiEvent.Message(text))

    fun failure(scope: CoroutineScope, error: Throwable) = emit(
        scope,
        UiEvent.Message(
            when (error) {
                is ApiException -> error.userMessage
                else -> error.message ?: "Something went wrong."
            },
        ),
    )
}

sealed interface UiEvent {
    data class Message(val text: String, val actionLabel: String? = null) : UiEvent
    data class Copied(val label: String) : UiEvent
    data object Dismiss : UiEvent
}

/** Convenience for the very common `_state.update { it.copy(...) }` pattern. */
fun <T> MutableStateFlow<T>.mutate(transform: (T) -> T) = update(transform)
