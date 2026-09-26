package com.btcpayapp.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the relationships the app's motion depends on, not the numbers.
 *
 * A test that asserts 380 equals 380 protects nothing — it fails whenever the
 * constant is deliberately changed and passes whenever it is changed
 * consistently, which is exactly backwards. What is worth pinning is the
 * structure: the reasons given in [Motion]'s documentation for why one spring
 * differs from another. Those are the things a later edit can break silently,
 * because a slightly wrong damping ratio does not crash, it just makes the app
 * feel cheap in a way nobody can point at.
 */
class MotionTest {

    private fun spec(spec: Any): SpringSpec<*> =
        spec as? SpringSpec<*> ?: error("Motion specs are expected to be springs, got $spec")

    @Test
    fun `opacity never overshoots`() {
        // An overshooting fade has to pass through more-than-opaque to reach
        // its target, which shows up as a flicker rather than as character.
        // Every effects spring must therefore be critically damped or slower.
        listOf(
            "effects" to Motion.effects,
            "effectsFast" to Motion.effectsFast,
            "effectsSlow" to Motion.effectsSlow,
            "color" to Motion.color,
        ).forEach { (name, value) ->
            assertTrue(
                "$name must not overshoot",
                spec(value).dampingRatio >= 1f,
            )
        }
    }

    @Test
    fun `movement is under-damped so it settles with weight`() {
        listOf(
            "spatial" to Motion.spatial,
            "spatialFast" to Motion.spatialFast,
            "spatialSlow" to Motion.spatialSlow,
            "spatialOffset" to Motion.spatialOffset,
            "spatialSize" to Motion.spatialSize,
            "spatialDp" to Motion.spatialDp,
        ).forEach { (name, value) ->
            assertTrue("$name must carry some overshoot", spec(value).dampingRatio < 1f)
        }
    }

    @Test
    fun `whole surfaces arrive and stop`() {
        // A chip overshooting reads as life. A full screen overshooting reads
        // as broken, and a shared element overshooting sails past the very
        // thing the eye was told to follow.
        assertEquals(1f, spec(Motion.screenSlide).dampingRatio, 0f)
        assertEquals(1f, spec(Motion.bounds).dampingRatio, 0f)
    }

    @Test
    fun `fast is stiffer than default is stiffer than slow`() {
        assertTrue(spec(Motion.spatialFast).stiffness > spec(Motion.spatial).stiffness)
        assertTrue(spec(Motion.spatial).stiffness > spec(Motion.spatialSlow).stiffness)
        assertTrue(spec(Motion.effectsFast).stiffness > spec(Motion.effects).stiffness)
        assertTrue(spec(Motion.effects).stiffness > spec(Motion.effectsSlow).stiffness)
    }

    @Test
    fun `a fade finishes before the movement it accompanies`() {
        // Otherwise a sliding screen spends its whole journey half-transparent.
        assertTrue(
            "effects must outrun spatial",
            spec(Motion.effects).stiffness > spec(Motion.spatial).stiffness,
        )
    }

    @Test
    fun `a staggered list finishes assembling promptly`() {
        // The stagger is a hint that rows arrived together, not a cascade to
        // be watched. Cap times step is the worst case any list can cost.
        val worstCaseMs = Motion.STAGGER_MAX_STEPS * Motion.STAGGER_STEP_MS
        assertTrue("stagger tail is $worstCaseMs ms", worstCaseMs <= 200)
    }
}
