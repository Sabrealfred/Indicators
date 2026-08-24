package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the game brings the download up on its own.
 *
 * The failure this guards is the one that ends with an uninstall, and it does not look like a bug
 * at all: a prompt for gigabytes two minutes into an egg, from a game the player has not decided
 * they like yet. Nobody reports that. They just stop having the app.
 *
 * The opposite failure is quieter and also real — a trigger set so far out that nobody is ever
 * offered the thing — so the tests here run in both directions.
 */
class LocalModelOfferTest {

    private val roomyPhone = DeviceMemory(
        totalRamBytes = 11_800_000_000L,
        availableRamBytes = 4_000_000_000L,
        memoryClassMb = 512,
        isLowRamDevice = false,
        freeDiskBytes = 60_000_000_000L,
    )
    private val tinyPhone = DeviceMemory(
        totalRamBytes = 3_700_000_000L,
        availableRamBytes = 1_200_000_000L,
        memoryClassMb = 128,
        isLowRamDevice = false,
        freeDiskBytes = 20_000_000_000L,
    )

    private val fits = LocalModelFit.of(roomyPhone)
    private val doesNotFit = LocalModelFit.of(tinyPhone)

    /** A creature somebody has clearly been looking after. */
    private fun attached(
        stage: LifeStage = LifeStage.TEEN,
        generation: Int = 1,
        peakBond: Float = 0f,
        health: Float = 90f,
        isSick: Boolean = false,
    ) = PetState(
        name = "Pip",
        stage = stage,
        generation = generation,
        peakBond = peakBond,
        isSick = isSick,
        stats = Stats(health = health),
    )

    /** Two minutes in. The state the design document is most worried about. */
    private val freshEgg = PetState(name = "Pip", stage = LifeStage.EGG)

    private fun config(local: LocalMindConfig = LocalMindConfig()) =
        GameConfig.Default.copy(localMind = local)

    private fun decide(
        pet: PetState,
        local: LocalMindConfig = LocalMindConfig(),
        fit: LocalModelFit = fits,
    ) = LocalModelOffer.decide(pet, config(local), fit)

    // ------------------------------------------------------- not on the first run, which is the point

    @Test
    fun `an egg two minutes in is not asked for gigabytes`() {
        val offer = decide(freshEgg)
        assertEquals(OfferVerdict.TOO_EARLY, offer.verdict)
        assertFalse(offer.shouldShow)
        assertNull("and nothing is dressed up to be shown anyway", offer.headline)
    }

    @Test
    fun `a baby is not asked either, however healthy`() {
        // isMindAwake is the floor: below CHILD there is no conversation to improve and nothing
        // yet that the player would mind losing.
        assertEquals(
            OfferVerdict.TOO_EARLY,
            decide(attached(stage = LifeStage.BABY, health = 100f)).verdict,
        )
    }

    @Test
    fun `a child nobody has bonded with is not asked`() {
        // Past the mind-awake floor and still nothing to lose: no bond, first generation, has not
        // grown up. This is the case that separates "old enough" from "invested".
        val justAlive = attached(stage = LifeStage.CHILD, generation = 1, peakBond = 10f)
        assertEquals(OfferVerdict.TOO_EARLY, decide(justAlive).verdict)
    }

    // ------------------------------------------------------------- the three ways to have earned it

    @Test
    fun `a creature that grew up is somebody worth spending gigabytes on`() {
        val offer = decide(attached(stage = LifeStage.TEEN, peakBond = 5f))
        assertEquals(OfferVerdict.OFFER, offer.verdict)
        assertTrue(offer.shouldShow)
    }

    @Test
    fun `a much-loved child is asked even before it grows up`() {
        val loved = attached(stage = LifeStage.CHILD, peakBond = LocalModelOffer.ATTACHED_BOND)
        assertEquals(OfferVerdict.OFFER, decide(loved).verdict)
    }

    @Test
    fun `the bond that counts is the peak, not what is left of it today`() {
        // Bond decays. A player who had a close creature and drifted has still had it, and the
        // closing value would call that person a stranger.
        val faded = attached(stage = LifeStage.CHILD, peakBond = 85f).copy(
            stats = Stats(health = 90f, bond = 4f),
        )
        assertTrue(LocalModelOffer.hasSomethingToLose(faded))
    }

    @Test
    fun `somebody on their second generation is asked, whatever stage the heir is at`() {
        // The strongest signal there is: the worst thing this game does to a player has already
        // happened to them and they started again.
        val heir = attached(stage = LifeStage.CHILD, generation = 2, peakBond = 0f)
        assertEquals(OfferVerdict.OFFER, decide(heir).verdict)
    }

    @Test
    fun `a second-generation egg is still not asked`() {
        // Commitment is not a licence to interrupt somebody who is looking at an egg.
        val newEgg = freshEgg.copy(generation = 3)
        assertEquals(OfferVerdict.TOO_EARLY, decide(newEgg).verdict)
    }

    // -------------------------------------------------------------------------- how often, and when

    @Test
    fun `the offer is not repeated within the same life`() {
        val already = LocalMindConfig(offersMade = 1, lastOfferGeneration = 1)
        assertEquals(OfferVerdict.ASKED_ENOUGH, decide(attached(), already).verdict)
    }

    @Test
    fun `a new generation earns one more ask, and then no more`() {
        val askedOnce = LocalMindConfig(offersMade = 1, lastOfferGeneration = 1)
        val secondLife = attached(generation = 2)
        assertEquals(OfferVerdict.OFFER, decide(secondLife, askedOnce).verdict)

        val askedTwice = LocalMindConfig(offersMade = 2, lastOfferGeneration = 2)
        assertEquals(
            "twice is a reminder, three times is an advertisement",
            OfferVerdict.ASKED_ENOUGH,
            decide(attached(generation = 3), askedTwice).verdict,
        )
    }

    @Test
    fun `showing the offer records itself, so it cannot appear on every frame`() {
        val before = LocalMindConfig()
        val after = before.afterOffering(generation = 4)
        assertEquals(1, after.offersMade)
        assertEquals(4, after.lastOfferGeneration)
        assertEquals(OfferVerdict.ASKED_ENOUGH, decide(attached(generation = 4), after).verdict)
    }

    @Test
    fun `a sick creature is not interrupted with a download screen`() {
        val ill = attached(isSick = true)
        assertEquals(OfferVerdict.BAD_MOMENT, decide(ill).verdict)
        assertEquals(OfferVerdict.BAD_MOMENT, decide(attached(health = 20f)).verdict)
    }

    @Test
    fun `a bad moment is not a permanent no`() {
        // Same save, same config, once the creature is well again. If this were folded into
        // ASKED_ENOUGH or TOO_EARLY the offer would never come back.
        val ill = attached(isSick = true)
        assertEquals(OfferVerdict.BAD_MOMENT, decide(ill).verdict)
        assertEquals(OfferVerdict.OFFER, decide(ill.copy(isSick = false)).verdict)
    }

    // ------------------------------------------------------------------ the phone, and the settings

    @Test
    fun `a phone that cannot hold a model is told that, rather than told to wait`() {
        // "Too early" implies waiting will help. On this phone it will not, and saying so is the
        // difference between an honest no and a string of prompts that never resolve.
        val offer = decide(attached(), fit = doesNotFit)
        assertEquals(OfferVerdict.DEVICE_CANNOT, offer.verdict)
        assertNotNull("and the fit says which kind of no it is", doesNotFit.blocker)
    }

    @Test
    fun `nothing is offered once something is installed`() {
        val installed = LocalMindConfig(installed = LocalModelVariant.TINY)
        assertEquals(OfferVerdict.ALREADY_INSTALLED, decide(attached(), installed).verdict)
        assertTrue(installed.hasModel)
        assertTrue(installed.usable)
    }

    @Test
    fun `switching the feature off stops the asking as well as the running`() {
        val off = LocalMindConfig(enabled = false)
        assertEquals(OfferVerdict.TURNED_OFF, decide(attached(), off).verdict)
        assertFalse(off.copy(installed = LocalModelVariant.TINY).usable)
    }

    @Test
    fun `the offer names the exact size of the exact variant it would fetch`() {
        val offer = decide(attached())
        assertEquals(LocalModelVariant.LARGE, offer.variant)
        assertEquals(LocalModelVariant.LARGE.downloadBytes, offer.downloadBytes)
        assertTrue("the pitch is about the creature, not about inference", offer.note!!.contains("Pip"))
    }

    @Test
    fun `a forced preference is what gets offered, when the phone can hold it`() {
        val prefersSmall = LocalMindConfig(preferred = LocalModelVariant.TINY)
        val offer = decide(attached(), prefersSmall)
        assertEquals(LocalModelVariant.TINY, offer.variant)
        assertEquals(LocalModelVariant.TINY.downloadBytes, offer.downloadBytes)
    }

    @Test
    fun `a preference the phone cannot hold does not become an offer for it`() {
        // The rule from LocalModelFit, checked where it actually reaches a player: asking for the
        // full model on a phone that cannot hold it must not produce a 3.4 GB download.
        val ordinary = LocalModelFit.of(roomyPhone.copy(totalRamBytes = 5_600_000_000L, memoryClassMb = 192))
        val prefersLarge = LocalMindConfig(preferred = LocalModelVariant.LARGE)
        val offer = LocalModelOffer.decide(attached(), config(prefersLarge), ordinary)
        assertEquals(OfferVerdict.OFFER, offer.verdict)
        assertEquals(LocalModelVariant.TINY, offer.variant)
    }

    // -------------------------------------------------------------------------------- Wi-Fi by default

    @Test
    fun `Wi-Fi is the default, and mobile data is a question rather than a refusal`() {
        assertTrue(LocalMindConfig().wifiOnly)
        assertEquals(
            DownloadGate.NEEDS_MOBILE_CONSENT,
            LocalModelOffer.gate(NetworkKind.METERED, wifiOnly = true),
        )
        assertEquals(
            "and saying yes once is enough to start it",
            DownloadGate.GO,
            LocalModelOffer.gate(NetworkKind.METERED, wifiOnly = true, consentedToMobile = true),
        )
    }

    @Test
    fun `an unmetered connection never asks anything`() {
        assertEquals(DownloadGate.GO, LocalModelOffer.gate(NetworkKind.UNMETERED, wifiOnly = true))
        assertEquals(DownloadGate.GO, LocalModelOffer.gate(NetworkKind.UNMETERED, wifiOnly = false))
    }

    @Test
    fun `turning Wi-Fi-only off means mobile data without a prompt`() {
        assertEquals(DownloadGate.GO, LocalModelOffer.gate(NetworkKind.METERED, wifiOnly = false))
    }

    @Test
    fun `no connection is not a consent question`() {
        // A dialog offering to spend mobile data the phone does not have is a dialog that cannot
        // be answered correctly.
        for (wifiOnly in listOf(true, false)) {
            for (consent in listOf(true, false)) {
                assertEquals(
                    DownloadGate.NO_NETWORK,
                    LocalModelOffer.gate(NetworkKind.NONE, wifiOnly, consent),
                )
            }
        }
    }

    @Test
    fun `the offer says whether it will wait for Wi-Fi`() {
        assertTrue(decide(attached()).waitsForWifi)
        assertFalse(decide(attached(), LocalMindConfig(wifiOnly = false)).waitsForWifi)
    }

    // --------------------------------------------------------------------------- backward compatibility

    @Test
    fun `a save written before any of this loads into the game the player left`() {
        // Every field defaulted, so an old GameConfig deserialises without a model, without an
        // offer having been made, and assuming Wi-Fi.
        val fresh = GameConfig.Default.localMind
        assertNull(fresh.installed)
        assertNull(fresh.preferred)
        assertEquals(0, fresh.offersMade)
        assertEquals(0, fresh.lastOfferGeneration)
        assertTrue(fresh.wifiOnly)
        assertFalse("and nothing is running until something is downloaded", fresh.usable)
    }
}
