package com.btcpayapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.btcpayapp.ui.theme.Motion
import androidx.compose.ui.text.style.TextOverflow

/**
 * The frame every screen sits in.
 *
 * Centralising it means back behaviour, insets, the collapsing app bar, the
 * snackbar host and pull-to-refresh are implemented once and behave identically
 * everywhere — including on a foldable, where getting insets subtly wrong on
 * one screen out of thirty is the usual outcome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    refreshing: Boolean? = null,
    onRefresh: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val fabVisibility = rememberFabVisibility()
    val scrollBehavior: TopAppBarScrollBehavior = if (large) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior()
    }

    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(fabVisibility.connection),
        topBar = {
            val titleContent: @Composable () -> Unit = {
                Column2(title = title, subtitle = subtitle)
            }
            val navigation: @Composable () -> Unit = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            }
            if (large) {
                LargeTopAppBar(
                    title = titleContent,
                    navigationIcon = navigation,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = titleContent,
                    navigationIcon = navigation,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            // The action button gets out of the way while the user is reading
            // and comes back the moment they stop or reverse. An extended FAB
            // covers two list rows, and on the screens that have one those
            // rows are invoices — the thing the user opened the screen to
            // read. It leaves downward rather than fading, so it is obvious it
            // has gone somewhere and will come back, not been disabled.
            AnimatedVisibility(
                visible = fabVisibility.visible,
                enter = slideInVertically(Motion.spatialOffset) { it * 2 } + fadeIn(Motion.effects),
                exit = slideOutVertically(Motion.spatialOffset) { it * 2 } + fadeOut(Motion.effectsFast),
            ) {
                floatingActionButton()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = refreshing == true,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                content(PaddingValues(bottom = padding.calculateBottomPadding()))
            }
        } else {
            Box(Modifier.fillMaxSize()) { content(padding) }
        }
    }
}

/**
 * Tracks whether the floating action button should currently be shown.
 *
 * Driven from the scroll itself rather than from a list's first-visible-index,
 * because [AppScreen] does not know what its content is — a `LazyColumn` on
 * one screen, a plain scrolling `Column` on another. A nested-scroll
 * connection sees both.
 *
 * The threshold exists because a bare sign test makes the button flicker on
 * every jitter of a finger resting on the list. A few pixels of slop means a
 * deliberate scroll hides it and a wobble does not.
 */
@Composable
private fun rememberFabVisibility(): FabVisibility = remember { FabVisibility() }

private const val FAB_SCROLL_SLOP_PX = 6f

@Stable
private class FabVisibility {
    var visible by mutableStateOf(true)
        private set

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -FAB_SCROLL_SLOP_PX) visible = false
            if (available.y > FAB_SCROLL_SLOP_PX) visible = true
            return Offset.Zero
        }
    }
}

@Composable
private fun Column2(title: String, subtitle: String?) {
    androidx.compose.foundation.layout.Column {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}
