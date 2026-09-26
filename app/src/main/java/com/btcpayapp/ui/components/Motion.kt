package com.btcpayapp.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.btcpayapp.ui.theme.LocalReducedMotion
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Swapping one thing for another
// ---------------------------------------------------------------------------

/**
 * How far the incoming and outgoing contents of a swap are separated in depth.
 *
 * Four percent. The job is to keep two layouts from reading as one double
 * exposure, not to zoom.
 */
private const val SWAP_SCALE = 0.96f

/**
 * The transition used whenever a region of the screen replaces its contents
 * with something of a different kind — a spinner becoming a list, a form
 * becoming a receipt, an unpaid invoice becoming a paid one.
 *
 * A plain cross-fade is the reflex here and it is wrong: two unrelated layouts
 * dissolving through each other spend the middle of the transition as an
 * unreadable double exposure. Scaling the incoming content up from slightly
 * under full size, and the outgoing one down, separates them in depth so only
 * one is ever the thing in focus.
 *
 * The fade is stiffer than the scale, so the outgoing content is most of the
 * way gone before the incoming one arrives.
 *
 * The size transform is stated rather than left to default. A spinner becoming
 * a six-field form changes the height of the region as well as its contents,
 * and `AnimatedContent`'s own default resizes it on a Compose spring that is
 * nothing like the rest of this app — so the container would settle on one set
 * of physics while what is inside it settled on another.
 */
fun swapTransform(reducedMotion: Boolean): ContentTransform = ContentTransform(
    targetContentEnter = if (reducedMotion) {
        fadeIn(Motion.effects)
    } else {
        fadeIn(Motion.effects) + scaleIn(Motion.spatial, initialScale = SWAP_SCALE)
    },
    // The outgoing half leaves on the *fade's* timing, not the scale's.
    //
    // `AnimatedContent` keeps the outgoing branch composed, and taking pointer
    // input, until every one of its exit animations has finished — and an
    // alpha of zero does not stop a hit test. Pairing an 80ms fade with a
    // 400ms scale would therefore leave an invisible, fully tappable copy of
    // the old content lying over the new for a third of a second, which is
    // long enough to double-submit a form or open a menu belonging to a
    // control that is no longer there. The incoming half still scales in over
    // the full spring; that is the half anyone can see.
    initialContentExit = if (reducedMotion) {
        fadeOut(Motion.effectsFast)
    } else {
        fadeOut(Motion.effectsFast) + scaleOut(Motion.effectsFast, targetScale = SWAP_SCALE)
    },
    sizeTransform = resize(),
)

/** The container of a swap or a page change, resizing on the app's own spring. */
private fun resize(): SizeTransform = SizeTransform(clip = true) { _, _ -> Motion.spatialSize }

/**
 * A region whose whole contents change together.
 *
 * Thin by design: it exists so that every such swap in the app is the same
 * swap, and so the reduced-motion decision is made in one place rather than in
 * thirty. Pass the discriminator the content actually branches on — not the
 * data itself, or the region re-animates on every refresh.
 */
@Composable
fun <T> AnimatedSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "swap",
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { swapTransform(reduced) },
        label = label,
        content = content,
    )
}

/**
 * Two peer pages with an order — a pair of tabs, a pager.
 *
 * Slides a sixth of the width rather than the whole of it. These sit inside a
 * screen that is not moving, so a full-width slide would claim the screen
 * itself had changed; a short travel says "sideways, within this" and stops.
 */
@Composable
fun <T> AnimatedPage(
    targetState: T,
    forward: Boolean,
    modifier: Modifier = Modifier,
    label: String = "page",
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.effects) togetherWith fadeOut(Motion.effectsFast) using resize()
            } else {
                val sign = if (forward) 1 else -1
                (
                    slideInHorizontally(Motion.spatialOffset) { sign * it / PAGE_TRAVEL } +
                        fadeIn(Motion.effects)
                    ) togetherWith (
                    slideOutHorizontally(Motion.spatialOffset) { -sign * it / PAGE_TRAVEL } +
                        fadeOut(Motion.effectsFast)
                    ) using resize()
            }
        },
        label = label,
        content = content,
    )
}

private const val PAGE_TRAVEL = 6

/**
 * A value that changes in place: a balance, a fee, a block height, a countdown.
 *
 * The new value rises in from below and the old one leaves upward when the
 * number grew, and the reverse when it shrank. Direction carries meaning — a
 * balance going up and a balance going down should not look identical — and it
 * is the one piece of information a cross-fade throws away.
 *
 * [upward] has no default on purpose. Getting the comparison wrong silently
 * animates every change the same way, which is worse than not animating.
 */
@Composable
fun AnimatedValue(
    value: String,
    upward: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.effects) togetherWith fadeOut(Motion.effectsFast) using resize()
            } else {
                val sign = if (upward) 1 else -1
                (
                    slideInVertically(Motion.spatialOffset) { sign * it } + fadeIn(Motion.effects)
                    ) togetherWith (
                    slideOutVertically(Motion.spatialOffset) { -sign * it } +
                        fadeOut(Motion.effectsFast)
                    ) using resize()
            }
        },
        label = "value",
    ) { shown ->
        // `maxLines` defaults to one because the usual subject is a figure,
        // and a figure that wraps has already gone wrong. It is a parameter
        // rather than a constant because some of these lines are sentences
        // with a number in them — a node's sync status, say — and silently
        // clamping those truncates the end of the sentence at a small width
        // or a raised font scale.
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The last value there was, so something leaving still has something to say.
 *
 * Anything that animates out outlives the state that produced it. An
 * `AnimatedVisibility` keeps its content composed while it collapses, and an
 * [AnimatedSwap] keeps the outgoing branch composed while it fades — but the
 * value driving them is already null by then. Read directly, the content
 * blanks itself and *then* animates away, which reads as two glitches rather
 * than one dismissal.
 *
 * Written during composition, which is safe because the write only happens on
 * the branch where the value is not read back.
 */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val held = remember { mutableStateOf<T?>(null) }
    if (value != null) held.value = value
    return value ?: held.value
}

// ---------------------------------------------------------------------------
// Arriving
// ---------------------------------------------------------------------------

/** How far arriving content rises, in pixels at 1x density. Small on purpose. */
private const val ARRIVE_RISE_PX = 28f

/**
 * The entrance for content that has just finished loading.
 *
 * Rises a short distance and fades in. The distance is small on purpose: this
 * fires when data arrives, which is exactly when the reader is trying to read
 * it, so the motion has to be over before it becomes something to wait
 * through.
 *
 * [index] staggers elements against each other. It is capped — see
 * [Motion.STAGGER_MAX_STEPS] — so a long group does not take a second and a
 * half to assemble.
 *
 * Not for rows inside a `LazyColumn`. A lazy item is composed when it scrolls
 * into view, so this would replay the entrance every time a row came back on
 * screen and the list would shimmer as it was scrolled. Lazy lists want
 * `Modifier.animateItem()`, which animates a row changing position rather
 * than a row appearing.
 */
@Composable
fun Modifier.arrive(index: Int = 0, enabled: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    if (!enabled || reduced) return this

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(Motion.STAGGER_MAX_STEPS) * Motion.STAGGER_STEP_MS).toLong())
        progress.animateTo(1f, Motion.spatial)
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * ARRIVE_RISE_PX
    }
}

// ---------------------------------------------------------------------------
// Waiting
// ---------------------------------------------------------------------------

/**
 * A placeholder shaped like the thing that is coming.
 *
 * A spinner says "something is happening". A skeleton says "a list of rows is
 * happening, and it will start about here" — the page does not jump when the
 * data lands, because the space was already the right size in the right place.
 * That is most of what makes a modern app feel quick when it is not.
 *
 * The sweep is a `tween`, not a spring, and deliberately so: it is a loop with
 * a period, not a response to anything.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    if (LocalReducedMotion.current) {
        Box(modifier.height(height).clip(shape).background(base))
        return
    }

    val transition = rememberInfiniteTransition(label = "skeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.SHIMMER_PERIOD_MS, easing = Motion.emphasised),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Box(
        modifier
            .height(height)
            .clip(shape)
            .drawWithCache {
                // The band is two widths wide and travels three, so it is
                // fully off one edge before it reappears at the other and
                // there is no visible restart.
                val span = size.width * 2f
                val start = -span + phase * (size.width + span)
                val brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + span, 0f),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A skeleton in the shape of one list row: an icon, two lines and an amount. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Skeleton(Modifier.size(36.dp), height = 36.dp, shape = RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Skeleton(Modifier.fillMaxWidth(0.55f), height = 14.dp)
            Skeleton(Modifier.fillMaxWidth(0.32f), height = 11.dp)
        }
        Skeleton(Modifier.width(64.dp), height = 14.dp)
    }
}

/**
 * A dot that breathes, for "this is live and still connected".
 *
 * Opacity only, never size. A pulsing dot that changes size reflows nothing,
 * but it draws the eye on every beat, and this indicator sits next to a
 * balance the user is trying to read.
 */
@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val alpha = if (LocalReducedMotion.current) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.PULSE_PERIOD_MS, easing = Motion.emphasised),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        animated
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Confirming
// ---------------------------------------------------------------------------

/**
 * A tick that draws itself, for the moment a payment settles.
 *
 * This is the one animation in the app allowed to be a performance. Everything
 * else here exists to stay out of the way; this exists to be believed. A
 * merchant glancing at a phone across a counter needs to know the money
 * arrived, and a tick that was simply always there looks exactly like a
 * screenshot of a tick that was always there.
 *
 * The ring arrives first and the stroke follows, because a tick that draws
 * before its container reads as a rendering glitch.
 */
@Composable
fun SuccessCheck(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val reduced = LocalReducedMotion.current
    val ring = remember { Animatable(if (reduced) 1f else 0f) }
    val stroke = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        ring.animateTo(1f, Motion.spatial)
        stroke.animateTo(1f, Motion.spatialSlow)
    }

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = ring.value
                scaleY = ring.value
                alpha = ring.value
            }
            .drawWithCache {
                val thickness = this.size.minDimension * 0.08f
                val inset = thickness / 2f
                // The three points of a tick, as fractions of the box.
                val a = Offset(this.size.width * 0.28f, this.size.height * 0.52f)
                val b = Offset(this.size.width * 0.44f, this.size.height * 0.68f)
                val c = Offset(this.size.width * 0.74f, this.size.height * 0.34f)
                val firstLeg = (b - a).getDistance()
                val secondLeg = (c - b).getDistance()

                onDrawBehind {
                    drawCircle(
                        color = color,
                        radius = this.size.minDimension / 2f - inset,
                        style = Stroke(width = thickness),
                    )
                    val drawn = stroke.value * (firstLeg + secondLeg)
                    if (drawn <= 0f) return@onDrawBehind
                    val firstEnd = if (drawn >= firstLeg) b else a + (b - a) * (drawn / firstLeg)
                    drawLine(color, a, firstEnd, thickness, StrokeCap.Round)
                    if (drawn > firstLeg) {
                        val rest = (drawn - firstLeg) / secondLeg
                        drawLine(color, b, b + (c - b) * rest, thickness, StrokeCap.Round)
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------
// Continuity between screens
// ---------------------------------------------------------------------------

/**
 * The two scopes a shared element needs, published by `AppNavHost`.
 *
 * They are composition locals rather than parameters because screens in this
 * app deliberately take plain lambdas and know nothing about navigation, so
 * that they can be previewed and tested on their own. Threading a
 * `SharedTransitionScope` through fifty screen signatures to reach four
 * amounts and two icons would trade that away for very little.
 *
 * Both default to null, and [continuity] is a no-op when either is missing.
 * A preview renders the row; it simply does not animate into anything.
 */
val LocalSharedTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> =
    staticCompositionLocalOf { null }

val LocalNavAnimatedScope: ProvidableCompositionLocal<AnimatedVisibilityScope?> =
    staticCompositionLocalOf { null }

/**
 * Marks an element as the same object on both sides of a navigation.
 *
 * The amount on a transaction row and the amount on that transaction's detail
 * screen are the same number about the same payment. Given the same [key] on
 * both, this makes them one thing that moves and resizes, instead of two
 * things where one disappears and another appears — which is the difference
 * between following a value across a transition and having to find it again.
 *
 * [key] must be unique per object *and* per role: `"tx-amount-$txId"`, not
 * `txId`. Two elements claiming one key on the same screen is a crash, and two
 * unrelated screens sharing a key is a value that flies across the display for
 * no reason.
 */
@Composable
fun Modifier.continuity(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (LocalReducedMotion.current) return this

    return with(shared) {
        this@continuity.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animated,
            // These run only when the key finds its partner: `sharedBounds`
            // gates them on `isMatchFound`, so an element whose counterpart is
            // not composed is left strictly alone and cannot fade out from
            // under the row that contains it.
            //
            // Given that, a cross-fade is wanted rather than merely harmless.
            // The two halves of a match are the same value but not the same
            // rendering — an amount at body size in a row and the same amount
            // at forty-four points on a detail screen, a pill reading "3 conf"
            // against one reading "Confirmed" — and `sharedBounds` draws both
            // throughout the morph. Without the fade they are simply
            // superimposed, and the text ghosts.
            enter = fadeIn(Motion.effects),
            exit = fadeOut(Motion.effectsFast),
            boundsTransform = { _, _ -> Motion.bounds },
            // Scale rather than remeasure. The row's amount and the detail
            // screen's amount are the same string at two very different type
            // sizes; remeasuring reflows the text mid-flight and the line
            // breaks jump. Scaling treats it as one object being resized,
            // which is the fiction the whole transition depends on.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

// ---------------------------------------------------------------------------
// Touch
// ---------------------------------------------------------------------------

/**
 * Shrinks slightly while held.
 *
 * Material's ripple says where the finger is. It does not say that the thing
 * under the finger is one object responding as a whole, which is what a card
 * wants to say — and on a large card the ripple is diffuse enough that a tap
 * near the edge reads as no feedback at all.
 *
 * For cards and rows, not for buttons: a Material button already has a state
 * layer and a shape small enough for the ripple to fill, and scale on top of
 * that feels rubbery.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    scale: Float = 0.975f,
): Modifier {
    val reduced = LocalReducedMotion.current
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            pressed = when (interaction) {
                is PressInteraction.Press -> true
                is PressInteraction.Release, is PressInteraction.Cancel -> false
                else -> pressed
            }
        }
    }

    val factor by animateFloatAsState(
        targetValue = if (pressed && !reduced) scale else 1f,
        animationSpec = Motion.spatialFast,
        label = "press",
    )
    return scale(factor)
}
