package com.neopal.pet.domain

/**
 * Everything a home-screen widget is allowed to say about the pet, and when it must look again.
 *
 * Pure Kotlin on purpose. A widget is the one surface nobody can watch while it is wrong: it is
 * drawn once, by a broadcast, onto someone else's process, and then left there for hours. The
 * only way to know it is telling the truth is to be able to ask it, in a test, what it would say
 * about a state — so every decision it makes lives here rather than in the `RemoteViews` code,
 * and the Android half is left with nothing to decide.
 *
 * Three rules run through the whole file.
 *
 * **The widget never answers a question the game can answer.** The urgent need is
 * [CareActions.topNeed] verbatim; whether a tap is worth offering is decided by running the very
 * care action it would perform and keeping it only if the game accepted it. Rules restated here
 * would drift out of step with the game silently, and a widget that disagrees with the app about
 * whether the pet is hungry is worse than no widget.
 *
 * **The picture is the game's own answer for *now*, not the last thing that was written down.**
 * The save is advanced through [Simulation.advance] before anything is read off it, which is the
 * same call the app makes when it comes back to the foreground. That means the widget is wrong
 * in exactly the ways the game is wrong — the twelve-hour catch-up cap included — and never in
 * any other way.
 *
 * **Nothing is invented for the empty cases.** No pet, an egg and a dead pet are three different
 * things and each gets its own face; none of them borrows a number from a pet that is not there.
 */
data class WidgetSnapshot(
    val face: WidgetFace,
    /** The pet's own name, trimmed to something a small widget can show. Never blank. */
    val name: String,
    /** First line: who this is. Never blank. */
    val headline: String,
    /** Second line: how it is. Never blank. */
    val detail: String,
    /** What to draw. Null only for [WidgetFace.EMPTY] — there is no creature to draw. */
    val creature: WidgetCreature?,
    /** The room behind it. Always present, so an empty save still gets a real picture. */
    val scene: WidgetScene,
    /** What the pet wants, straight from [CareActions.topNeed]. Null when it wants nothing. */
    val need: PetNeed?,
    /** The one tap worth offering, or null when there is nothing useful a tap could do. */
    val offer: WidgetOffer?,
    /** Wall clock this snapshot describes. Everything above is true as of this instant. */
    val atMillis: Long,
    /** How long ago the save was last written by the app or the worker. */
    val saveAgeSeconds: Long,
    /**
     * True when the gap since the last tick was longer than [GameConfig.maxOfflineSeconds], so
     * the simulation threw the excess away. The picture is still what the app will show on open;
     * it is simply not what a pet left alone for that long would really look like.
     */
    val cappedCatchUp: Boolean,
) {
    /**
     * Everything the widget actually shows, as one string.
     *
     * Two snapshots with the same key draw the same widget, so this is both what the refresh
     * search is looking for a change in and what lets a host skip a redraw. Continuous stats are
     * deliberately absent: satiety changes every second and the widget does not.
     */
    val key: String
        get() = listOf(
            face.name,
            name,
            creature?.stage?.name ?: "-",
            creature?.mood?.name ?: "-",
            need?.name ?: "-",
            offer?.action?.name ?: "-",
            offer?.itemId ?: "-",
            scene.poops.toString(),
            if (scene.night) "night" else "day",
            if (scene.lightsOff) "dark" else "lit",
            detail,
        ).joinToString("|")

    companion object {
        /** Never ask to be woken more often than this, however fast the pet is falling apart. */
        const val MIN_LOOK_SECONDS = 600L

        /** Never go longer than this without looking again, however settled the pet is. */
        const val MAX_LOOK_SECONDS = 4L * 3600L

        /** Longest name a small widget can show without eating the line it sits on. */
        const val MAX_NAME_LENGTH = 14

        /**
         * The one snapshot the widget draws.
         *
         * [saved] is the save exactly as it was read: null when there has never been a pet.
         * Nothing here writes anything back — the projection is for looking at only, because the
         * app keeps the pet it is running in memory and would overwrite anything the widget put
         * under it.
         */
        fun of(
            saved: PetState?,
            config: GameConfig = GameConfig.Default,
            nowMillis: Long,
        ): WidgetSnapshot {
            if (saved == null) return empty(nowMillis)
            return build(saved, config, nowMillis)
        }

        private fun build(
            saved: PetState,
            config: GameConfig,
            nowMillis: Long,
        ): WidgetSnapshot {
            val now = project(saved, config, nowMillis)
            val saveAge = ((nowMillis - saved.lastTickMillis) / 1000L).coerceAtLeast(0L)
            val capped = saved.lastTickMillis > 0L && saveAge > config.maxOfflineSeconds

            val need = CareActions.topNeed(now)
            val offer = offerFor(now, need)
            val face = when {
                now.isDead -> WidgetFace.GONE
                now.isEgg -> WidgetFace.EGG
                else -> WidgetFace.ALIVE
            }
            val name = displayName(now.name)
            return WidgetSnapshot(
                face = face,
                name = name,
                headline = headlineFor(face, name, now),
                detail = detailFor(face, now, need, offer, config),
                creature = creatureOf(now, config),
                scene = sceneOf(now, config),
                // An egg has no needs to report and a dead pet's needs stopped mattering. Both
                // are already refused an offer; this is the same rule for the line of text.
                need = if (face == WidgetFace.ALIVE) need else null,
                offer = offer,
                atMillis = nowMillis,
                saveAgeSeconds = saveAge,
                cappedCatchUp = capped,
            )
        }

        /** The face for a phone that has never had a pet on it. */
        private fun empty(nowMillis: Long): WidgetSnapshot = WidgetSnapshot(
            face = WidgetFace.EMPTY,
            name = "NeoPal",
            headline = "No pet yet",
            detail = "Tap to hatch an egg",
            creature = null,
            scene = WidgetScene(),
            need = null,
            offer = null,
            atMillis = nowMillis,
            saveAgeSeconds = 0L,
            cappedCatchUp = false,
        )

        /** The same catch-up the app runs on resume, and never written back. */
        private fun project(saved: PetState, config: GameConfig, nowMillis: Long): PetState =
            Simulation.advance(saved, nowMillis, config).state

        private fun displayName(raw: String): String {
            val trimmed = raw.trim()
            // An imported save can carry anything, including nothing. A widget with a blank line
            // where the name goes looks broken rather than nameless.
            if (trimmed.isEmpty()) return "Your pet"
            return if (trimmed.length <= MAX_NAME_LENGTH) trimmed else trimmed.take(MAX_NAME_LENGTH - 1) + "…"
        }

        private fun headlineFor(face: WidgetFace, name: String, state: PetState): String = when (face) {
            WidgetFace.EMPTY -> "No pet yet"
            WidgetFace.EGG -> "$name · Egg"
            WidgetFace.GONE -> name
            WidgetFace.ALIVE -> "$name · ${state.stage.displayName}"
        }

        private fun detailFor(
            face: WidgetFace,
            state: PetState,
            need: PetNeed?,
            offer: WidgetOffer?,
            config: GameConfig,
        ): String = when (face) {
            WidgetFace.EMPTY -> "Tap to hatch an egg"
            // Nothing about a death is a call to action, so this line says what happened and
            // stops. It is also the only line that never changes again.
            WidgetFace.GONE -> state.deathReason?.let { "Gone · ${it.displayName.lowercase()}" } ?: "Gone"
            WidgetFace.EGG ->
                if (Simulation.stageProgress(state, config) >= 0.66f) "Hatching soon" else "Warming up"
            WidgetFace.ALIVE -> when {
                // A sleeping pet is not neglected, and a widget that shouts a need over a
                // sleeping creature is asking to be obeyed rather than read.
                state.isSleeping -> "Asleep"
                need == null -> "Doing fine"
                // The one case worth spelling out: it wants something the cupboard cannot give,
                // and a chip that is simply missing would look like a bug.
                need == PetNeed.HUNGRY && offer == null && bestFoodFor(state) == null ->
                    "Hungry · nothing left to serve"
                need == PetNeed.SICK && offer == null && bestMedicineFor(state) == null ->
                    "Sick · no medicine left"
                else -> need.label
            }
        }

        private fun sceneOf(state: PetState, config: GameConfig): WidgetScene = WidgetScene(
            roomTheme = state.roomTheme,
            night = Simulation.isNight(state, config),
            lightsOff = state.lightsOff,
            petDay = state.ageInPetDays(config),
            poops = state.poops.coerceIn(0, 6),
        )

        private fun creatureOf(state: PetState, config: GameConfig): WidgetCreature {
            val progress = finite(Simulation.stageProgress(state, config), 0f).coerceIn(0f, 1f)
            // A body drawn from a not-a-number is not a wrong body, it is no body: every
            // coordinate derived from it comes out NaN and every shape silently draws nothing.
            // Nothing in the game can produce one today — the serialiser refuses to decode NaN
            // and weight is coerced wherever it is set — but a widget is the one surface where
            // an invisible creature would look like an empty room and nobody would ever be told.
            val weight = finite(state.weightGrams, 12f).coerceIn(6f, 120f)
            val genes = state.genome.toList()
            val genome = if (genes.all { it.isFinite() }) {
                state.genome
            } else {
                Genome.fromList(genes.map { finite(it, 0.5f) })
            }
            return WidgetCreature(
                species = state.species,
                stage = state.stage,
                branch = state.branch,
                mood = state.mood,
                morphology = Morphology.of(genome, state.stage, state.branch, weight),
                weightGrams = weight,
                hatId = state.equippedHat,
                stageProgress = progress,
                // The egg's crack is its stage progress and nothing else, so a widget drawn in
                // the last minute of an egg shows an egg that is about to go.
                hatchProgress = if (state.isEgg) progress else 0f,
                bond = finite(state.stats.bond, 0f).coerceIn(0f, 100f),
                isSick = state.isSick,
                isSleeping = state.isSleeping,
                isDead = state.isDead,
            )
        }

        private fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

        // ------------------------------------------------------------------ the one tap

        /**
         * The tap worth offering for [need], or null.
         *
         * Every branch is the same answer the app's own quick-care button gives — see
         * `quickCareFor` in `HomeScreen.kt`, which resolves the same [CareActions.topNeed] into
         * the same concrete action. It is transcribed rather than shared because that function is
         * private to a Compose file and cannot be reached from a pure one; if the two ever
         * disagree, the app is right and this is the bug, because a widget that suggests one
         * thing and an app that suggests another is worse than either alone.
         *
         * Two places where the widget cannot follow it, both deliberate:
         *  - boredom offers a pat rather than a minigame, because a widget cannot host one, and
         *    the pat is the app's own fallback for a pet that is not up for a game anyway;
         *  - an empty cupboard offers nothing rather than a trip to the shop, because a chip that
         *    only opens the app is the tap the whole widget already is.
         *
         * Whatever it lands on is then run through the real care action and kept only if the game
         * accepted it. That is what stops a meal being offered to a pet that is full or a nap to
         * one that is wide awake, without a single one of those rules being written down twice.
         */
        private fun offerFor(state: PetState, need: PetNeed?): WidgetOffer? {
            // The same three states the app refuses to suggest anything for. A sleeping pet in
            // particular: the widget has just said "Asleep", and a button under that word asking
            // to be pressed is the widget arguing with itself.
            if (state.isDead || state.isEgg || state.isSleeping) return null
            return when (need) {
                null -> null
                PetNeed.SICK -> bestMedicineFor(state)?.let { id ->
                    accepted(CareActions.useMedicine(state, id)) {
                        WidgetOffer(WidgetAction.MEDICATE, id, "Medicine")
                    }
                }
                PetNeed.HUNGRY -> bestFoodFor(state)?.let { id ->
                    accepted(CareActions.feed(state, id)) { WidgetOffer(WidgetAction.FEED, id, "Feed") }
                }
                PetNeed.DIRTY -> when {
                    // Mess on the floor is the loudest half of being dirty, and scooping it is
                    // what the app reaches for first.
                    state.poops > 0 -> accepted(CareActions.cleanRoom(state)) {
                        WidgetOffer(WidgetAction.CLEAN, null, "Clean")
                    }
                    (state.inventory[SOAP_ID] ?: 0) > 0 -> accepted(CareActions.bathe(state)) {
                        WidgetOffer(WidgetAction.BATHE, SOAP_ID, "Scrub")
                    }
                    else -> accepted(CareActions.cleanRoom(state)) {
                        WidgetOffer(WidgetAction.CLEAN, null, "Clean")
                    }
                }
                PetNeed.SLEEPY -> accepted(CareActions.putToSleep(state)) {
                    WidgetOffer(WidgetAction.TUCK_IN, null, "Lights out")
                }
                PetNeed.BORED -> accepted(CareActions.pet(state)) {
                    WidgetOffer(WidgetAction.PET, null, "Pet")
                }
            }
        }

        /** The soap is a hygiene item that the catalogue files under medicine. */
        private const val SOAP_ID = "soap"

        private inline fun accepted(result: ActionResult, offer: () -> WidgetOffer): WidgetOffer? =
            if (result.accepted) offer() else null

        /**
         * Performs the tap [asked] for, or refuses it.
         *
         * The picture can be hours old by the time a finger lands on it, so nothing the widget
         * drew is trusted: only the *verb* survives the trip, and the state, the item and the
         * whole question of whether this is still worth doing are worked out again from a fresh
         * read — the same rule the remote brain follows for a step of its own plan. Null means
         * the offer went stale, which is not a failure; it is the widget declining to do
         * something the game no longer agrees with.
         */
        fun apply(
            saved: PetState,
            config: GameConfig,
            nowMillis: Long,
            asked: WidgetAction,
        ): ActionResult? {
            val projected = project(saved, config, nowMillis)
            val current = build(saved, config, nowMillis).offer
            return applyWith(projected, asked, current)
        }

        private fun applyWith(state: PetState, asked: WidgetAction, current: WidgetOffer?): ActionResult? {
            if (current == null || current.action != asked) return null
            // The item is re-read too: the food the widget drew may have been the last one.
            val itemId = current.itemId
            val result = when (current.action) {
                WidgetAction.FEED -> CareActions.feed(state, itemId ?: return null)
                WidgetAction.MEDICATE -> CareActions.useMedicine(state, itemId ?: return null)
                WidgetAction.CLEAN -> CareActions.cleanRoom(state)
                WidgetAction.BATHE -> CareActions.bathe(state)
                WidgetAction.TUCK_IN -> CareActions.putToSleep(state)
                WidgetAction.PET -> CareActions.pet(state)
                WidgetAction.OPEN -> return null
            }
            return if (result.accepted) result else null
        }

        /**
         * The meal to serve: the biggest thing in the tin.
         *
         * Transcribed from `quickCareFor`, which does the same — `foods.filter { owned }
         * .maxByOrNull { it.satiety }`. It is not the rule this file would have chosen on its
         * own (a pet with room for ten gets a forty-eight-point stew and most of it is wasted),
         * but the widget and the app's own button must not serve two different dinners, and this
         * one is the app's.
         */
        internal fun bestFoodFor(state: PetState): String? = ItemCatalog.foods
            .filter { (state.inventory[it.id] ?: 0) > 0 }
            .maxByOrNull { it.satiety }
            ?.id

        /**
         * The dose to give: the strongest in the cupboard, and never the soap.
         *
         * Also `quickCareFor`'s rule. The `health > 0` filter is the part worth keeping an eye
         * on: [ItemKind.MEDICINE] covers the Bubble Soap, and a bar of soap handed to a sick pet
         * is spent, cures nothing, and can even clear the illness flag through the back door —
         * `useMedicine` treats any dose that leaves health above sixty as a cure.
         */
        internal fun bestMedicineFor(state: PetState): String? = ItemCatalog.ofKind(ItemKind.MEDICINE)
            .filter { it.health > 0f && (state.inventory[it.id] ?: 0) > 0 }
            .maxByOrNull { it.health }
            ?.id

        // ------------------------------------------------------------------ when to look again

        /**
         * How long the picture [of] just drew stays that picture, floored and capped.
         *
         * Every probe runs [Simulation.advance] from the *saved* state to a future instant —
         * exactly the call [of] will make when that instant arrives — so a probe that says the
         * key changes at T is not an estimate: it is the answer the widget will give at T. That
         * costs a handful of simulated hours, which is why it is asked for separately rather
         * than being carried on every snapshot: only the code that schedules the next refresh
         * needs it, and it needs it once.
         *
         * Inverting the drain rates instead would be cheaper and would go quietly wrong the day
         * somebody retunes hunger.
         */
        fun nextLook(saved: PetState?, config: GameConfig = GameConfig.Default, nowMillis: Long): Long {
            if (saved == null) return MAX_LOOK_SECONDS
            val nowKey = keyAt(saved, config, nowMillis)
            // A dead pet is the one state that genuinely never changes on its own. Everything
            // that could change it — a new generation, a new pet — is the app writing the save,
            // and the app tells the widget when it does that.
            if (project(saved, config, nowMillis).isDead) return MAX_LOOK_SECONDS

            var same = 0L
            var changed = -1L
            for (probe in LADDER) {
                if (keyAt(saved, config, nowMillis + probe * 1000L) != nowKey) {
                    changed = probe
                    break
                }
                same = probe
            }
            if (changed < 0L) return MAX_LOOK_SECONDS

            // Three halvings put the answer inside about an eighth of the band it was found in,
            // which is far finer than the floor below cares about at the near end and plenty at
            // the far end. More would only buy precision the alarm cannot honour anyway.
            var low = same
            var high = changed
            repeat(3) {
                val mid = (low + high) / 2
                if (mid <= low) return@repeat
                if (keyAt(saved, config, nowMillis + mid * 1000L) != nowKey) high = mid else low = mid
            }
            return high.coerceIn(MIN_LOOK_SECONDS, MAX_LOOK_SECONDS)
        }

        /** The horizons worth asking about, coarsening as they go out. */
        private val LADDER = longArrayOf(600L, 900L, 1800L, 3600L, 7200L, MAX_LOOK_SECONDS)

        private fun keyAt(saved: PetState, config: GameConfig, atMillis: Long): String =
            build(saved, config, atMillis).key
    }
}

/**
 * Which of the four honest faces the widget is wearing.
 *
 * They are four separate things, not one thing with fields missing. A phone with no save has
 * never had a pet; an egg has not hatched yet and has no needs to report; a dead pet has needs
 * that no longer mean anything. Only [ALIVE] may show a need or offer a tap.
 */
enum class WidgetFace { EMPTY, EGG, ALIVE, GONE }

/**
 * `WidgetNeed` used to live here: a second five-case enum whose only job was to recognise the
 * five English words [CareActions.topNeed] returned and turn them back into cases. It is gone.
 * The widget now takes [PetNeed] straight from the game, which is what the doc comment above
 * this file always said it was doing — "the widget never answers a question the game can answer"
 * — and the round trip through English that stood between the two is no longer there to drift.
 */

/** What one tap on the widget does. [OPEN] is the whole widget; the rest are the chip. */
enum class WidgetAction { OPEN, FEED, MEDICATE, CLEAN, BATHE, TUCK_IN, PET }

/** A tap the widget is prepared to offer, and the item it would spend doing it. */
data class WidgetOffer(
    val action: WidgetAction,
    val itemId: String? = null,
    /** What the chip says. Never blank. */
    val label: String,
)

/**
 * The creature to draw.
 *
 * Carries the *expressed* [Morphology] rather than the genome, so the widget draws the same body
 * the app draws without knowing what a life stage does to a gene. A bred line looks like itself
 * on the home screen for exactly the same reason it does on the pet screen: this is the number
 * the renderer reads, and there is only one of it.
 */
data class WidgetCreature(
    val species: Species,
    val stage: LifeStage,
    val branch: EvolutionBranch,
    val mood: Mood,
    val morphology: Morphology,
    val weightGrams: Float,
    val hatId: String?,
    val stageProgress: Float,
    val hatchProgress: Float,
    /** 0..100. The renderer spends it on the blush, so affection shows on the home screen too. */
    val bond: Float,
    val isSick: Boolean,
    val isSleeping: Boolean,
    val isDead: Boolean,
)

/** The room behind the creature. Present even when there is no creature. */
data class WidgetScene(
    val roomTheme: String = "room_default",
    val night: Boolean = false,
    val lightsOff: Boolean = false,
    val petDay: Int = 0,
    val poops: Int = 0,
)
