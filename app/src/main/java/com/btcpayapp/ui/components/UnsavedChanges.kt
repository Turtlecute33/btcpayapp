package com.btcpayapp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

/** Use the returned callback for toolbar back as well as the system gesture. */
@Composable
fun confirmDiscardChanges(dirty: Boolean, onBack: () -> Unit): () -> Unit {
    var confirming by remember { mutableStateOf(false) }
    val back: () -> Unit = { if (dirty) confirming = true else onBack() }
    BackHandler(enabled = dirty) { confirming = true }
    if (confirming) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your unsaved changes will be lost.",
            confirmLabel = "Discard",
            onConfirm = { confirming = false; onBack() },
            onDismiss = { confirming = false },
        )
    }
    return back
}
