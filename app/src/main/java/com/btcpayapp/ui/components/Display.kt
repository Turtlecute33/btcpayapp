package com.btcpayapp.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.btcpayapp.core.qr.QrEncoder
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.theme.AmountStyle
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.MonospaceStyle
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal

// ---------------------------------------------------------------------------
// Amounts
// ---------------------------------------------------------------------------

/**
 * Renders a fiat or crypto amount, honouring privacy mode.
 *
 * Privacy mode masks rather than hides: the row keeps its height and the layout
 * does not jump when it is toggled at a market stall.
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
    maskable: Boolean = true,
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
    val text = if (maskable && settings.privacyMode) Amounts.masked(formatted) else formatted

    if (!animated) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
 * The hero figure on the terminal and checkout screens.
 *
 * [animated] is for a figure that is revised by something other than the
 * person looking at it — an exchange rate moving under a checkout total. Not
 * for the terminal keypad: a digit typed every 150ms against a spring that
 * takes 300ms to settle never resolves, and the number spends the whole entry
 * mid-slide and unreadable.
 */
@Composable
fun BigAmount(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    animated: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AmountText(
            amount = amount,
            currency = currency,
            style = AmountStyle,
            maskable = false,
            animated = animated,
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
        )
    }
}

// ---------------------------------------------------------------------------
// Copyable values
// ---------------------------------------------------------------------------

/**
 * A monospace value with a copy button.
 *
 * Copying marks the clip as sensitive so Android 13+ omits the preview toast
 * and Android 15 keeps it out of clipboard history. Addresses and BOLT11
 * invoices are not secrets, but a clipboard preview showing one over the
 * shoulder of a merchant is still worth avoiding, and for an API key it
 * matters a great deal.
 */
@Composable
fun CopyableField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    onCopied: (String) -> Unit = {},
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
            IconButton(
                onClick = {
                    copyToClipboard(context, label, value, sensitive)
                    onCopied(label)
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy $label",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

fun copyToClipboard(context: Context, label: String, value: String, sensitive: Boolean = false) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
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
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val payload = remember(content) { QrEncoder.optimiseCase(content) }
    val image = remember(payload) { QrEncoder.encode(payload) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color.White,
    ) {
        if (image != null) {
            // No entrance of its own, deliberately.
            //
            // `QrEncoder.encode` runs inside `remember`, during composition, so
            // the bitmap is there on the first frame the card is and there is
            // no gap to cover. An entrance would only replay itself: several of
            // these sit inside lazy lists, and an entrance in a lazy item runs
            // again every time the item scrolls back into view. Whatever
            // reveals the QR — a swap, a section expanding — animates it from
            // the outside.
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

// ---------------------------------------------------------------------------
// Rows and sections
// ---------------------------------------------------------------------------

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
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
