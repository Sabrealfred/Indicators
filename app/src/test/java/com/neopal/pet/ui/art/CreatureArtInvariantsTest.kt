package com.neopal.pet.ui.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Morphology
import com.neopal.pet.domain.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the creature drawing is allowed to do, stated as properties rather than as pictures.
 *
 * The bug this exists for is the figure-of-eight of PLAN.md 6.4: through the middle of the
 * bipedal-to-quadrupedal sweep the barrel and the head were two blobs of the same size side by
 * side, each stroking its own outline, so a sliver of barrel showed *outside* the head and the
 * doubled key line read as a second body. Nothing in this repository could see that, because
 * nothing in this repository had ever looked at a draw call.
 *
 * These tests look. [NpArtRecorder] is a [DrawScope] that records instead of rasterising, so the
 * real [drawCreature] runs against it and every circle, oval, line and arc it emits is available
 * to assert on. Golden images are deliberately avoided: an art change is supposed to change the
 * pixels, and a test that breaks on every art change is a test that gets deleted.
 *
 * Three things about it that are not obvious, all worth knowing before trusting it:
 *
 *  0. **It needs one build setting to run at all.** Everything the creature draws goes through a
 *     Compose `Path` at some point, and a Compose `Path` is an `android.graphics.Path`, which a
 *     unit-test JVM stubs out. Unless `testOptions.unitTests.isReturnDefaultValues` is on, that
 *     stub throws, and every test below stands aside rather than reporting a failure it cannot
 *     tell apart from a real one. [theAnimationCurvesStayInTheirBanks] needs no path and always
 *     runs, so the class is never silently empty.
 *  1. **Paths carry no geometry here.** A Compose `Path` is backed by `android.graphics.Path`,
 *     which on a unit-test JVM is a stub that remembers nothing. The silhouette, the tail and
 *     the horns are paths, so they are counted but not measured. Everything asserted below is
 *     therefore measured off the primitives that *do* carry geometry — chiefly the belly rings,
 *     which are the only non-path shape that traces a blob's own ellipse.
 *  2. **It is not a picture.** When one of these fails, the fastest way to see what happened is
 *     the rasteriser in the session scratchpad (`ag/render.sh` plus `ag/raster.py`): it compiles
 *     this same `CreatureArt.kt` against Compose-shaped stubs and turns the recorded draw calls
 *     into a PNG. The assertion says which invariant broke; the PNG says what it looks like.
 *     `scratchpad/arttest.sh` runs this same file the same way, which is how it can be worked on
 *     at all on a machine with no Android SDK.
 */
class CreatureArtInvariantsTest {

    // ---------------------------------------------------------------- the stance sweep

    /**
     * The seam rule, and the figure-of-eight regression itself.
     *
     * A barrel that has come out from behind the head is a body; a barrel that pokes past the
     * head on *both* sides at once is not a body, it is a second outline a few pixels outside
     * the first. Bodies grow out backwards, over the rump, so at every point in the sweep the
     * barrel may break the head's outline on one side only.
     */
    @Test
    fun theBarrelNeverBreaksTheHeadOnBothSidesAtOnce() {
        npArtSweep().forEach { (stance, blobs) ->
            if (blobs.size < 2) return@forEach
            val head = blobs.last()
            val trunk = blobs.first()
            val pokesLeft = trunk.left < head.left - NP_SLACK
            val pokesRight = trunk.right > head.right + NP_SLACK
            assertTrue(
                "stance $stance: the barrel breaks the head's outline on both sides " +
                    "(head ${head.left}..${head.right}, barrel ${trunk.left}..${trunk.right}) - " +
                    "that is a doubled key line, not a body",
                !(pokesLeft && pokesRight),
            )
        }
    }

    /**
     * A body grows out behind the shoulders. The barrel is allowed to clear the head backwards
     * and nowhere else, at any point in the sweep — the moment it shows in front of the face it
     * is a line beside the muzzle rather than a chest.
     */
    @Test
    fun theBarrelOnlyEverClearsTheHeadBackwards() {
        npArtSweep().forEach { (stance, blobs) ->
            if (blobs.size < 2) return@forEach
            val head = blobs.last()
            val trunk = blobs.first()
            assertTrue(
                "stance $stance: the barrel (to ${trunk.right}) has come out past the front of " +
                    "the head (to ${head.right})",
                trunk.right <= head.right + NP_SLACK,
            )
        }
    }

    /**
     * Shape runs early, travel runs late — the two clocks that killed the figure-of-eight. For
     * the first third of the sweep the barrel has not come out at all: it is still the head's
     * own outline, so there is only ever one key line to read.
     */
    @Test
    fun theBarrelStaysHiddenThroughTheFirstThirdOfTheSweep() {
        assertEquals(
            "an upright creature should draw one blob, not a barrel parked behind its head",
            1, npArtBlobs(npArtQuadrupedSpec(0f), NP_STILL).size,
        )
        npArtSweep().filter { it.first <= 1f / 3f }.forEach { (stance, blobs) ->
            if (blobs.size < 2) return@forEach
            val head = blobs.last()
            val trunk = blobs.first()
            assertTrue(
                "stance $stance: the barrel (${trunk.left}..${trunk.right}) is already breaking " +
                    "the head's outline (${head.left}..${head.right}) while it should still be hidden",
                trunk.left >= head.left - NP_SLACK && trunk.right <= head.right + NP_SLACK,
            )
        }
    }

    /**
     * Once it starts to emerge it keeps emerging. A barrel that came out, went back in and came
     * out again would flicker a second outline on and off through the middle of the sweep, which
     * is the failure the eight was made of even when no single frame looks wrong.
     */
    @Test
    fun theBarrelEmergesMonotonically() {
        var previous = -Float.MAX_VALUE
        npArtSweep().forEach { (stance, blobs) ->
            if (blobs.size < 2) return@forEach
            val showing = blobs.last().left - blobs.first().left
            assertTrue(
                "stance $stance: the barrel went back inside the head ($showing after $previous)",
                showing >= previous - NP_SLACK,
            )
            previous = showing
        }
        assertTrue("the barrel never came out at all", previous > 0f)
    }

    /** By the far end of the sweep the barrel is the body: it has to be visibly wider than the head. */
    @Test
    fun theBarrelIsAProperBodyByTheEndOfTheSweep() {
        val blobs = npArtBlobs(npArtQuadrupedSpec(1f), NP_STILL)
        assertTrue("a fully quadrupedal creature should draw two blobs, drew ${blobs.size}", blobs.size >= 2)
        val head = blobs.last()
        val trunk = blobs.first()
        assertTrue(
            "at full stance the barrel (half-width ${trunk.halfW}) is no wider than the head " +
                "(half-width ${head.halfW}); nothing reads as a body",
            trunk.halfW > head.halfW * 1.15f,
        )
    }

    /** Whatever the barrel does horizontally, it never climbs above the head: heads sit on top. */
    @Test
    fun theHeadStaysAboveTheBarrel() {
        npArtSweep().forEach { (stance, blobs) ->
            if (blobs.size < 2) return@forEach
            assertTrue(
                "stance $stance: the head has sunk to or below the barrel",
                blobs.last().cy <= blobs.first().cy + NP_SLACK,
            )
        }
    }

    // ---------------------------------------------------------------- the turned head

    /**
     * A head seen at an angle foreshortens. The far eye narrows; the near one keeps its width.
     * Getting this wrong is what made the quadruped look like it was facing the viewer while
     * walking sideways.
     */
    @Test
    fun theFarEyeNarrowsOnATurnedHead() {
        val upright = npArtEyes(npArtQuadrupedSpec(0f))
        assertEquals(
            "a front-facing head must draw two eyes of the same width",
            upright[0].w.toDouble(), upright[1].w.toDouble(), 0.01,
        )
        val turned = npArtEyes(npArtQuadrupedSpec(1f))
        val far = turned.minByOrNull { it.cx }!!
        val near = turned.maxByOrNull { it.cx }!!
        assertTrue(
            "on a fully turned head the far eye (${far.w}) is not narrower than the near one (${near.w})",
            far.w < near.w * 0.7f,
        )
        assertTrue(
            "the far eye has collapsed to nothing (${far.w} against ${near.w})",
            far.w > near.w * 0.2f,
        )
    }

    /**
     * The scholar's glasses hang off the same two functions the eyes do, so that a scholar on
     * all fours is still wearing them rather than carrying them on its temple.
     */
    @Test
    fun theGlassesSitOnTheEyesAtEveryTurn() {
        for (i in 0..5) {
            val stance = i / 5f
            val spec = npArtQuadrupedSpec(stance, branch = EvolutionBranch.SCHOLAR)
            val rec = npArtRender(spec, NP_STILL)
            val eyes = npArtEyes(spec)
            // The glasses go on after the face and, on this fixture, nothing goes on after
            // them — no hat, no sweat, no elder's whiskers. Picking them by position would be
            // circular: where they sit is the thing under test.
            val lenses = rec.marks
                .filter { it.kind == NP_OVAL && !it.strokeWidth.isNaN() }
                .takeLast(2)
                .sortedBy { it.cx }
            assertEquals("a scholar should wear two lenses at stance $stance", 2, lenses.size)
            eyes.sortedBy { it.cx }.forEachIndexed { side, eye ->
                val lens = lenses[side]
                assertTrue(
                    "stance $stance: lens ${lens.cx} is not over eye ${eye.cx} - it is on the temple",
                    abs(lens.cx - eye.cx) < eye.w * 0.35f,
                )
                assertTrue(
                    "stance $stance: lens (${lens.w}) does not cover its eye (${eye.w})",
                    lens.w >= eye.w,
                )
            }
        }
    }

    // ---------------------------------------------------------------- the dilating pupil

    /** The pupil swells with the creature's interest in food, and only ever in one direction. */
    @Test
    fun thePupilGrowsWithFoodFocus() {
        var previous = 0f
        for (i in 0..5) {
            val focus = i / 5f
            val iris = npArtIris(NP_STILL.copy(foodFocus = focus))
            val area = iris.w * iris.h
            assertTrue(
                "foodFocus $focus shrank the pupil ($area against $previous)",
                area > previous,
            )
            previous = area
        }
    }

    /**
     * Dilation is spent on the eye. A creature that watches a plate arrive should not also
     * change shape, so nothing outside the face is allowed to move with [CreatureFrame.foodFocus].
     */
    @Test
    fun foodFocusMovesNothingBelowTheFace() {
        val spec = npArtPlainSpec()
        val calm = npArtRender(spec, NP_STILL).marks
        val keen = npArtRender(spec, NP_STILL.copy(foodFocus = 1f)).marks
        assertEquals("food focus added or removed draw calls", calm.size, keen.size)
        val head = npArtBlobs(spec, NP_STILL).last()
        calm.indices.forEach { i ->
            if (calm[i] != keen[i]) {
                val m = keen[i]
                assertTrue(
                    "foodFocus moved something at (${m.cx}, ${m.cy}), which is not on the face: $m",
                    m.cy < head.cy && abs(m.cx - head.cx) < head.halfW * 1.4f,
                )
            }
        }
        assertTrue(
            "foodFocus changed nothing at all; the pupil is not wired up",
            calm.indices.any { calm[it] != keen[it] },
        )
    }

    // ---------------------------------------------------------------- growing inside a stage

    /**
     * A stage is not a step function any more: proportions drift across it. Two things have to
     * hold for that to be growth rather than noise — it only ever goes one way, and it never
     * carries a creature past the stage it has not reached yet.
     */
    @Test
    fun proportionsDriftOneWayInsideAStage() {
        NP_STAGES.forEach { stage ->
            val samples = (0..5).map { i ->
                val spec = npArtPlainSpec(stage = stage, stageProgress = i / 5f)
                val rec = npArtRender(spec, NP_STILL)
                npArtShadowRadius(rec) to npArtEyes(spec).first().w
            }
            npArtAssertMonotone("$stage body radius", samples.map { it.first })
            npArtAssertMonotone("$stage eye width", samples.map { it.second })
        }
    }

    /** Growing all the way through a stage must not land on the next stage's numbers. */
    @Test
    fun driftNeverOvershootsIntoTheNextStage() {
        NP_STAGES.zipWithNext().forEach { (stage, next) ->
            val ripe = npArtShadowRadius(npArtRender(npArtPlainSpec(stage = stage, stageProgress = 1f), NP_STILL))
            val fresh = npArtShadowRadius(npArtRender(npArtPlainSpec(stage = next, stageProgress = 0f), NP_STILL))
            val start = npArtShadowRadius(npArtRender(npArtPlainSpec(stage = stage, stageProgress = 0f), NP_STILL))
            assertTrue(
                "a ripe $stage (body radius $ripe) has already reached a fresh $next ($fresh)",
                abs(ripe - start) < abs(fresh - start) || fresh == start,
            )
        }
    }

    // ---------------------------------------------------------------- the outer edge

    /**
     * Nothing is drawn miles away from the creature.
     *
     * [drawCreature] is handed a centre and a size, and everything it draws belongs to a body of
     * that size. This is the cheap net that catches a part anchored to the wrong thing — the
     * failure where an ear, a hat or a tail is off in the corner of the scene and every other
     * assertion here still passes. The bounds are loose on purpose: they are a net, not a
     * judgement about how far a tail may swing.
     */
    @Test
    fun nothingIsDrawnFarFromTheCreature() {
        npArtCatalogue().forEach { spec ->
            NP_EXTREMES.forEach { frame ->
                npArtRender(spec, frame).marks.filter { !it.cx.isNaN() }.forEach { m ->
                    assertTrue(
                        "$spec drew $m ${(abs(m.cx - NP_CENTRE.x) + m.w / 2f) / NP_UNIT} of a " +
                            "creature's own size from the centre line",
                        abs(m.cx - NP_CENTRE.x) + m.w / 2f < NP_UNIT * NP_WIDE,
                    )
                    assertTrue(
                        "$spec drew $m above the creature",
                        NP_CENTRE.y - (m.cy - m.h / 2f) < NP_UNIT * NP_UP,
                    )
                    assertTrue(
                        "$spec drew $m below the creature",
                        (m.cy + m.h / 2f) - NP_CENTRE.y < NP_UNIT * NP_DOWN,
                    )
                }
            }
        }
    }

    /**
     * The two animation curves the whole file is timed off, checked without drawing anything.
     *
     * This is the one test here that needs no Compose `Path`, which is deliberate: where the
     * rest of the class stands aside (see [npArtRender]) this still runs, so the class always
     * reports something and a silently inert suite cannot pass for a passing one.
     */
    @Test
    fun theAnimationCurvesStayInTheirBanks() {
        val period = 1.4f
        var previous = pingPong(0f, period)
        assertEquals("a hop starts on the ground", 0.0, previous.toDouble(), 1e-6)
        for (i in 1..280) {
            val at = i * period / 100f
            val v = pingPong(at, period)
            assertTrue("pingPong left 0..1 at $at: $v", v >= 0f && v <= 1f)
            assertTrue("pingPong jumped at $at: $previous to $v", abs(v - previous) < 0.05f)
            previous = v
        }
        assertEquals("a hop peaks halfway through", 1.0, pingPong(period / 2f, period).toDouble(), 1e-6)
        for (i in 0..40) {
            val at = i / 10f
            assertTrue("wobble left its amplitude at $at", abs(wobble(at, 1.5f, 0.4f)) <= 0.4f)
            assertEquals(
                "wobble is not periodic at $at",
                wobble(at, 1.5f).toDouble(), wobble(at + 1f / 1.5f, 1.5f).toDouble(), 1e-4,
            )
        }
    }

    /**
     * The same creature in the same frame draws the same thing twice. Nothing in this file may
     * reach for a clock or a random number: everything visible is derived from stored state, so
     * a second render has to agree with the first, call for call.
     */
    @Test
    fun theSameFrameDrawsTheSameThingTwice() {
        npArtCatalogue().forEach { spec ->
            NP_EXTREMES.forEach { frame ->
                val once = npArtRender(spec, frame).marks
                val twice = npArtRender(spec, frame).marks
                assertEquals("$spec did not draw the same thing twice", once, twice)
            }
        }
    }
}

// -------------------------------------------------------------------- the fixtures

private val NP_CENTRE = Offset(160f, 190f)
private const val NP_UNIT = 320f

/** Half an art pixel at this size: below it nothing is visible either way. */
private const val NP_SLACK = 0.5f

// How far from the centre anything may be drawn, as a fraction of the size the caller asked for.
// A third again as far as the widest thing the art draws today, which leaves room for a longer
// tail or a taller hat and none at all for a shape anchored to the wrong origin.
private const val NP_WIDE = 0.75f
private const val NP_UP = 0.85f
private const val NP_DOWN = 0.70f

private val NP_STAGES = listOf(
    LifeStage.BABY,
    LifeStage.CHILD,
    LifeStage.TEEN,
    LifeStage.ADULT,
    LifeStage.ELDER,
)

/** A creature standing still: no bob, no blink, no swing. The baseline every sweep varies from. */
private val NP_STILL = CreatureFrame()

private fun npArtMorphology(stance: Float) = Morphology(
    muzzleLength = 0.20f,
    earLength = 0.30f,
    earDroop = 0.55f,
    legLength = 0.26f,
    quadruped = stance,
    tailLength = 0.30f,
    bodyWidth = 0.98f,
    shagginess = 0.45f,
    // Left at zero on purpose: a hue-rotated palette would stop the eye and belly colours below
    // from being the ones [Palettes] hands out, and those colours are how a shape is identified.
    hueShift = 0f,
)

private fun npArtQuadrupedSpec(
    stance: Float,
    branch: EvolutionBranch = EvolutionBranch.BALANCED,
) = CreatureSpec(
    species = Species.LEAF,
    stage = LifeStage.ADULT,
    branch = branch,
    mood = Mood.NEUTRAL,
    weightGrams = 30f,
    morphology = npArtMorphology(stance),
)

private fun npArtPlainSpec(
    stage: LifeStage = LifeStage.ADULT,
    stageProgress: Float = 0f,
) = CreatureSpec(
    species = Species.LEAF,
    stage = stage,
    branch = EvolutionBranch.BALANCED,
    mood = Mood.NEUTRAL,
    weightGrams = 30f,
    stageProgress = stageProgress,
)

/** A spread wide enough that a shape anchored to the wrong thing shows up somewhere in it. */
private fun npArtCatalogue(): List<CreatureSpec> {
    val hats = listOf(null, "hat_cap", "hat_crown", "hat_bow", "hat_goggles", "hat_leafhat")
    val out = mutableListOf<CreatureSpec>()
    NP_STAGES.forEachIndexed { i, stage ->
        EvolutionBranch.entries.forEachIndexed { j, branch ->
            out += CreatureSpec(
                species = Species.entries[(i + j) % Species.entries.size],
                stage = stage,
                branch = branch,
                mood = Mood.entries[(i + j) % Mood.entries.size],
                weightGrams = 6f + (i + j) * 12f,
                hatId = hats[(i + j) % hats.size],
                morphology = npArtMorphology((i + j) / 8f),
                stageProgress = (i + j) % 3 / 2f,
            )
        }
    }
    // The egg is a different drawing altogether, and the only one with its own frame value.
    out += CreatureSpec(
        species = Species.AQUA,
        stage = LifeStage.EGG,
        branch = EvolutionBranch.BALANCED,
        mood = Mood.NEUTRAL,
    )
    return out
}

/**
 * The corners of the animation: everything that moves a part of the creature, all the way over
 * at once. No frame like this is ever drawn in the game, which is the point — anything anchored
 * to the wrong thing has nowhere to hide.
 */
private val NP_EXTREMES = listOf(
    CreatureFrame(),
    CreatureFrame(
        bobY = -0.16f, squash = 1.18f, armSwing = 1f, armsUp = 1f, gaze = 1f, gazeY = 1f,
        mouthOpen = 1f, blush = 1f, sweat = 1f, sweatPhase = 0.5f, shiver = 1f, tailSwing = 1f,
        hatTilt = 12f, foodFocus = 1f, crack = 0.5f,
    ),
    CreatureFrame(
        bobY = 0.08f, squash = 0.86f, armSwing = -1f, gaze = -1f, gazeY = -1f, eyeOpen = 0f,
        lean = -8f, tailSwing = -1f, hatTilt = -12f,
    ),
)

private fun npArtSweep(): List<Pair<Float, List<NpArtBlob>>> =
    (0..20).map { i ->
        val stance = i / 20f
        stance to npArtBlobs(npArtQuadrupedSpec(stance), NP_STILL)
    }

// -------------------------------------------------------------------- reading the record

/**
 * One blob — a head or a barrel — recovered from the four belly rings [drawBelly] paints inside
 * it. The rings are the only non-path shape that traces a blob's own ellipse, which makes them
 * the only measuring stick available on a JVM where a path remembers nothing.
 */
private class NpArtBlob(val cx: Float, val cy: Float, val halfW: Float, val halfH: Float) {
    val left get() = cx - halfW
    val right get() = cx + halfW
}

// The outermost belly ring is drawn at these fractions of the blob it sits in; see [drawBelly].
private const val NP_RING_W = 1.04f
private const val NP_RING_H = 0.95f
private const val NP_RING_TOP = 0.05f
// The ratio between the first ring and the fourth. A run of four ovals that shares a centre and
// shrinks in this proportion is a belly and nothing else on the creature is.
private const val NP_RING_RATIO = NP_RING_W / 0.94f

private fun npArtBlobs(spec: CreatureSpec, frame: CreatureFrame): List<NpArtBlob> {
    // The ground shadow is the first thing drawn and is a pair, not a belly; skipping it here
    // keeps the run finder from having to tell a shadow from a body.
    val ovals = npArtRender(spec, frame).marks.filter { it.kind == NP_OVAL && it.strokeWidth.isNaN() }.drop(2)
    val out = mutableListOf<NpArtBlob>()
    var i = 0
    while (i + 3 < ovals.size) {
        val run = ovals.subList(i, i + 4)
        val sameCentre = run.all { abs(it.cx - run[0].cx) < 0.01f }
        val shrinking = (0..2).all { run[it].w > run[it + 1].w && run[it].h > run[it + 1].h }
        val ratio = run[0].w / run[3].w
        if (sameCentre && shrinking && abs(ratio - NP_RING_RATIO) < 0.01f) {
            val h = run[0].h / NP_RING_H
            out += NpArtBlob(
                cx = run[0].cx,
                cy = (run[0].cy - run[0].h / 2f) + h * NP_RING_TOP,
                halfW = run[0].w / NP_RING_W,
                halfH = h,
            )
            i += 4
        } else {
            i++
        }
    }
    return out
}

/** The two whites of the eyes, found by the colour only they are painted in. */
private fun npArtEyes(spec: CreatureSpec): List<NpArtMark> {
    val palette = Palettes.creature(spec.species, spec.branch)
    val sclera = lerp(Color.White, palette.belly, 0.30f)
    val eyes = npArtRender(spec, NP_STILL).marks
        .filter { it.kind == NP_OVAL && it.strokeWidth.isNaN() && it.color == sclera }
    assertEquals("expected two eyes, found ${eyes.size}", 2, eyes.size)
    return eyes
}

/** One iris, which is the only thing painted in the palette's eye colour. */
private fun npArtIris(frame: CreatureFrame): NpArtMark {
    val spec = npArtPlainSpec()
    val palette = Palettes.creature(spec.species, spec.branch)
    val irises = npArtRender(spec, frame).marks
        .filter { it.kind == NP_OVAL && it.strokeWidth.isNaN() && it.color == palette.eye }
    assertEquals("expected two irises, found ${irises.size}", 2, irises.size)
    return irises.first()
}

/**
 * A number proportional to the body radius, read back off the ground shadow — which is drawn at
 * a fixed multiple of it, before anything else, and so is the one measurement always available.
 *
 * Only proportional, and only on a still, ungenomed creature: the pool also tightens as the
 * creature leaves the ground and spreads as a barrel comes out from behind the head.
 */
private fun npArtShadowRadius(rec: NpArtRecorder): Float =
    rec.marks.first { it.kind == NP_OVAL }.w / 2f / 1.12f

private fun npArtAssertMonotone(what: String, values: List<Float>) {
    val rising = values.last() > values.first()
    assertTrue("$what did not move across the stage at all: $values", values.last() != values.first())
    values.zipWithNext().forEach { (a, b) ->
        assertTrue(
            "$what doubled back inside the stage: $values",
            if (rising) b >= a else b <= a,
        )
    }
}

// -------------------------------------------------------------------- the recorder

private const val NP_OVAL = "oval"
private const val NP_RECT = "rect"
private const val NP_LINE = "line"
private const val NP_ARC = "arc"
private const val NP_PATH = "path"

/**
 * One draw call, reduced to the bounding box the eye would see. Paths keep no geometry — see the
 * note at the top of this file — so they are recorded with a NaN box and skipped by anything
 * that measures.
 */
private data class NpArtMark(
    val kind: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val color: Color,
    val alpha: Float,
    /** NaN for a fill; the stroke width otherwise. */
    val strokeWidth: Float,
    val detail: String = "",
)

/**
 * Runs the real [drawCreature] and hands back everything it drew.
 *
 * On a unit-test JVM `androidx.compose.ui.graphics.Path` is backed by a stubbed
 * `android.graphics.Path`. Whether that stub returns quietly or throws is a build setting
 * (`testOptions.unitTests.isReturnDefaultValues`), so if it throws, these tests stand aside
 * rather than reporting a failure they cannot tell apart from a real one.
 */
private fun npArtRender(spec: CreatureSpec, frame: CreatureFrame): NpArtRecorder {
    val rec = NpArtRecorder()
    try {
        with(rec) { drawCreature(NP_CENTRE, NP_UNIT, spec, frame) }
    } catch (e: RuntimeException) {
        if (e.message?.contains("not mocked") == true) {
            Assume.assumeNoException(
                "android.graphics is not stubbed on this JVM, so a Compose Path cannot be built; " +
                    "set testOptions.unitTests.isReturnDefaultValues = true to run these",
                e,
            )
        }
        throw e
    }
    return rec
}

/** A [DrawScope] that keeps the call instead of painting it. */
private class NpArtRecorder : DrawScope {

    val marks = mutableListOf<NpArtMark>()

    override val density: Float = 1f
    override val fontScale: Float = 1f
    override val layoutDirection: LayoutDirection = LayoutDirection.Ltr

    // A transform is three numbers here, not a matrix: the creature is only ever leaned about a
    // pivot and shifted by whole pixels, and a stack of those composes without a matrix library.
    private val stack = ArrayDeque<FloatArray>()
    private var dx = 0f
    private var dy = 0f
    private var rot = 0f
    private var px = 0f
    private var py = 0f

    private fun place(o: Offset): Offset {
        val x = o.x + dx
        val y = o.y + dy
        if (rot == 0f) return Offset(x, y)
        val r = rot * PI.toFloat() / 180f
        val ox = x - px
        val oy = y - py
        return Offset(px + ox * cos(r) - oy * sin(r), py + ox * sin(r) + oy * cos(r))
    }

    private fun stroke(style: DrawStyle): Float = (style as? Stroke)?.width ?: Float.NaN

    private fun add(
        kind: String,
        centre: Offset,
        w: Float,
        h: Float,
        color: Color,
        alpha: Float,
        style: DrawStyle,
        detail: String = "",
    ) {
        val c = place(centre)
        marks += NpArtMark(kind, c.x, c.y, w, h, color, alpha, stroke(style), detail)
    }

    override val drawContext: DrawContext = object : DrawContext {
        override var size: Size = Size(NP_UNIT, NP_UNIT)
        override var canvas: Canvas = NpArtCanvas()
        override val transform: DrawTransform = NpArtTransform()
        override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
        override var density: Density = NpArtDensity
        override var graphicsLayer: GraphicsLayer? = null
    }

    /**
     * Only save and restore do anything: `withTransform` reaches through the draw context for
     * them, and everything else on a canvas would mean this scope had been handed to code that
     * paints rather than to [drawCreature].
     */
    private inner class NpArtCanvas : Canvas {
        override fun save() {
            stack.addLast(floatArrayOf(dx, dy, rot, px, py))
        }

        override fun restore() {
            val s = stack.removeLast()
            dx = s[0]; dy = s[1]; rot = s[2]; px = s[3]; py = s[4]
        }

        override fun saveLayer(bounds: Rect, paint: Paint) = npArtUnsupported()
        override fun translate(dx: Float, dy: Float) = npArtUnsupported()
        override fun scale(sx: Float, sy: Float) = npArtUnsupported()
        override fun rotate(degrees: Float) = npArtUnsupported()
        override fun skew(sx: Float, sy: Float) = npArtUnsupported()
        override fun concat(matrix: Matrix) = npArtUnsupported()
        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) =
            npArtUnsupported()
        override fun clipPath(path: Path, clipOp: ClipOp) = npArtUnsupported()
        override fun drawLine(p1: Offset, p2: Offset, paint: Paint) = npArtUnsupported()
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
            npArtUnsupported()
        override fun drawRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusX: Float,
            radiusY: Float,
            paint: Paint,
        ) = npArtUnsupported()
        override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
            npArtUnsupported()
        override fun drawCircle(center: Offset, radius: Float, paint: Paint) = npArtUnsupported()
        override fun drawArc(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            startAngle: Float,
            sweepAngle: Float,
            useCenter: Boolean,
            paint: Paint,
        ) = npArtUnsupported()
        override fun drawPath(path: Path, paint: Paint) = npArtUnsupported()
        override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) = npArtUnsupported()
        override fun drawImageRect(
            image: ImageBitmap,
            srcOffset: IntOffset,
            srcSize: IntSize,
            dstOffset: IntOffset,
            dstSize: IntSize,
            paint: Paint,
        ) = npArtUnsupported()
        override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) = npArtUnsupported()
        override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) = npArtUnsupported()
        override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) = npArtUnsupported()
        override fun enableZ() = npArtUnsupported()
        override fun disableZ() = npArtUnsupported()
    }

    private inner class NpArtTransform : DrawTransform {
        override val size: Size get() = Size(NP_UNIT, NP_UNIT)

        override fun translate(left: Float, top: Float) {
            dx += left
            dy += top
        }

        override fun rotate(degrees: Float, pivot: Offset) {
            rot += degrees
            px = pivot.x + dx
            py = pivot.y + dy
        }

        override fun inset(left: Float, top: Float, right: Float, bottom: Float) = npArtUnsupported()
        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) =
            npArtUnsupported()
        override fun clipPath(path: Path, clipOp: ClipOp) = npArtUnsupported()
        override fun scale(scaleX: Float, scaleY: Float, pivot: Offset) = npArtUnsupported()
        override fun transform(matrix: Matrix) = npArtUnsupported()
    }

    // ------------------------------------------------------------ the draw calls themselves

    override fun drawCircle(
        color: Color,
        radius: Float,
        center: Offset,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = add(NP_OVAL, center, radius * 2f, radius * 2f, color, alpha, style)

    override fun drawOval(
        color: Color,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = add(
        NP_OVAL,
        Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f),
        size.width,
        size.height,
        color,
        alpha,
        style,
    )

    override fun drawRect(
        color: Color,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = add(
        NP_RECT,
        Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f),
        size.width,
        size.height,
        color,
        alpha,
        style,
    )

    override fun drawLine(
        color: Color,
        start: Offset,
        end: Offset,
        strokeWidth: Float,
        cap: StrokeCap,
        pathEffect: PathEffect?,
        alpha: Float,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) {
        val a = place(start)
        val b = place(end)
        marks += NpArtMark(
            kind = NP_LINE,
            cx = (a.x + b.x) / 2f,
            cy = (a.y + b.y) / 2f,
            w = abs(b.x - a.x) + strokeWidth,
            h = abs(b.y - a.y) + strokeWidth,
            color = color,
            alpha = alpha,
            strokeWidth = strokeWidth,
            detail = "$cap",
        )
    }

    override fun drawArc(
        color: Color,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = add(
        NP_ARC,
        Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f),
        size.width,
        size.height,
        color,
        alpha,
        style,
        detail = "$startAngle/$sweepAngle/$useCenter",
    )

    override fun drawPath(
        path: Path,
        color: Color,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) {
        marks += NpArtMark(
            NP_PATH, Float.NaN, Float.NaN, Float.NaN, Float.NaN, color, alpha, stroke(style),
        )
    }

    // Nothing below is reached by the creature art. They are here because the interface has
    // them, and they fail loudly so that a caller who needs one finds out rather than reading a
    // silent gap in the record.

    override fun drawLine(
        brush: Brush,
        start: Offset,
        end: Offset,
        strokeWidth: Float,
        cap: StrokeCap,
        pathEffect: PathEffect?,
        alpha: Float,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawRect(
        brush: Brush,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawImage(
        image: ImageBitmap,
        topLeft: Offset,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    // The overload Compose keeps only for binary compatibility. It is hidden from Kotlin
    // callers, but an implementer still has to carry it.
    @Deprecated("Kept by Compose for binary compatibility", level = DeprecationLevel.HIDDEN)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun drawImage(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawRoundRect(
        brush: Brush,
        topLeft: Offset,
        size: Size,
        cornerRadius: CornerRadius,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawRoundRect(
        color: Color,
        topLeft: Offset,
        size: Size,
        cornerRadius: CornerRadius,
        style: DrawStyle,
        alpha: Float,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawCircle(
        brush: Brush,
        radius: Float,
        center: Offset,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawOval(
        brush: Brush,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawArc(
        brush: Brush,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        topLeft: Offset,
        size: Size,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawPath(
        path: Path,
        brush: Brush,
        alpha: Float,
        style: DrawStyle,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawPoints(
        points: List<Offset>,
        pointMode: PointMode,
        color: Color,
        strokeWidth: Float,
        cap: StrokeCap,
        pathEffect: PathEffect?,
        alpha: Float,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()

    override fun drawPoints(
        points: List<Offset>,
        pointMode: PointMode,
        brush: Brush,
        strokeWidth: Float,
        cap: StrokeCap,
        pathEffect: PathEffect?,
        alpha: Float,
        colorFilter: ColorFilter?,
        blendMode: BlendMode,
    ) = npArtUnsupported()
}

private object NpArtDensity : Density {
    override val density: Float = 1f
    override val fontScale: Float = 1f
}

private fun npArtUnsupported(): Nothing =
    throw UnsupportedOperationException("the creature art is not expected to reach this call")
