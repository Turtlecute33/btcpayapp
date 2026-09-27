package com.btcpayapp.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.btcpayapp.core.qr.QrEncoder
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.theme.AmountStyle
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.MonospaceStyle
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

// ---------------------------------------------------------------------------
// Amounts
// ---------------------------------------------------------------------------

/**
 * Renders a fiat or crypto amount, honouring privacy mode.
 *
 * Privacy mode masks rather than hides: the row keeps its height and the layout
 * does not jump when it is toggled at a market stall. The mask is
 * [Amounts.MASK], one fixed width, so its length says nothing about the size of
 * the number behind it. Figures that are never masked do not use this; see
 * [maskedIfPrivate].
 *
 * A figure that does not fit shrinks (see [FigureText]).
 *
 * [animated] makes the figure roll when it changes, upward for a rise and
 * downward for a fall. Off by default, and deliberately so. It belongs on the
 * handful of figures that change while being looked at — a wallet balance, a
 * node's channel capacity, a running total on the terminal — and nowhere else.
 * A transaction in a list has an amount that is a fact about the past; making
 * it roll on every refresh animates noise, and thirty rows each carrying an
 * `AnimatedContent` is thirty subcompositions to pay for nothing.
 */
@Composable
fun AmountText(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    animated: Boolean = false,
) {
    val settings = LocalSettings.current
    val formatted = remember(amount, currency, settings.bitcoinUnit) {
        if (currency.equals("BTC", ignoreCase = true)) {
            Amounts.formatBitcoin(amount, settings.bitcoinUnit)
        } else {
            Amounts.format(amount, currency)
        }
    }
    val text = if (settings.privacyMode) Amounts.MASK else formatted

    if (!animated) {
        FigureText(text = text, modifier = modifier, style = style, color = color)
        return
    }

    // Compared as numbers, not as strings. The formatted text is what is
    // shown, but "9.99" to "10.00" is a rise and sorting those two strings
    // says the opposite.
    //
    // Held in a plain object rather than a `MutableState`. The previous amount
    // is read during composition and written just after it, and a snapshot
    // state would make that write invalidate the composition that just read
    // it — so every amount change would recompose twice to reach the same
    // answer. Nothing observes this value; it only has to survive.
    val history = remember { AmountHistory(amount) }
    val rising = amount >= history.previous
    SideEffect { history.previous = amount }

    AnimatedValue(
        value = text,
        upward = rising,
        modifier = modifier,
        style = style,
        color = color,
    )
}

/** The last amount an [AmountText] showed. See its comment on why this is not state. */
private class AmountHistory(var previous: BigDecimal)

/**
 * A figure on one line that shrinks to fit, down to 12sp, before anything is
 * cut: "0.0012345…" is a different amount that looks like the right one.
 *
 * Only a figure still too wide at 12sp is cut, and then with an ellipsis. It
 * never wraps, so the unit cannot fall onto a hidden second line and vanish
 * with no mark.
 */
@Composable
fun FigureText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = shrinkToFit(style),
    )
}

/** The smallest size a figure shrinks to. Below this it is no longer readable at arm's length. */
private val MIN_AMOUNT_SIZE = 12.sp

/**
 * Shrink-to-fit for a one-line figure, from the style's own size down to
 * [MIN_AMOUNT_SIZE], or null when the style states no size in sp.
 *
 * In 1sp steps rather than the default quarter: the search lays the text out
 * once per step it tries, this runs for every amount in a list, and a quarter
 * of a point is a difference nobody can see.
 */
private fun shrinkToFit(style: TextStyle): TextAutoSize? {
    val largest = style.fontSize
    if (!largest.isSp) return null
    val smallest = if (largest.value < MIN_AMOUNT_SIZE.value) largest else MIN_AMOUNT_SIZE
    return TextAutoSize.StepBased(minFontSize = smallest, maxFontSize = largest, stepSize = 1.sp)
}

/**
 * [formatted], or [Amounts.MASK] in privacy mode.
 *
 * For balances, history, lists and details whose text is not drawn by
 * [AmountText]: one rule, so no screen invents its own. Screens a customer or
 * a cashier reads to take a payment — the terminal, checkout, a receive code —
 * are never masked. Nor is a confirmation of money leaving the store (a review
 * dialog and the spend-prompt subtitle): the operator must read the amount
 * there. Those use a plain formatter from `Amounts`, not this or [AmountText].
 */
@Composable
@ReadOnlyComposable
fun maskedIfPrivate(formatted: String): String =
    if (LocalSettings.current.privacyMode) Amounts.MASK else formatted

/**
 * [destination] as a review shows it: a Bitcoin address in groups of four, so
 * it can be read against the payee's copy group by group; anything else (a
 * BOLT11, an LNURL, a Lightning address) as it is, since it has no such
 * structure. Always whole. A shortened "bc1qar0srr…wf5mdq" is enough to find
 * a payment, not to check one: a look-alike address can share its first and
 * last characters.
 */
internal fun reviewDestination(destination: String): String {
    val value = destination.trim()
    return if (ScanParser.parse(value) is ScannedPayload.BitcoinAddress) groupedAddress(value) else value
}

/** An on-chain address in groups of four, for [reviewDestination] and the on-chain send review. */
internal fun groupedAddress(address: String): String = address.chunked(4).joinToString(" ")

/** One line of a review dialog: [label] over [content]. */
@Composable
internal fun ReviewLine(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/**
 * A screen subtitle naming the chain, or null when it would say nothing.
 *
 * Bitcoin is what this app is for, and "BTC" under the word "Send" tells the
 * reader something they already knew. An altcoin code does not — a store with
 * both BTC and LTC wallets needs to know which Send screen it is looking at —
 * so the code survives exactly where it earns its place.
 */
fun chainSubtitle(cryptoCode: String, prefix: String? = null): String? {
    val code = cryptoCode.takeIf { it.isNotBlank() && !it.equals("BTC", ignoreCase = true) }
    return listOfNotNull(prefix, code).joinToString(" · ").ifBlank { null }
}

/**
 * The hero figure at the top of a detail screen: a transaction, a pull payment.
 *
 * Masked in privacy mode, because those are the store's own records and
 * privacy mode promises to hide them.
 */
@Composable
fun BigAmount(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AmountText(
            amount = amount,
            currency = currency,
            style = AmountStyle,
        )
        if (secondary != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = secondary,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

@Composable
fun StatusChip(status: InvoiceStatus, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = AppTheme.statusColors
    val (container, content) = when (status) {
        InvoiceStatus.Settled -> colors.settled to colors.onSettled
        InvoiceStatus.Processing, InvoiceStatus.New -> colors.pending to colors.onPending
        InvoiceStatus.Expired -> colors.expired to colors.onExpired
        InvoiceStatus.Invalid, InvoiceStatus.Unknown -> colors.invalid to colors.onInvalid
    }
    StatusPill(
        label = detail ?: status.name,
        container = container,
        content = content,
        modifier = modifier,
    )
}

@Composable
fun PayoutStatusChip(state: PayoutState, modifier: Modifier = Modifier) {
    val colors = AppTheme.statusColors
    val (container, content) = when (state) {
        PayoutState.Completed -> colors.settled to colors.onSettled
        PayoutState.AwaitingApproval, PayoutState.AwaitingPayment, PayoutState.InProgress ->
            colors.pending to colors.onPending
        PayoutState.Cancelled -> colors.expired to colors.onExpired
        PayoutState.Unknown -> colors.invalid to colors.onInvalid
    }
    StatusPill(
        label = TextUtil.sentenceCase(state.name),
        container = container,
        content = content,
        modifier = modifier,
    )
}

/**
 * A coloured state label.
 *
 * The colours are animated and the text is not. An invoice going from New to
 * Processing to Settled is one thing changing state, so the pill should
 * recolour in place rather than be replaced — but the word inside it is what
 * the user is actually reading, and a word that slides or dissolves while
 * being read is a word that has to be read twice. It changes on the frame.
 */
@Composable
fun StatusPill(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val animatedContainer by animateColorAsState(container, Motion.color, label = "pill")
    val animatedContent by animateColorAsState(content, Motion.color, label = "pillText")
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = animatedContainer,
        contentColor = animatedContent,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Copyable values
// ---------------------------------------------------------------------------

/**
 * A monospace value with a copy button.
 *
 * [sensitive] is for secrets such as an API key: see [copyToClipboard] for
 * what it does. Addresses and BOLT11 invoices are not secrets and are copied
 * plainly, so the system can show what was copied.
 */
@Composable
fun CopyableField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    truncate: Boolean = false,
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (truncate) TextUtil.middleEllipsis(value, 16, 12) else value,
                modifier = Modifier.weight(1f),
                style = MonospaceStyle,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { copyToClipboard(context, label, value, sensitive) }) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy $label",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** How long a sensitive clip may stay on the clipboard. Long enough to paste it once. */
private const val SENSITIVE_CLIP_MS = 60_000L

/**
 * The pending clear of this app's last sensitive clip: its label and the time
 * the system stamped on it, which is how the clipboard describes it. Kept in a
 * file, not in memory, so a clear that is due when the process dies still runs
 * the next time the app is in front. Neither value is secret, and no app data
 * goes into a backup.
 */
private const val CLIP_PREFS = "sensitive_clip"
private const val CLIP_LABEL = "label"
private const val CLIP_STAMP = "stamp"

/** Tags the one scheduled clipboard check, so a new one replaces it. */
private val CLIP_CHECK = Any()

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Copies [value] and says so.
 *
 * Below Android 13 the system shows nothing when an app copies, so a "Copied"
 * toast is shown here; from 13 the system shows its own confirmation.
 *
 * A [sensitive] clip is flagged, so previews and keyboard histories leave it
 * out, and it is removed after [SENSITIVE_CLIP_MS] if it is still on the
 * clipboard. That limits how long a secret waits there for any app that can
 * read the clipboard (every app, below Android 10). From Android 10 the check
 * works only while the app is in front, so a clip whose time is up while the
 * app is in the background or closed goes when the app is next opened. If it
 * is not opened again, the clip stays; Android 13 and later clear old clips by
 * themselves. See [clearExpiredSensitiveClip] for how "still" is told.
 */
@SuppressLint("InlinedApi")
fun copyToClipboard(context: Context, label: String, value: String, sensitive: Boolean = false) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        // EXTRA_IS_SENSITIVE is a compile-time constant, inlined into this app,
        // so it is safe below Android 13, where keyboards that keep a clipboard
        // history read the same key.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    manager.setPrimaryClip(clip)

    if (sensitive) {
        // Read back at once, while the app is in front: the system stamps the
        // clip as it takes it.
        val stamp = runCatching { manager.primaryClipDescription?.timestamp }.getOrNull()
        if (stamp != null) {
            // The application context: the delayed check must not hold an activity.
            val app = context.applicationContext
            clipPrefs(app).edit { putString(CLIP_LABEL, label).putLong(CLIP_STAMP, stamp) }
            checkClipAfter(app, SENSITIVE_CLIP_MS)
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Removes this app's sensitive clip whose time is up, if the clipboard still
 * holds it, and leaves anything copied since alone. A clip whose time is not
 * up yet is checked again when it is.
 *
 * The clip is recognised by its description (label and timestamp), never by
 * its text: reading the text would read whatever another app copied since,
 * and Android 12 and later would tell the user that this app pasted it.
 *
 * From Android 10 an app without focus cannot read even the description, so
 * a check that runs in the background does nothing and stays due. MainActivity
 * calls this again whenever its window gains focus, also after a restart.
 */
fun clearExpiredSensitiveClip(context: Context) {
    val app = context.applicationContext
    val prefs = clipPrefs(app)
    val label = prefs.getString(CLIP_LABEL, null) ?: return
    val stamp = prefs.getLong(CLIP_STAMP, 0L)
    val left = sensitiveClipLeft(stamp, System.currentTimeMillis())
    if (left > 0) {
        checkClipAfter(app, left)
        return
    }
    val manager = app.getSystemService(ClipboardManager::class.java) ?: return
    // Null when the read is refused, or when the clipboard is empty: tried again next time.
    val description = runCatching { manager.primaryClipDescription }.getOrNull() ?: return
    prefs.edit { clear() }
    if (description.timestamp == stamp && description.label?.toString() == label) clearPrimaryClip(manager)
}

/**
 * Milliseconds until a sensitive clip stamped at [stamp] (wall clock) is due
 * to go; zero or less when it is due. A clock before [stamp] was set back
 * after the copy, so the clip is due at once: each check would otherwise wait
 * again, and the clip would stay for as long as the clock went back.
 */
internal fun sensitiveClipLeft(stamp: Long, now: Long): Long =
    if (now < stamp) 0L else stamp + SENSITIVE_CLIP_MS - now

private fun checkClipAfter(app: Context, delayMs: Long) {
    mainHandler.removeCallbacksAndMessages(CLIP_CHECK)
    mainHandler.postAtTime({ clearExpiredSensitiveClip(app) }, CLIP_CHECK, SystemClock.uptimeMillis() + delayMs)
}

private fun clipPrefs(context: Context) = context.getSharedPreferences(CLIP_PREFS, Context.MODE_PRIVATE)

/**
 * Removes the clip if it still holds [value], and leaves anything the user
 * copied since alone. For text the user pasted into this app, such as an API
 * key, once it has been used; call it while the app is in front.
 *
 * From Android 10 an app in the background may not read the clipboard, so the
 * check fails and the clip stays. That is the safe way to fail: the app cannot
 * tell whether the clip is still the same, and Android 13 and later clear old
 * clips by themselves.
 */
fun clearClipboardIfHolds(context: Context, value: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
    if (clip.itemCount == 0 || clip.getItemAt(0).text?.toString() != value) return
    clearPrimaryClip(manager)
}

private fun clearPrimaryClip(manager: ClipboardManager) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

// ---------------------------------------------------------------------------
// QR
// ---------------------------------------------------------------------------

/**
 * Renders [content] as a QR code.
 *
 * The bitmap is one pixel per module and scaled with [FilterQuality.None], so
 * the modules stay square and hard-edged at any size — scanners are noticeably
 * faster on a crisp code than on a smoothed one.
 *
 * The background is forced to white and the foreground to black regardless of
 * theme. A themed QR looks nicer and scans worse.
 *
 * Encoded on [Dispatchers.Default], not in composition: a BIP21 link with an
 * amount and a Lightning invoice is a dense code, and encoding it on the main
 * thread drops frames on exactly the push that opens the checkout. Until it is
 * ready a blank white square of the same size holds the place, so nothing
 * moves when the code lands.
 *
 * The side is capped at 320dp and at 55% of the screen height, so a customer's
 * camera sees the whole code without scrolling in landscape or on a tablet.
 * The cap applies after the caller's [modifier], so a caller's
 * `fillMaxWidth(0.8f)` still sizes it on a phone, and the capped code is
 * centred in the space the caller gave it.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val payload = remember(content) { QrEncoder.optimiseCase(content) }
    val encoded by produceState<EncodedQr?>(null, payload) {
        // QRCodeWriter keeps no state, so encodes on several threads are safe.
        value = EncodedQr(payload, withContext(Dispatchers.Default) { QrEncoder.encode(payload) })
    }
    // Checked against the payload, not only for null. produceState keeps its
    // last value while it works on a new one, and a code for the previous
    // amount must never be on screen, not even for a frame.
    val current = encoded?.takeIf { it.payload == payload }
    val cap = min(QR_MAX_SIDE, (LocalConfiguration.current.screenHeightDp * QR_MAX_SCREEN_FRACTION).dp)

    Surface(
        modifier = modifier.wrapContentSize().sizeIn(maxWidth = cap, maxHeight = cap),
        shape = MaterialTheme.shapes.medium,
        color = Color.White,
    ) {
        val image = current?.image
        if (current == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f))
        } else if (image != null) {
            // No entrance of its own, deliberately.
            //
            // The placeholder above has the same size and colour, so the code
            // lands without anything moving. An entrance would only replay
            // itself: several of these sit inside lazy lists, and an entrance
            // in a lazy item runs again every time the item scrolls back into
            // view. Whatever reveals the QR — a swap, a section expanding —
            // animates it from the outside.
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(8.dp),
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.None,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cannot render this code", color = Color.Black)
            }
        }
    }
}

private val QR_MAX_SIDE = 320.dp
private const val QR_MAX_SCREEN_FRACTION = 0.55f

/** A finished encode and the payload it is for; [image] is null when the payload cannot be a QR code. */
private class EncodedQr(val payload: String, val image: ImageBitmap?)

// ---------------------------------------------------------------------------
// Rows and sections
// ---------------------------------------------------------------------------

/**
 * The title over a group of rows.
 *
 * Marked as a heading, so a screen-reader user can jump from section to
 * section instead of hearing every row on the way. Only the title is the
 * heading; the [action] stays a control of its own.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        action()
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    monospace: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = if (monospace) MonospaceStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * The standard content card.
 *
 * A tappable one shrinks very slightly while held. Material's ripple alone is
 * not enough on a surface this large — at the edge of a full-width card the
 * ripple is so far from the finger that a tap there registers as nothing
 * happening. The scale says the whole card is one button.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    if (onClick == null) {
        Card(
            modifier = base,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
        return
    }

    val interactions = remember { MutableInteractionSource() }
    Card(
        modifier = base
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        content()
    }
}

@Composable
fun DirectionIcon(incoming: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.statusColors
    val icon: ImageVector = if (incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (incoming) colors.settled else colors.pending,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (incoming) "Received" else "Sent",
            tint = if (incoming) colors.incoming else colors.outgoing,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
