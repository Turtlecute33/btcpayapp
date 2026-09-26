package com.btcpayapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.ui.theme.Motion

/**
 * A spinner, for waits with no predictable shape.
 *
 * Prefer [SkeletonList] wherever what is coming is a list. A spinner is the
 * right answer only when the thing being waited for could be anything — a form
 * submitting, a node connecting — because a placeholder that guesses the wrong
 * layout is worse than no placeholder.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp).arrive(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The wait state for a list: rows in outline, where the rows will be.
 *
 * This is the single biggest difference between an app that feels quick and
 * one that does not, and it is not a trick. A spinner in the middle of an
 * empty screen throws the layout away and rebuilds it when the data lands, so
 * every load ends with the page jumping. A skeleton occupies the same space
 * the content will, so the data arrives into a page that is already the right
 * shape and nothing moves except the text appearing.
 *
 * [rows] should be roughly what fits on screen. Fewer looks like a short list
 * that then grows; many more is wasted composition below the fold.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 6,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(rows) { index ->
            SkeletonRow(Modifier.arrive(index))
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Staggered against each other rather than arriving as a block. An
        // empty state is the one screen with nothing to read, so it is the one
        // place where a beat between the icon and the words costs the reader
        // nothing and stops the screen looking like a failure to load.
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp).arrive(0),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            modifier = Modifier.arrive(1),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                modifier = Modifier.arrive(2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.arrive(3)) { Text(actionLabel) }
        }
    }
}

/**
 * Full-screen failure, used when there is nothing cached to show.
 *
 * The icon and wording vary by failure class, because "check your connection"
 * is unhelpful when the real problem is a revoked API key, and a certificate
 * error deserves to look different from a timeout.
 *
 * Takes a nullable error and remembers the last real one, for the same reason
 * [ErrorBanner] does. This is shown inside a swap, and a swap keeps its
 * outgoing half composed while it fades — so on the frame the user taps Retry,
 * `error` is already null and this recomposes once more on its way out.
 * Holding the value here means no caller needs a stand-in error such as
 * `?: ApiException.NotFound()` to survive that, and the message cannot change
 * to the wrong wording as it leaves.
 */
@Composable
fun ErrorState(
    error: ApiException?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onFix: (() -> Unit)? = null,
    fixLabel: String? = null,
) {
    val shown = rememberLast(error) ?: return

    val icon = when (shown) {
        is ApiException.Transport, is ApiException.Timeout -> Icons.Rounded.CloudOff
        is ApiException.Tls -> Icons.Rounded.Shield
        is ApiException.Unauthorized, is ApiException.Forbidden -> Icons.Rounded.Lock
        else -> Icons.Rounded.ErrorOutline
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp).arrive(0),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = shown.headline(),
            modifier = Modifier.arrive(1),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = shown.userMessage,
            modifier = Modifier.arrive(2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.arrive(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
            if (onFix != null && fixLabel != null) {
                TextButton(onClick = onFix) { Text(fixLabel) }
            }
        }
    }
}

/**
 * Inline failure, shown above content that is still usable.
 *
 * Takes a nullable error and owns its own appearance, rather than being
 * wrapped in `error?.let { }` by every caller. That is not a convenience: a
 * banner conjured into and out of existence by an `if` cannot animate out,
 * because by the time it should be leaving it has already been removed from
 * the composition. Holding the null here means the card can push the content
 * below it down as it arrives and let it back up as it goes — which is also
 * what stops a failed background refresh from making the whole list jump.
 */
@Composable
fun ErrorBanner(
    error: ApiException?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    // Held, so the card still has something to say while it collapses.
    val shown = rememberLast(error)

    AnimatedVisibility(
        visible = error != null,
        modifier = modifier,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = shown?.userMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (onRetry != null || onDismiss != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
                        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }
    }
}

private fun ApiException.headline(): String = when (this) {
    is ApiException.Transport -> "Cannot reach the server"
    is ApiException.Timeout -> "The server is slow to answer"
    is ApiException.Tls -> "Certificate not trusted"
    is ApiException.Unauthorized -> "Authorisation expired"
    is ApiException.Forbidden -> "Not permitted"
    is ApiException.NotFound -> "Not found"
    is ApiException.Unsupported -> "Not supported by this server"
    is ApiException.Validation -> "Check those values"
    is ApiException.Decoding -> "Unexpected response"
    is ApiException.NoAccount -> "No server connected"
    is ApiException.Server -> "Server error"
}

/**
 * The inline line a form shows when it will not submit, or when the server
 * refused what it was given.
 *
 * It pushes what is beneath it down as it arrives and lets it back up as it
 * goes, rather than being conjured in and out between two frames. On a send
 * form that is the difference between a reader noticing why the button did
 * nothing and a reader not.
 */
@Composable
fun FormProblem(
    message: String?,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 8.dp,
) {
    val shown = rememberLast(message)

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Text(
            text = shown.orEmpty(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = verticalPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
