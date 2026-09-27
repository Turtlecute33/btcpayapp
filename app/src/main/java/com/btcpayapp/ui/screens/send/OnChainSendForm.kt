package com.btcpayapp.ui.screens.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.wallet.SignedTransaction
import com.btcpayapp.core.wallet.scriptPubKeyOf
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.dto.CreateTransactionRequest
import com.btcpayapp.data.api.dto.TransactionDestination
import com.btcpayapp.data.api.dto.WalletUtxoData
import com.btcpayapp.data.api.endpoints.broadcastTransaction
import com.btcpayapp.data.api.endpoints.signTransaction
import com.btcpayapp.data.api.endpoints.walletFeeRate
import com.btcpayapp.data.api.endpoints.walletTransaction
import com.btcpayapp.data.api.endpoints.walletUtxos
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.session.OutcomeHold
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ExpandableSection
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.ReviewLine
import com.btcpayapp.ui.components.SpendAuth
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.groupedAddress
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.screens.wallet.formatAmountInput
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.screens.wallet.unitLabel
import com.btcpayapp.ui.theme.MonospaceStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Next block, roughly an hour, roughly four hours. */
private val FEE_TARGETS = listOf(1, 6, 24)

/** The Greenfield code for a transaction the server's wallet has not indexed (yet). */
private const val TRANSACTION_NOT_FOUND = "transaction-not-found"

/**
 * What the app says when it cannot tell whether the transaction went out.
 *
 * It names the one safe move. The same signed bytes can only ever be mined
 * once, so sending them again cannot pay twice; a *new* payment could, because
 * the server would build it from other coins.
 */
private const val UNCONFIRMED = "We could not confirm this payment. Sending the same transaction again is safe; " +
    "it cannot pay twice. Check the wallet history before you make a new payment."

/**
 * Where an on-chain payment is, from the first keystroke to its answer.
 *
 * The send is three steps, so that the sender approves numbers read from the
 * bytes that go out rather than from the request: the server signs without
 * sending ([Preparing]); the app reads the signed bytes, computes the exact
 * fee and checks that they pay the amount ([Review]); only then are those same
 * bytes broadcast ([Broadcasting]). Every failure after that is settled by
 * looking the txid up ([Checking]), so a lost answer reads as [Unknown], never
 * as a failure to try again.
 */
sealed interface OnChainPhase {
    /** Filling in the form. */
    data object Form : OnChainPhase

    /** The server is signing. Nothing can be sent in this step. */
    data object Preparing : OnChainPhase

    /** Signed and not sent; [WalletSendState.review] is on screen. */
    data object Review : OnChainPhase

    data object Broadcasting : OnChainPhase

    /** Looking the txid up in the wallet, to learn whether it went out. */
    data object Checking : OnChainPhase

    data class Sent(val txid: String) : OnChainPhase

    /**
     * Refused before it could reach the network. [canChange] is false when a
     * changed payment would be refused the same way (the server has no
     * broadcast route).
     */
    data class NotSent(val message: String, val canChange: Boolean) : OnChainPhase

    /** It may be out. Only the same bytes, or a lookup, are offered. */
    data class Unknown(val message: String) : OnChainPhase
}

/** The form is open and nothing signed can have left the phone. */
private val OnChainPhase.editable: Boolean
    get() = this == OnChainPhase.Form || this == OnChainPhase.Preparing || this == OnChainPhase.Review

/** A request is running whose answer the screen must not lose. */
internal val OnChainPhase.isWorking: Boolean
    get() = this == OnChainPhase.Preparing || this == OnChainPhase.Broadcasting || this == OnChainPhase.Checking

/** After the broadcast: the form is gone and only the outcome is on screen. */
internal val OnChainPhase.isOutcome: Boolean
    get() = this == OnChainPhase.Broadcasting || this == OnChainPhase.Checking ||
        this is OnChainPhase.Sent || this is OnChainPhase.NotSent || this is OnChainPhase.Unknown

/** The fee figures the review shows, computed from the signed transaction. */
data class ReviewNumbers(
    /** Inputs minus outputs, in sat. */
    val feeSats: Long,
    /** sat/vB, from [feeSats] and the transaction's own vsize. */
    val feeRate: Double,
    /**
     * The payment output plus [feeSats], in sat. This is what leaves the
     * wallet only if the other output, when there is one, is the wallet's own
     * change, which the app cannot check (see [paidToDestination]).
     */
    val totalSats: Long,
    /**
     * The sender asked to take the fee from the amount, and [totalSats] is
     * not more than that amount. Read from the bytes, not from the request.
     */
    val feeFromAmount: Boolean,
    /** One line in error colour, or null when nothing looks wrong. */
    val warning: String?,
)

/**
 * Each coin's value in sat, keyed the way [SignedTransaction.inputs] names
 * the coins it spends (`txid:vout`).
 *
 * Greenfield writes an outpoint as NBitcoin does, `txid-vout`. A txid is hex,
 * so its only '-' is the separator. Without this no input matches and the fee
 * can never be computed.
 */
internal fun coinValues(utxos: List<WalletUtxoData>): Map<String, Long> =
    utxos.associate { it.outpoint.replace('-', ':').lowercase() to Amounts.btcToSats(it.amount).toLong() }

/**
 * The rate, total and warning for [tx], which pays [feeSats] in fees
 * ([SignedTransaction.feeSats]) and [paidSats] to the destination
 * ([paidToDestination]). The total is read from those two, not from the
 * request. So when the sender asked to take the fee from the amount
 * ([subtractFee]) and the server did not take all of it, the total is higher
 * and a warning says so.
 *
 * [amountSats] is what the sender typed. The other warnings catch the two slips
 * that cost real money: a rate typed with the decimal point in the wrong place
 * (against [nextBlockRate], the server's next-block estimate), and a small
 * payment from a wallet of many small coins.
 */
internal fun reviewOf(
    tx: SignedTransaction,
    feeSats: Long,
    paidSats: Long,
    amountSats: Long,
    subtractFee: Boolean,
    nextBlockRate: Double?,
): ReviewNumbers {
    val rate = feeSats.toDouble() / tx.vsize.coerceAtLeast(1)
    val totalSats = paidSats + feeSats
    val feeFromAmount = subtractFee && totalSats <= amountSats
    // Every slip that applies is named: one warning must not hide another.
    val warning = listOfNotNull(
        "This fee rate is more than twice the next-block estimate."
            .takeIf { nextBlockRate != null && nextBlockRate > 0 && rate > 2 * nextBlockRate },
        "Not all of the fee is taken from the amount. Check the total.".takeIf { subtractFee && !feeFromAmount },
        "The fee is more than 10% of the amount.".takeIf { feeSats * 10 > amountSats },
    ).joinToString("\n").ifEmpty { null }
    return ReviewNumbers(
        feeSats = feeSats,
        feeRate = rate,
        totalSats = totalSats,
        feeFromAmount = feeFromAmount,
        warning = warning,
    )
}

/**
 * What [tx] pays the destination, in sat, or null when it is not the payment
 * the sender approves.
 *
 * The request has one destination, so BTCPay builds one output that pays it
 * and at most one change output; more outputs are refused. The payment must
 * be [amountSats] exactly, or with [subtractFee] that amount less at most
 * [feeSats]. A range, not one value, because how much of the fee comes out of
 * the amount is the server's choice; this looks for a wrong payment, not for
 * how the fee was split.
 *
 * [script] is the output script of the typed address ([scriptPubKeyOf]).
 * Exactly one output must pay it, so a changed destination, or a second output
 * to the same address, fails here, and the address on the review is the one
 * the signed bytes pay. The other output is taken to be change. The app cannot
 * tell the wallet's own script from another one, so a server that pays that
 * output to someone else is not caught. [script] is null only for a coin other
 * than BTC, whose addresses this app cannot read; then only the values are
 * checked, and the larger match counts, so that the total is not too low.
 */
internal fun paidToDestination(
    tx: SignedTransaction,
    script: String?,
    amountSats: Long,
    feeSats: Long,
    subtractFee: Boolean,
): Long? {
    val toDestination = tx.outputSats.indices.filter { script == null || tx.outputScripts[it] == script }
    if (tx.outputSats.size > 2 || (script != null && toDestination.size != 1)) return null
    val approved = if (subtractFee) (amountSats - feeSats)..amountSats else amountSats..amountSats
    return toDestination.map { tx.outputSats[it] }.filter { it in approved }.maxOrNull()
}

/** What the sender approves, and what the result screen repeats. */
data class ReviewData(
    val amountBtc: BigDecimal,
    val destination: String,
    /** The store and payment method whose wallet pays: "Shop · BTC-CHAIN". */
    val from: String,
    val numbers: ReviewNumbers,
)

data class WalletSendState(
    val destination: String = "",
    val amountInput: String = "",
    /**
     * True while [amountInput] came from a scanned code rather than the
     * keyboard. A scanned amount belongs to its code: when another destination
     * replaces that code, the amount goes too, instead of being paid to the
     * new recipient. Typing an amount makes it the sender's own.
     */
    val amountFromScan: Boolean = false,
    val sendEverything: Boolean = false,
    val feeRates: Map<Int, Double> = emptyMap(),
    val selectedTarget: Int? = 6,
    val customFeeRate: String = "",
    val rbf: Boolean = true,
    val excludeUnconfirmed: Boolean = false,
    val phase: OnChainPhase = OnChainPhase.Form,
    /** The signed transaction's review, from [OnChainPhase.Review] to the result. */
    val review: ReviewData? = null,
    /**
     * False when this app knows the server will not sign — a key granted
     * without signing rights, or a wallet the server holds no key for.
     */
    val canSpend: Boolean = true,
    /**
     * True only when that was already known when the screen opened.
     *
     * The distinction matters for what gets drawn. Known in advance, there is
     * no point offering a form at all. Learned from a refused send, replacing
     * the form with an explanation would take the server's own error off the
     * screen at the moment it is most worth reading — so the form stays, with
     * the error on it and the button disabled.
     */
    val blockedUpFront: Boolean = false,
    /** The server predates the broadcast route this flow needs ([ServerVersion.SIGNED_BROADCAST]). */
    val serverTooOld: Boolean = false,
    /** No store was active when the screen opened, so there is no wallet to send from. */
    val noStore: Boolean = false,
    /** Shown above the button: why the last attempt stopped before review, or a refused confirmation. */
    val message: String? = null,
) {
    /** True once the operator has typed something in the custom rate field. */
    val usingCustomFeeRate: Boolean get() = customFeeRate.isNotBlank()

    /**
     * A typed rate always wins over the estimator's chip — and a typed rate
     * that does not parse is an error, not a reason to quietly use the chip.
     *
     * Sent verbatim, `"0"` would go out as `feerate = 0.0`, producing a
     * transaction that never confirms and can only be rescued by RBF. Falling
     * back to the server estimate on `"1.2.3"` while every chip still draws
     * unselected would let the operator believe their rate was in force when
     * it was not.
     */
    val customFeeRateValue: Double?
        get() = Amounts.feeRate(customFeeRate)?.toDouble()

    val feeRateError: String?
        get() = "Enter a fee rate greater than zero, in sat/vB.".takeIf { usingCustomFeeRate && customFeeRateValue == null }

    val effectiveFeeRate: Double?
        get() = if (usingCustomFeeRate) customFeeRateValue else selectedTarget?.let(feeRates::get)

    /** Whether this wallet can be offered for a payment at all. */
    val spendable: Boolean get() = canSpend && !blockedUpFront && !serverTooOld
}

class WalletSendViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
) : ViewModel() {

    // The store the form was opened for (see StoreBinding): every request
    // below goes to it, not to whatever store is active by the time it runs.
    private val store = StoreBinding(graph.session)
    private val storeId: String? get() = store.id
    private val storeName: String? get() = store.store?.name?.takeIf { it.isNotBlank() }
    private val cryptoCode = cryptoCodeOf(paymentMethodId)

    // A store or account switch drops this screen and its view model, and with
    // them the signed bytes that "Send again" needs. So no switch while an
    // outcome waits. Held here, not in the composable: a tab switch or a
    // screen pushed on top hides the outcome but does not end it.
    private val outcomeHold = OutcomeHold(graph.session).also(::addCloseable)

    private val _state = MutableStateFlow(WalletSendState(noStore = storeId == null))
    val state = _state.asStateFlow()

    /**
     * Every state change goes through here, so the hold follows the phase. It
     * starts with the broadcast, so there is no moment between the request's
     * end and the hold.
     */
    private fun updateState(transform: (WalletSendState) -> WalletSendState) {
        outcomeHold.set(_state.updateAndGet(transform).phase.isOutcome)
    }

    private val unit: BitcoinUnit get() = graph.settings.settings.value.bitcoinUnit

    /** The bytes on review or out. Broadcast unchanged; never re-signed once they may be out. */
    private var signed: SignedTransaction? = null

    /**
     * True once an attempt with [signed] ended in a way that might have put it
     * on the network. From then on no path signs a new transaction: the server
     * would build it from other coins and the recipient could be paid twice.
     */
    private var mayHaveReachedNetwork = false

    /** The refusal to show when the lookup confirms [signed] is not out. Only while nothing may be out. */
    private var rejection: OnChainPhase.NotSent? = null

    private var preparing: Job? = null

    init {
        loadFeeRates()
        // After a cold start the store list comes later: bind its first store
        // and load what the missing store stopped.
        store.retryWhenKnown(viewModelScope) {
            updateState { it.copy(noStore = false) }
            loadFeeRates()
        }
        // Set eagerly rather than only from the collector below: the composable
        // reads `state.value` before any coroutine has run, and a watch-only
        // wallet flashing a live send form is exactly the wrong first frame.
        val allowed = graph.session.canSpendOnChain(paymentMethodId)
        updateState {
            it.copy(
                canSpend = allowed,
                blockedUpFront = !allowed,
                serverTooOld = !graph.session.serverAtLeast(ServerVersion.SIGNED_BROADCAST),
            )
        }
        viewModelScope.launch {
            graph.session.unspendableMethods.collect {
                updateState { it.copy(canSpend = graph.session.canSpendOnChain(paymentMethodId)) }
            }
        }
    }

    /**
     * Applies a change to the form. A change after signing drops the signed
     * transaction and goes back to the form: the review must show exactly what
     * will be sent. Ignored once the bytes may be out, when the form is closed.
     */
    private fun edit(transform: (WalletSendState) -> WalletSendState) {
        val current = _state.value
        if (!current.phase.editable) return
        val next = transform(current)
        if (next == current) return
        dropSigned()
        updateState { next.copy(phase = OnChainPhase.Form, review = null, message = null) }
    }

    private fun dropSigned() {
        preparing?.cancel()
        preparing = null
        signed = null
        mayHaveReachedNetwork = false
        rejection = null
    }

    fun setDestination(value: String) = edit { current ->
        if (value == current.destination) {
            current
        } else {
            current.copy(
                destination = value,
                amountInput = if (current.amountFromScan) "" else current.amountInput,
                amountFromScan = false,
            )
        }
    }

    fun setAmount(value: String) = edit { it.copy(amountInput = value, amountFromScan = false) }

    fun setSendEverything(value: Boolean) = edit { it.copy(sendEverything = value) }

    fun setTarget(target: Int) = edit { it.copy(selectedTarget = target, customFeeRate = "") }

    fun setCustomFeeRate(value: String) = edit { it.copy(customFeeRate = value) }

    fun setRbf(value: Boolean) = edit { it.copy(rbf = value) }

    fun setExcludeUnconfirmed(value: Boolean) = edit { it.copy(excludeUnconfirmed = value) }

    /** Empties the destination and amount, after the other half of a unified code was paid. */
    fun clearForm() = edit { it.copy(destination = "", amountInput = "", amountFromScan = false) }

    /** Back to the form without sending; [message] says why, when there is a reason to say. */
    fun cancelReview(message: String? = null) {
        if (!_state.value.phase.editable) return
        dropSigned()
        updateState { it.copy(phase = OnChainPhase.Form, review = null, message = message) }
    }

    /**
     * A BIP21 URI carries an amount as well as an address; a bare address must
     * not disturb an amount the user has already typed — but it does replace
     * one that came with the previous code.
     */
    fun applyScanned(raw: String) {
        if (!cryptoCode.equals("BTC", true)) {
            updateState {
                it.copy(message = "Enter a destination for $cryptoCode manually. This scanner recognizes Bitcoin payment requests.")
            }
            return
        }
        when (val payload = ScanParser.parse(raw)) {
            is ScannedPayload.Bip21 -> edit { current ->
                val scanned = payload.amountBtc?.takeIf { it.signum() > 0 }
                current.copy(
                    destination = payload.address,
                    amountInput = when {
                        scanned != null -> formatAmountInput(scanned, unit)
                        current.amountFromScan -> ""
                        else -> current.amountInput
                    },
                    amountFromScan = scanned != null,
                )
            }

            is ScannedPayload.BitcoinAddress -> setDestination(payload.address)

            // Anything else (a BOLT11 invoice, a server URL) cannot be paid from
            // an on-chain wallet, so the field is left alone rather than filled
            // with something that will only fail at the server.
            else -> Unit
        }
    }

    private fun loadFeeRates() {
        val storeId = storeId ?: return
        viewModelScope.launch {
            val api = runCatching { graph.session.requireApi() }.getOrNull() ?: return@launch
            // A missing estimate is not an error worth showing: the custom
            // sat/vB field is always there as a fallback.
            val rates = FEE_TARGETS.map { target ->
                async { attempt { target to api.walletFeeRate(storeId, paymentMethodId, target).feeRate }.getOrNull() }
            }.awaitAll().filterNotNull().toMap()
            updateState { it.copy(feeRates = rates) }
        }
    }

    /**
     * Has the server sign the payment, without sending it, and opens the
     * review with the exact fee.
     *
     * Signing moves nothing (`signTransaction` turns broadcast and payjoin
     * off), so any failure here, even one with no answer, is a plain
     * "try again". A refusal to sign is remembered, so the Wallet tab and this
     * screen both stop offering a send that cannot work; Greenfield reports a
     * watch-only store and an under-scoped key identically, so both land here.
     */
    fun review() {
        val snapshot = _state.value
        if (snapshot.phase != OnChainPhase.Form || !snapshot.spendable || snapshot.feeRateError != null) return
        val storeId = storeId ?: return
        val destination = snapshot.destination.trim()
        val amountBtc = parseAmountToBtc(snapshot.amountInput, unit, cryptoCode)?.takeIf { it.signum() > 0 }
        if (destination.isEmpty() || amountBtc == null) return
        // For BTC the signed bytes must pay this script (see paidToDestination).
        // An address it cannot read is refused here, before anything is signed.
        val script = if (cryptoCode.equals("BTC", ignoreCase = true)) {
            scriptPubKeyOf(destination) ?: run {
                cancelReview("This app cannot read that address, so it cannot check the payment. Enter only the address.")
                return
            }
        } else {
            null
        }
        val request = CreateTransactionRequest(
            destinations = listOf(
                TransactionDestination(
                    destination = destination,
                    amount = amountBtc,
                    subtractFromAmount = snapshot.sendEverything,
                ),
            ),
            feerate = snapshot.effectiveFeeRate,
            rbf = snapshot.rbf,
            excludeUnconfirmed = snapshot.excludeUnconfirmed,
        )

        dropSigned()
        updateState { it.copy(phase = OnChainPhase.Preparing, review = null, message = null) }
        preparing = viewModelScope.launch {
            val outcome = runCatching { signAndReadCoins(storeId, request) }
            if (_state.value.phase != OnChainPhase.Preparing) return@launch
            outcome.onSuccess { (hex, utxos) ->
                val tx = SignedTransaction.parse(hex) ?: run {
                    // Only Bitcoin's own format is read. Liquid (LBTC) uses
                    // another one, so there it fails every time.
                    cancelReview(
                        if (cryptoCode.equals("BTC", ignoreCase = true)) {
                            "The server returned a transaction this app could not read. Nothing was sent."
                        } else {
                            "This app cannot read $cryptoCode transactions, so it cannot send $cryptoCode. Nothing was sent."
                        },
                    )
                    return@launch
                }
                val amountSats = Amounts.btcToSats(amountBtc).toLong()
                // The fee is computed, not asked for: the coins tx spends,
                // valued from the wallet's own UTXO list, minus what it pays
                // out. A fee the server only reported would be the one number
                // on the review that nobody checked, so an unknown fee stops here.
                val fee = tx.feeSats(coinValues(utxos.orEmpty())) ?: run {
                    cancelReview("The fee could not be checked, so nothing was sent. Try again.")
                    return@launch
                }
                val paid = paidToDestination(tx, script, amountSats, fee, snapshot.sendEverything) ?: run {
                    cancelReview("The server built a different payment. Nothing was sent.")
                    return@launch
                }
                signed = tx
                val review = ReviewData(
                    amountBtc = amountBtc,
                    destination = destination,
                    from = listOfNotNull(storeName, paymentMethodId).joinToString(" · "),
                    numbers = reviewOf(
                        tx, fee, paid, amountSats,
                        subtractFee = snapshot.sendEverything,
                        nextBlockRate = snapshot.feeRates[FEE_TARGETS.first()],
                    ),
                )
                updateState { it.copy(phase = OnChainPhase.Review, review = review) }
            }.onFailure { failure ->
                val error = failure.asApiException()
                if (error.meansCannotSign()) graph.session.markUnspendable(paymentMethodId)
                cancelReview(
                    if (error is ApiException.OutcomeUnknown) {
                        "No clear answer came from the server. Nothing was sent; try again."
                    } else {
                        error.userMessage
                    },
                )
            }
        }
    }

    /**
     * The signed hex, and the wallet's coins to value its inputs with. The two
     * are read together: signing does not spend anything, so the coins it
     * picked are still in the list. A failed coin list leaves the fee unknown,
     * and [review] then stops before the review.
     */
    private suspend fun signAndReadCoins(
        storeId: String,
        request: CreateTransactionRequest,
    ): Pair<String, List<WalletUtxoData>?> = coroutineScope {
        val api = graph.session.requireApi()
        val utxos = async { attempt { api.walletUtxos(storeId, paymentMethodId) }.getOrNull() }
        api.signTransaction(storeId, paymentMethodId, request) to utxos.await()
    }

    /**
     * Sends the reviewed bytes, or sends them again.
     *
     * Also what "Send again" calls after [OnChainPhase.NotSent] or
     * [OnChainPhase.Unknown], without a new confirmation: these are the bytes
     * the sender already approved, and the same transaction cannot be mined
     * twice. One `spending` covers the broadcast *and* the check after it, so a
     * store switch cannot close the screen between the two.
     */
    fun broadcast() {
        val tx = signed ?: return
        val phase = _state.value.phase
        if (phase != OnChainPhase.Review && phase !is OnChainPhase.NotSent && phase !is OnChainPhase.Unknown) return
        val storeId = storeId ?: return
        updateState { it.copy(phase = OnChainPhase.Broadcasting, message = null) }
        viewModelScope.launch {
            val next = graph.session.spending { sendAndSettle(storeId, tx) }
            updateState { it.copy(phase = next) }
        }
    }

    /** Looks [signed] up again, after [OnChainPhase.Unknown]. Sends nothing. */
    fun checkAgain() {
        val tx = signed ?: return
        if (_state.value.phase !is OnChainPhase.Unknown) return
        val storeId = storeId ?: return
        updateState { it.copy(phase = OnChainPhase.Checking) }
        viewModelScope.launch {
            val next = graph.session.spending { lookUp(storeId, tx) }
            updateState { it.copy(phase = next) }
        }
    }

    /** Back to the form after a refusal. Only offered while nothing can have reached the network. */
    fun changePayment() {
        val phase = _state.value.phase
        if (phase !is OnChainPhase.NotSent || !phase.canChange || mayHaveReachedNetwork) return
        dropSigned()
        updateState { it.copy(phase = OnChainPhase.Form, review = null, message = null) }
    }

    /**
     * Broadcasts [tx] and settles what happened: see [refusalOf] for when a
     * failure counts as "not sent". Either way the txid is looked up before
     * anything is said, because only the wallet knows.
     */
    private suspend fun sendAndSettle(storeId: String, tx: SignedTransaction): OnChainPhase {
        val failure = try {
            val sent = graph.session.requireApi().broadcastTransaction(storeId, paymentMethodId, tx.hex)
            return OnChainPhase.Sent(sent.transactionHash?.takeIf { it.isNotBlank() } ?: tx.txid)
        } catch (e: Throwable) {
            e.asApiException()
        }
        // The server broadcast it, then asked its own wallet, which has not
        // indexed it yet.
        if (failure is ApiException.NotFound && failure.code == TRANSACTION_NOT_FOUND) return OnChainPhase.Sent(tx.txid)

        rejection = refusalOf(failure, mayHaveReachedNetwork, versionUnknown = graph.session.serverVersion.value == null)
        if (rejection == null) mayHaveReachedNetwork = true
        updateState { it.copy(phase = OnChainPhase.Checking) }
        return lookUp(storeId, tx)
    }

    private suspend fun lookUp(storeId: String, tx: SignedTransaction): OnChainPhase {
        val failure = try {
            graph.session.requireApi().walletTransaction(storeId, paymentMethodId, tx.txid)
            null
        } catch (e: Throwable) {
            e.asApiException()
        }
        return settled(tx.txid, failure, rejection?.takeUnless { mayHaveReachedNetwork })
    }
}

/**
 * What a failed broadcast shows if the lookup then finds nothing: a refusal,
 * or null when the bytes may have reached the network.
 *
 * A failure only counts as "not sent" when the server refused the bytes
 * before handing them to the network: a node rejection (`broadcast-error`), a
 * validation or permission refusal, or an empty 404 from a server of unknown
 * version, which is a missing route (older than 2.3.3). Anything else, a lost
 * answer above all, may have gone out. So may everything after an attempt
 * that may have gone out ([mayHaveReachedNetwork]): a node that already has
 * the transaction can answer the next attempt with a rejection.
 */
internal fun refusalOf(
    failure: ApiException,
    mayHaveReachedNetwork: Boolean,
    versionUnknown: Boolean,
): OnChainPhase.NotSent? = when {
    mayHaveReachedNetwork -> null

    failure is ApiException.NotFound -> OnChainPhase.NotSent(
        "This server cannot broadcast from the app; it needs BTCPay Server 2.3.3 or later. Nothing was sent.",
        canChange = false,
    ).takeIf { failure.code == null && versionUnknown }

    (failure is ApiException.Server && failure.code == "broadcast-error") ||
        failure is ApiException.Validation || failure is ApiException.Forbidden -> OnChainPhase.NotSent(
        "The payment was not sent: ${failure.userMessage.trim().trimEnd('.')}. Nothing left the wallet.",
        canChange = true,
    )

    else -> null
}

/**
 * The outcome once [txid] was looked up. [lookupFailure] is null when the
 * wallet has it. Not found means "not sent" only with a [refusal] from
 * [refusalOf]; anything else is unknown.
 */
internal fun settled(txid: String, lookupFailure: ApiException?, refusal: OnChainPhase.NotSent?): OnChainPhase =
    when {
        lookupFailure == null -> OnChainPhase.Sent(txid)
        lookupFailure is ApiException.NotFound && refusal != null -> refusal
        else -> OnChainPhase.Unknown(UNCONFIRMED)
    }


/**
 * Whether a failed send means "this wallet can never be spent from here"
 * rather than "try again".
 *
 * Deliberately narrow. A false positive disables the Send button until the app
 * is restarted, so a timeout, a routing problem or an insufficient-funds answer
 * must not be read as a permanent refusal.
 */
private fun ApiException.meansCannotSign(): Boolean = when (this) {
    is ApiException.Forbidden -> true
    is ApiException.Server -> code in CANNOT_SIGN_CODES
    else -> false
}

private val CANNOT_SIGN_CODES = setOf(
    "no-private-keys",
    "not-available",
    "wallet-not-found",
    "unsupported-operation",
)

/** [btc] in the sender's unit; for Bitcoin also the other unit, so a slip of three zeros shows. */
private fun amountLabel(btc: BigDecimal, unit: BitcoinUnit, cryptoCode: String, echo: Boolean = false): String =
    when {
        !cryptoCode.equals("BTC", ignoreCase = true) -> Amounts.format(btc, cryptoCode)
        echo -> "${Amounts.formatBitcoin(btc, unit)} (${Amounts.inOtherUnit(btc, unit)})"
        else -> Amounts.formatBitcoin(btc, unit)
    }

private fun satsLabel(sats: Long, unit: BitcoinUnit, cryptoCode: String): String =
    amountLabel(Amounts.satsToBtc(BigDecimal(sats)), unit, cryptoCode)

/**
 * The on-chain half of the send form: everything the sender fills in below the
 * destination, and the review that stands in front of a broadcast.
 *
 * The destination field itself is not here. It is the one thing both rails have
 * in common — and the thing that decides which of them is on screen — so it
 * belongs to [SendScreen], which owns the switch between the two.
 */
@Composable
internal fun ColumnScope.OnChainSendForm(
    viewModel: WalletSendViewModel,
    state: WalletSendState,
    cryptoCode: String,
) {
    val settings = LocalSettings.current
    val unit = settings.bitcoinUnit
    val scope = rememberCoroutineScope()
    val gate = rememberSpendGate()
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }

    val editable = state.phase == OnChainPhase.Form
    val amountBtc = remember(state.amountInput, unit, cryptoCode) {
        parseAmountToBtc(state.amountInput, unit, cryptoCode)?.takeIf { it.signum() > 0 }
    }
    val amountError = remember(state.amountInput, unit, cryptoCode) {
        amountProblem(state.amountInput, unit, cryptoCode)
    }
    val canReview = editable &&
        state.destination.isNotBlank() &&
        amountBtc != null &&
        // A bad custom rate blocks the send instead of silently reverting
        // to the estimate behind the operator's back.
        state.feeRateError == null &&
        // A server that has already refused to sign will refuse again; the
        // message above the button says so rather than letting it retry for ever.
        state.spendable

    FormField(
        label = "Amount (${unitLabel(unit, cryptoCode)})",
        value = state.amountInput,
        onValueChange = viewModel::setAmount,
        // The value as it will be read, in the other unit: "0.0021" typed as
        // BTC shows "210,000 sat" before anything is signed.
        supportingText = amountBtc
            ?.takeIf { cryptoCode.equals("BTC", ignoreCase = true) }
            ?.let { "= ${Amounts.inOtherUnit(it, unit)}" },
        error = amountError,
        enabled = editable,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )

    FormSection(title = "Network fee") {
        FeeTargetRow(
            rates = state.feeRates,
            selected = state.selectedTarget,
            custom = state.customFeeRate.isNotBlank(),
            enabled = editable,
            onSelect = viewModel::setTarget,
        )
    }

    // The four controls below are right nearly always, and a merchant
    // sending from the counter should not have to read past them. They
    // stay one tap away, with the collapsed row stating what is in force.
    AdvancedOptions(
        state = state,
        enabled = editable,
        expanded = advancedOpen,
        onToggle = { advancedOpen = !advancedOpen },
        onCustomFeeRate = viewModel::setCustomFeeRate,
        onSendEverything = viewModel::setSendEverything,
        onRbf = viewModel::setRbf,
        onExcludeUnconfirmed = viewModel::setExcludeUnconfirmed,
    )

    Spacer(Modifier.height(16.dp))

    FormProblem(state.message)

    Button(
        onClick = viewModel::review,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        enabled = canReview,
    ) {
        // The label and the spinner cross-fade rather than swap on the
        // frame. This button is pressed once and then watched, so the
        // moment it changes is the only confirmation the sender gets
        // that the tap was taken.
        AnimatedSwap(state.phase == OnChainPhase.Preparing, label = "send") { preparing ->
            if (preparing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing…")
                }
            } else {
                Text("Review and send")
            }
        }
    }

    Spacer(Modifier.height(32.dp))

    val review = state.review
    if (state.phase == OnChainPhase.Review && review != null) {
        ReviewDialog(
            review = review,
            unit = unit,
            cryptoCode = cryptoCode,
            onCancel = { viewModel.cancelReview() },
            onSend = {
                if (!authorizing) {
                    authorizing = true
                    scope.launch {
                        try {
                            val amount = amountLabel(review.amountBtc, unit, cryptoCode)
                            when (val auth = gate.authorize("Confirm payment", "Send $amount from ${review.from}")) {
                                SpendAuth.Granted -> viewModel.broadcast()
                                is SpendAuth.Refused -> viewModel.cancelReview(auth.message)
                                // Dismissing is a deliberate "no": the review stays.
                                SpendAuth.Cancelled -> Unit
                            }
                        } finally {
                            authorizing = false
                        }
                    }
                }
            },
        )
    }
}

/**
 * The last look before a broadcast. The fee, rate and total are computed from
 * the signed transaction. For BTC its one payment output was checked to pay
 * the amount to the typed address; the other output, if any, is taken to be
 * change and is not checked ([paidToDestination]). For another coin only the
 * values were checked, and the address is the one the sender typed.
 *
 * The address is shown whole, in groups of four. Twelve characters at each end
 * is what address-poisoning attacks copy, and the middle is what they cannot.
 * The store and payment method are named because a multi-store operator must
 * see which hot wallet is about to pay.
 */
@Composable
private fun ReviewDialog(
    review: ReviewData,
    unit: BitcoinUnit,
    cryptoCode: String,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val numbers = review.numbers
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Review payment") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReviewText(
                    label = "Send",
                    value = amountLabel(review.amountBtc, unit, cryptoCode, echo = true) +
                        if (numbers.feeFromAmount) "\nThe fee is taken from this amount." else "",
                )
                ReviewText(label = "To", value = groupedAddress(review.destination), style = MonospaceStyle)
                ReviewText(label = "From", value = review.from)
                ReviewText(
                    label = "Network fee",
                    value = "${satsLabel(numbers.feeSats, unit, cryptoCode)} (${"%.1f".format(numbers.feeRate)} sat/vB)",
                )
                ReviewText(label = "Total", value = satsLabel(numbers.totalSats, unit, cryptoCode))
                numbers.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = "A sent transaction cannot be reversed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSend) { Text("Send", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ReviewText(label: String, value: String, style: TextStyle = MaterialTheme.typography.bodyLarge) =
    ReviewLine(label) { Text(text = value, style = style) }

/**
 * Everything after the broadcast: sending, checking, and the answer.
 *
 * It replaces the form, because the form is closed from here on: once the
 * bytes may be out, a changed payment would be a second payment. What is
 * offered follows from what is known — "Change the payment" only after a
 * definite refusal, only "Close" when the server has no broadcast route, and
 * otherwise only the same bytes again or a lookup.
 */
@Composable
internal fun OnChainOutcome(
    state: WalletSendState,
    cryptoCode: String,
    onSendAgain: () -> Unit,
    onCheckAgain: () -> Unit,
    onChange: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = LocalSettings.current.bitcoinUnit
    // Held, because this branch stays composed while it fades out, after
    // "Change the payment" has already put the phase back to the form.
    val phase = rememberLast(state.phase.takeIf { it.isOutcome }) ?: return
    val review = rememberLast(state.review)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        AnimatedSwap(phase, label = "outcome") { shown ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (shown) {
                    OnChainPhase.Broadcasting, OnChainPhase.Checking -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(24.dp))
                        OutcomeText(
                            if (shown == OnChainPhase.Broadcasting) {
                                "Sending the payment…"
                            } else {
                                "Checking whether the payment went out…"
                            },
                        )
                    }

                    is OnChainPhase.Sent -> {
                        SuccessCheck(size = 64.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Sent", style = MaterialTheme.typography.titleLarge)
                        review?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = amountLabel(it.amountBtc, unit, cryptoCode),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = groupedAddress(it.destination),
                                style = MonospaceStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        CopyableField(label = "Transaction ID", value = shown.txid, truncate = true)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }

                    is OnChainPhase.NotSent -> {
                        OutcomeIcon(Icons.Rounded.ErrorOutline)
                        Text("Not sent", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        OutcomeText(shown.message)
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (shown.canChange) {
                                // The node refused these bytes, so the same
                                // bytes again rarely help; a new fee usually does.
                                OutlinedButton(onClick = onSendAgain) { Text("Send again") }
                                Button(onClick = onChange) { Text("Change the payment") }
                            } else {
                                // No broadcast route: nothing sent from here can succeed.
                                Button(onClick = onDone) { Text("Close") }
                            }
                        }
                    }

                    is OnChainPhase.Unknown -> {
                        OutcomeIcon(Icons.AutoMirrored.Rounded.HelpOutline)
                        Text("Payment status unknown", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        OutcomeText(shown.message)
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onSendAgain) { Text("Send again") }
                            Button(onClick = onCheckAgain) { Text("Check again") }
                        }
                    }

                    OnChainPhase.Form, OnChainPhase.Preparing, OnChainPhase.Review -> Unit
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun OutcomeIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun OutcomeText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Shown in place of the on-chain form when the server will not sign for this
 * wallet — known before anything is typed, rather than discovered by a refused
 * broadcast.
 *
 * Not a disabled form: there is nothing useful to type into one, and a screen
 * full of live fields behind a dead button reads as a bug rather than as a
 * deliberate limit. When the store also has a Lightning node the rail switch
 * stays above this, so the sender's next move is one tap away.
 */
@Composable
internal fun OnChainWatchOnlyState(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "This wallet is watch only",
        description = "The server will not sign for it. Either the private key is not held " +
            "there — it lives on a hardware wallet or another signer — or this app's API " +
            "key was granted without signing rights.\n\nReceiving still works, and so does " +
            "the history. To spend, sign with the wallet that holds the key.",
        icon = Icons.Rounded.Visibility,
    )
}

/**
 * Shown in place of the on-chain form on a server older than 2.3.3, which has
 * no route to broadcast a transaction the app has reviewed. The only other way
 * would sign and send in one call, with no review and no way to tell a lost
 * answer from a failure, so this app does not offer it.
 */
@Composable
internal fun OnChainNeedsUpdateState(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "Server update needed",
        description = "Sending from the app needs BTCPay Server 2.3.3 or later.",
        icon = Icons.Rounded.Update,
    )
}

@Composable
private fun AdvancedOptions(
    state: WalletSendState,
    enabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCustomFeeRate: (String) -> Unit,
    onSendEverything: (Boolean) -> Unit,
    onRbf: (Boolean) -> Unit,
    onExcludeUnconfirmed: (Boolean) -> Unit,
) {
    ExpandableSection(
        title = "Fee options",
        summary = advancedSummary(state),
        // A bad custom rate is the one thing in here that blocks the send, so
        // it has to be legible while the section is shut.
        summaryIsError = state.feeRateError != null,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        FormField(
            label = "Custom fee rate (sat/vB)",
            value = state.customFeeRate,
            onValueChange = onCustomFeeRate,
            placeholder = "Leave empty to use the estimate above",
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
            error = state.feeRateError,
        )
        FormSwitch(
            title = "Subtract fee from amount",
            checked = state.sendEverything,
            onCheckedChange = onSendEverything,
            description = "Takes the network fee out of the amount above instead of adding it on top.",
            enabled = enabled,
        )
        FormSwitch(
            title = "Replace by fee",
            checked = state.rbf,
            onCheckedChange = onRbf,
            description = "Lets you bump the fee later if the transaction gets stuck.",
            enabled = enabled,
        )
        FormSwitch(
            title = "Exclude unconfirmed inputs",
            checked = state.excludeUnconfirmed,
            onCheckedChange = onExcludeUnconfirmed,
            description = "Spends only coins that already have a confirmation.",
            enabled = enabled,
        )
    }
}

/** What the collapsed row has to admit to, in one line. */
private fun advancedSummary(state: WalletSendState): String {
    state.feeRateError?.let { return it }
    return listOfNotNull(
        state.customFeeRateValue?.let { "%.2f sat/vB".format(it) },
        "fee ${if (state.sendEverything) "taken from the amount" else "added on top"}",
        if (state.rbf) "replaceable" else "not replaceable",
        "confirmed coins only".takeIf { state.excludeUnconfirmed },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun FeeTargetRow(
    rates: Map<Int, Double>,
    selected: Int?,
    custom: Boolean,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(FEE_TARGETS) { target ->
            val rate = rates[target]
            FilterChip(
                selected = !custom && selected == target,
                onClick = { onSelect(target) },
                enabled = enabled && rate != null,
                label = {
                    Text(
                        text = buildString {
                            append(targetLabel(target))
                            if (rate != null) append(" · %.1f".format(rate))
                        },
                    )
                },
            )
        }
    }
}

private fun targetLabel(blockTarget: Int): String = when (blockTarget) {
    1 -> "Next block"
    6 -> "~1 hour"
    24 -> "~4 hours"
    else -> "~$blockTarget blocks"
}
