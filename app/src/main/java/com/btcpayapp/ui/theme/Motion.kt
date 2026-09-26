package com.btcpayapp.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion vocabulary: springs, not durations.
 *
 * Everything that moves on screen — a screen change, a section opening, a
 * balance changing, a row arriving in a list — reads from here, so the whole
 * app accelerates and settles the same way. Mixing physics-based motion in one
 * place with fixed-duration easing in another is what makes an interface feel
 * assembled from parts rather than designed.
 *
 * Springs rather than `tween` for a concrete reason, not fashion. A spring
 * carries velocity, so an interrupted animation — a second tap, a back gesture
 * abandoned halfway, a section closed while it is still opening — continues
 * from where it really was instead of snapping to a new curve. That
 * discontinuity is most of what makes a transition feel dated.
 *
 * ## Where the numbers come from
 *
 * They are Material 3's expressive motion tokens, to the decimal
 * (`androidx.compose.material3.tokens.ExpressiveMotionTokens`).
 *
 * Expressive rather than standard because this app is held in one hand at a
 * market stall. Standard is tuned to get out of the way (stiffness 700, damping
 * 0.9 — quick, flat, forgettable); expressive is softer and carries a trace of
 * overshoot (380 / 0.8), which is what reads as weight rather than as a
 * redraw. Opacity is the exception and stays critically damped: an overshooting
 * fade would have to pass through more-than-opaque to get where it is going,
 * which shows up as a flicker.
 *
 * ## Why the tokens are restated rather than imported
 *
 * Because there is no way to import them. Material 3 1.4.0 seals off every
 * route to its own expressive motion: `MaterialExpressiveTheme`, the
 * `MotionScheme` interface, `MotionScheme.expressive()`, the `MaterialTheme`
 * overload that accepts a scheme, and the token class itself are all
 * `internal` in the Kotlin metadata — several of them are public in the
 * compiled class file, which is a trap rather than an opening.
 *
 * Restating them is also what lets this file be read outside a composition,
 * which it has to be: a good third of the motion in this app is declared at
 * file scope, because `NavHost` takes its transitions as plain lambdas and a
 * transition rebuilt per recomposition is a transition that restarts.
 *
 * The cost is one real inconsistency, recorded here rather than hidden: the
 * motion *inside* a Material component — a Switch thumb travelling, a
 * navigation-bar indicator sliding — still runs on Material's standard
 * springs, which are about twice as stiff. Revisit when the expressive entry
 * point ships as public API.
 */
object Motion {

    // --- Expressive spatial: position, size, scale, anything with extent ----

    private const val SPATIAL_DAMPING = 0.8f
    private const val SPATIAL_STIFFNESS = 380f

    /** For movement the user did not ask for and should barely notice. */
    private const val FAST_SPATIAL_DAMPING = 0.6f
    private const val FAST_SPATIAL_STIFFNESS = 800f

    /** For large surfaces, where the same stiffness would read as a snap. */
    private const val SLOW_SPATIAL_DAMPING = 0.8f
    private const val SLOW_SPATIAL_STIFFNESS = 200f

    // --- Expressive effects: opacity and colour, critically damped ---------

    private const val EFFECTS_DAMPING = 1f
    private const val EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_STIFFNESS = 3800f
    private const val SLOW_EFFECTS_STIFFNESS = 800f

    // --- Typed spatial specs ------------------------------------------------
    //
    // Typed rather than generic, unlike `MotionScheme`, because a spring needs
    // to know when it has arrived and "close enough" is measured in the unit
    // being animated. A generic spec carries no visibility threshold, so an
    // `IntOffset` spring is judged against a threshold meant for a fraction and
    // spends its last dozen frames travelling a distance smaller than a pixel.

    val spatial: FiniteAnimationSpec<Float> =
        spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    val spatialFast: FiniteAnimationSpec<Float> =
        spring(FAST_SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS)

    val spatialSlow: FiniteAnimationSpec<Float> =
        spring(SLOW_SPATIAL_DAMPING, SLOW_SPATIAL_STIFFNESS)

    val spatialOffset: FiniteAnimationSpec<IntOffset> =
        spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)

    val spatialOffsetFast: FiniteAnimationSpec<IntOffset> =
        spring(FAST_SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)

    val spatialSize: FiniteAnimationSpec<IntSize> =
        spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntSize.VisibilityThreshold)

    val spatialDp: FiniteAnimationSpec<Dp> =
        spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, Dp.VisibilityThreshold)

    /**
     * One rectangle becoming another: a row growing into the screen it opens.
     *
     * Critically damped, like [screenSlide] and for the same reason. A shared
     * element overshooting means the thing the eye is following sails past its
     * destination and comes back, which breaks the very illusion — that this
     * is one object moving — the transition exists to create.
     */
    val bounds: FiniteAnimationSpec<Rect> = spring(1f, 380f, Rect.VisibilityThreshold)

    /** Alias for [spatial], read at call sites that animate a scale factor. */
    val spatialScale: FiniteAnimationSpec<Float> = spatial

    /**
     * Whole screens sliding, and nothing else.
     *
     * Critically damped, unlike [spatial]: a trace of overshoot gives a chip or
     * a card some life, but a full-screen surface that arrives and then wobbles
     * back a few pixels looks broken rather than lively. Softer than the other
     * springs too — a screen crossing the entire display in the time a small
     * control takes to move a few dp reads as a snap, not as motion.
     */
    val screenSlide: FiniteAnimationSpec<IntOffset> =
        spring(1f, 420f, IntOffset.VisibilityThreshold)

    // --- Typed effects specs ------------------------------------------------

    val effects: FiniteAnimationSpec<Float> =
        spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    /** For changes the user did not ask for and should barely notice. */
    val effectsFast: FiniteAnimationSpec<Float> =
        spring(EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS)

    /** For a fade that has to be seen to be understood — a state changing. */
    val effectsSlow: FiniteAnimationSpec<Float> =
        spring(EFFECTS_DAMPING, SLOW_EFFECTS_STIFFNESS)

    val color: FiniteAnimationSpec<Color> = spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    // --- The one place durations are honest ---------------------------------

    /**
     * Emphasised easing, for the handful of animations that are not a response
     * to anything.
     *
     * A spring models a thing being pushed and settling. A shimmer sweeping
     * across a placeholder, or a dot pulsing to say a socket is still open, is
     * not being pushed by anyone — it is a loop, and a loop needs a period. Two
     * curves, because the same asymmetry applies: leaving is quicker than
     * arriving.
     */
    val emphasised: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasisedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasisedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** One sweep of a loading placeholder, milliseconds. */
    const val SHIMMER_PERIOD_MS: Int = 1400

    /** One beat of a "still connected" indicator, milliseconds. */
    const val PULSE_PERIOD_MS: Int = 2000

    /**
     * How long a list row waits behind the one above it, milliseconds.
     *
     * Small on purpose. A stagger is a hint that the rows arrived together, not
     * a cascade to be watched; past about 30ms per row a list of ten takes a
     * third of a second to finish assembling, and the reader is left waiting
     * for their own data.
     */
    const val STAGGER_STEP_MS: Int = 18

    /** After this many rows the stagger stops accumulating. */
    const val STAGGER_MAX_STEPS: Int = 8
}

/**
 * Whether the user has asked the system to stop animating things.
 *
 * Android exposes this as `ANIMATOR_DURATION_SCALE`, which developer options
 * and — more to the point — the accessibility "Remove animations" setting both
 * write to. Honouring it is not decoration: motion sickness and vestibular
 * disorders are real, and a payment app that ignores the setting is one the
 * affected user has to stop using.
 *
 * It is read as a composition local rather than consulted at each call site so
 * that the decision is made once, and so previews and tests can force either
 * value.
 *
 * What it turns off is *travel*, not feedback: a screen still arrives, it just
 * arrives by fading rather than by crossing the display, and a balance still
 * changes, it just changes without counting up. Suppressing feedback entirely
 * would leave the user unable to tell whether their tap registered.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * Reads the system animation scale.
 *
 * Deliberately not observed for changes. The setting is changed roughly never,
 * and the alternative — a `ContentObserver` on `Settings.Global` for the life of
 * the process — is a watch on a system table to catch an event that arrives
 * once a year. It is re-read whenever the activity is recreated, which is what
 * happens after a settings change in practice.
 */
@Composable
@ReadOnlyComposable
internal fun systemPrefersReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
