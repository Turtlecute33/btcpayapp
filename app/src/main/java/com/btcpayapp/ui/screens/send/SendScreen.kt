package com.btcpayapp.ui.screens.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.ui.LocalAppGraph
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.nav.ScanPurpose
import com.btcpayapp.ui.screens.lightning.NoStoreSelectedState
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.theme.Motion

/** The two ways money leaves a BTCPay store. */
enum class SendRail { OnChain, Lightning }

/**
 * Which rail a pasted or scanned payload has to travel on, or null when the
 * text does not say yet — a half-typed address, or something this app cannot
 * read at all. Null means "leave the sender where they are"; switching the form
 * out from under someone mid-keystroke is worse than waiting.
 *
 * [preferOnChain] settles the one code that is honestly both: a BIP21 URI
 * carrying an address *and* a `lightning=` invoice. The address wins when there
 * is an on-chain wallet to pay it from, because that is the half every wallet
 * can read; the invoice is then offered as the alternative rather than lost.
 */
internal fun railFor(payload: ScannedPayload, preferOnChain: Boolean): SendRail? = when (payload) {
    is ScannedPayload.Bolt11 -> SendRail.Lightning
    is ScannedPayload.Lnurl -> SendRail.Lightning
    is ScannedPayload.BitcoinAddress -> SendRail.OnChain
    is ScannedPayload.Bip21 -> when {
        // A BIP21 with no address is a Lightning invoice in an envelope.
        payload.address.isBlank() -> SendRail.Lightning
        payload.lightning == null -> SendRail.OnChain
        preferOnChain -> SendRail.OnChain
        else -> SendRail.Lightning
    }

    else -> null
}

/** What the screen is showing: the form, or what came back from the node. */
private enum class SendPhase { NoRail, NoStore, Paid, Form }

/**
 * Which form is on screen, and whether it is a form at all.
 *
 * The rail and the reason it cannot be used are one value rather than two
 * because they drive the same swap: switching from a live Lightning form to the
 * watch-only explanation is the same kind of change as switching rails, and
 * animating them separately would make the two cross-fades collide.
 */
private enum class SendSlot { OnChain, OnChainWatchOnly, Lightning, LightningReadOnly }

/**
 * One Send screen for both rails.
 *
 * The rail is a consequence of the destination, not a question asked before it:
 * a BOLT11 invoice can only be paid over Lightning and an address can only be
 * paid on-chain, so pasting either one settles it. The switch above the field
 * is for the sender who wants to choose before they have something to paste,
 * and for the store that has both rails and a code that carries both.
 *
 * Both halves keep their own view model and their own idea of a destination.
 * Pasting an address, thinking better of it and switching to Lightning leaves
 * the address where it was rather than trying to reinterpret it as an invoice.
 */
@Composable
fun SendScreen(
    paymentMethodId: String?,
    lightningCryptoCode: String?,
    serverNode: Boolean,
    startOnLightning: Boolean,
    prefill: String?,
    scanResult: String?,
    onScan: (String) -> Unit,
    onBack: () -> Unit,
    onSent: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val settings = LocalSettings.current

    val onChainViewModel = paymentMethodId?.let { id ->
        appViewModel(key = "send-$id") { WalletSendViewModel(it, id) }
    }
    val lightningViewModel = lightningCryptoCode?.let { code ->
        appViewModel(key = "ln-send:$code:$serverNode") { LightningSendViewModel(it, code, serverNode) }
    }
    val onChainState = onChainViewModel?.state?.collectAsStateWithLifecycle()?.value
    val lightningState = lightningViewModel?.state?.collectAsStateWithLifecycle()?.value

    val hasOnChain = onChainViewModel != null
    val hasLightning = lightningViewModel != null
    val onChainCryptoCode = remember(paymentMethodId) { paymentMethodId?.let(::cryptoCodeOf).orEmpty() }
    // Read once. This is a property of the paired API key, not of the store's
    // answer to any particular request, so it cannot change while the form is
    // open. The permission is a store one, so it says nothing about the
    // server's own node — that form is left alone rather than wrongly refused.
    val canUseNode = remember { serverNode || graph.session.canSpendLightning() }

    var rail by rememberSaveable {
        mutableStateOf(
            when {
                !hasOnChain -> SendRail.Lightning
                !hasLightning -> SendRail.OnChain
                startOnLightning -> SendRail.Lightning
                else -> SendRail.OnChain
            },
        )
    }
    // A code this store cannot act on, said plainly under the field rather than
    // by refusing the paste and leaving the sender to guess why.
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    // The raw BIP21 of a code that carried both an address and an invoice, kept
    // so that switching rails can hand the other half over.
    var unified by rememberSaveable { mutableStateOf<String?>(null) }

    /** Files a destination under the rail that can actually pay it. */
    fun receive(text: String) {
        val payload = ScanParser.parse(text)
        notice = null
        unified = (payload as? ScannedPayload.Bip21)
            ?.takeIf { it.address.isNotBlank() && it.lightning != null }
            ?.raw

        val wanted = railFor(payload, preferOnChain = hasOnChain)
        val target = when {
            wanted == null -> rail
            wanted == SendRail.Lightning && !hasLightning -> {
                notice = "That is a Lightning invoice, and this store has no Lightning node."
                rail
            }

            wanted == SendRail.OnChain && !hasOnChain -> {
                notice = "That is an on-chain address, and this store has no on-chain wallet."
                rail
            }

            else -> wanted
        }
        val previous = rail
        rail = target

        // Text that turned out to belong to the other rail does not stay behind
        // in the field it was typed into. The whole field classified as
        // something this rail cannot pay, so a copy left here is only a
        // destination the sender will find again later and not recognise.
        if (target != previous) {
            when (previous) {
                SendRail.OnChain -> onChainViewModel?.setDestination("")
                SendRail.Lightning -> lightningViewModel?.setBolt11("")
            }
        }

        // The destination is handed over whole unless it is an envelope worth
        // opening. `applyScanned` takes the amount out of a BIP21 as well as the
        // address, which is the entire reason to unwrap one.
        when (target) {
            SendRail.OnChain ->
                if (payload is ScannedPayload.Bip21 && payload.address.isNotBlank()) {
                    onChainViewModel?.applyScanned(text)
                } else {
                    onChainViewModel?.setDestination(text)
                }

            SendRail.Lightning -> {
                val wrapped = (payload as? ScannedPayload.Bip21)?.lightning
                lightningViewModel?.setBolt11(wrapped ?: text)
                // Kept in the field and explained underneath, rather than
                // dropped: this rail is the right one for an LNURL, and the
                // node still cannot be asked to pay it.
                if (payload is ScannedPayload.Lnurl) lightningViewModel?.noteLnurl()
            }
        }
    }

    fun select(next: SendRail) {
        if (next == rail) return
        // A rail this store does not have has no form to show, so nothing may
        // put the screen on one — not the switch, and not the offer to use the
        // other half of a code.
        if (next == SendRail.Lightning && !hasLightning) return
        if (next == SendRail.OnChain && !hasOnChain) return
        rail = next
        notice = null
        // The other half of a unified code, carried across rather than retyped.
        unified?.let { raw ->
            val payload = ScanParser.parse(raw) as? ScannedPayload.Bip21 ?: return@let
            when (next) {
                SendRail.Lightning -> payload.lightning?.let { lightningViewModel?.setBolt11(it) }
                // The on-chain half is the address *and* the amount, which is
                // why this goes through the view model rather than the field.
                SendRail.OnChain -> onChainViewModel?.applyScanned(raw)
            }
        }
    }

    LaunchedEffect(prefill) { prefill?.takeIf { it.isNotBlank() }?.let(::receive) }
    LaunchedEffect(scanResult) { scanResult?.takeIf { it.isNotBlank() }?.let(::receive) }

    val subtitle = when (rail) {
        SendRail.OnChain -> chainSubtitle(onChainCryptoCode)
        SendRail.Lightning -> chainSubtitle(
            cryptoCode = lightningCryptoCode.orEmpty(),
            prefix = "Server node".takeIf { serverNode },
        )
    }

    AppScreen(title = "Send", subtitle = subtitle, onBack = onBack) { padding ->
        val phase = when {
            !hasOnChain && !hasLightning -> SendPhase.NoRail
            lightningState?.noStore == true -> SendPhase.NoStore
            lightningState?.result != null -> SendPhase.Paid
            else -> SendPhase.Form
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "send") { shown ->
            when (shown) {
                SendPhase.NoRail -> EmptyState(
                    title = "Nothing to send from",
                    description = "This store has neither an on-chain wallet nor a Lightning node. " +
                        "Add a derivation scheme or a node in its payment settings first.",
                    icon = Icons.Rounded.AccountBalanceWallet,
                )

                SendPhase.NoStore -> NoStoreSelectedState()

                // Read through `?.let` rather than `!!`. The branch on its way
                // out of a swap stays composed while it fades, and "Pay
                // another" has already cleared the result it was built from.
                SendPhase.Paid -> lightningState?.result?.let { payment ->
                    PaymentResult(
                        payment = payment,
                        unit = settings.bitcoinUnit,
                        onAgain = { lightningViewModel.reset() },
                        onDone = onBack,
                        modifier = Modifier.padding(padding),
                    )
                }

                SendPhase.Form -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding),
                ) {
                    if (hasOnChain && hasLightning) {
                        RailSelector(rail = rail, onSelect = ::select)
                    }

                    val slot = when {
                        rail == SendRail.OnChain && onChainState?.blockedUpFront == true ->
                            SendSlot.OnChainWatchOnly

                        rail == SendRail.OnChain -> SendSlot.OnChain
                        canUseNode -> SendSlot.Lightning
                        else -> SendSlot.LightningReadOnly
                    }

                    AnimatedSwap(slot, label = "rail") { shownSlot ->
                        Column {
                            when (shownSlot) {
                                SendSlot.OnChainWatchOnly -> OnChainWatchOnlyState()

                                SendSlot.LightningReadOnly -> EmptyState(
                                    title = "Read only",
                                    description = "This app's API key was granted without permission " +
                                        "to use the store's Lightning node, so it cannot pay an " +
                                        "invoice from here.\n\nThe balance and the history still " +
                                        "read; paying needs a key with the use-node permission.",
                                    icon = Icons.Rounded.Visibility,
                                )

                                SendSlot.OnChain -> if (onChainState != null) {
                                    DestinationField(
                                        label = "Destination",
                                        value = onChainState.destination,
                                        placeholder = "Address or bitcoin: URI",
                                        enabled = !onChainState.sending,
                                        onValueChange = ::receive,
                                        onScan = { onScan(scanPurpose(hasOnChain, hasLightning)) },
                                    )
                                    // The other half is only worth mentioning to
                                    // a store that could pay it.
                                    val alternative = unified?.takeIf { hasLightning }
                                    SendNotice(
                                        text = notice ?: alternative?.let {
                                            "This code also carries a Lightning invoice."
                                        },
                                        actionLabel = "Use Lightning".takeIf { notice == null && alternative != null },
                                        onAction = { select(SendRail.Lightning) },
                                        onDismiss = { notice = null; unified = null },
                                    )
                                    OnChainSendForm(
                                        viewModel = onChainViewModel,
                                        state = onChainState,
                                        cryptoCode = onChainCryptoCode,
                                        onSent = onSent,
                                    )
                                }

                                SendSlot.Lightning -> if (lightningState != null) {
                                    DestinationField(
                                        label = "Invoice",
                                        value = lightningState.bolt11,
                                        placeholder = "lnbc…",
                                        enabled = !lightningState.sending,
                                        error = lightningState.formError,
                                        onValueChange = ::receive,
                                        onScan = { onScan(scanPurpose(hasOnChain, hasLightning)) },
                                    )
                                    val alternative = unified?.takeIf { hasOnChain }
                                    SendNotice(
                                        text = notice ?: alternative?.let {
                                            "This code also carries an on-chain address."
                                        },
                                        actionLabel = "Use on-chain".takeIf { notice == null && alternative != null },
                                        onAction = { select(SendRail.OnChain) },
                                        onDismiss = { notice = null; unified = null },
                                    )
                                    LightningSendForm(
                                        viewModel = lightningViewModel,
                                        state = lightningState,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The scanner is told what this store can act on, so it keeps rejecting codes
 * that cannot be paid from here instead of closing on the wrong thing.
 */
private fun scanPurpose(hasOnChain: Boolean, hasLightning: Boolean): String = when {
    hasOnChain && hasLightning -> ScanPurpose.SEND_DESTINATION
    hasLightning -> ScanPurpose.LIGHTNING_INVOICE
    else -> ScanPurpose.ONCHAIN_DESTINATION
}

@Composable
private fun RailSelector(rail: SendRail, onSelect: (SendRail) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SegmentedButton(
            selected = rail == SendRail.OnChain,
            onClick = { onSelect(SendRail.OnChain) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("On-chain") }
        SegmentedButton(
            selected = rail == SendRail.Lightning,
            onClick = { onSelect(SendRail.Lightning) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Lightning") }
    }
}

@Composable
private fun DestinationField(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onScan: () -> Unit,
    error: String? = null,
) {
    FormField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        singleLine = false,
        enabled = enabled,
        error = error,
        trailingIcon = {
            IconButton(onClick = onScan) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan")
            }
        },
    )
}

/**
 * One line under the destination, for what the app noticed about it.
 *
 * Composed whether or not there is anything to say: a line conjured into the
 * column by an `if` arrives by shoving the form down the screen, which is
 * exactly when the sender is reading the thing it shoved.
 */
@Composable
private fun SendNotice(
    text: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val held = rememberLast(text)
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = held.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
