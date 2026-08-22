package com.neopal.pet.domain

import kotlin.random.Random

/**
 * The pet's family and social circle, laid out for a screen to draw.
 *
 * Assembled on demand rather than stored, because every part of it is already a fact about
 * [PetState.pals]. A stored tree is a second copy of the truth that has to be kept in step with
 * the first, and the one thing a family tree must never do is disagree with the pet standing in
 * front of you.
 */
data class FamilyTree(
    val name: String,
    /** The pet's own parents, by name. Empty for a founder — which is not the same as unknown. */
    val parentNames: List<String>,
    val mates: List<Pal>,
    val offspring: List<Pal>,
    val friends: List<Pal>,
    /** Eggs still in the nest, so the tree can show the branch that has not arrived yet. */
    val expecting: List<NestEgg>,
)

/**
 * What a pairing would most likely produce, shown before the player commits to it.
 *
 * The preview is the *average* child — every gene at the midpoint, no mutation roll — and that
 * is deliberate. Rolling a real child for the preview would either lie (the egg re-rolls and
 * comes out different) or force the roll to be fixed early, which hands the player a re-roll by
 * closing the screen. An honest average with the odds explained beats a dishonest specimen.
 */
data class ChildPreview(
    val genome: Genome,
    val morphology: Morphology,
    /** How far apart the two parents are, 0..1. Below [Genome.MIN_USEFUL_DISTANCE] is barred. */
    val parentDistance: Float,
    /** Change in [Genome.houndliness] from the player's own pet, positive meaning more hound. */
    val houndlinessShift: Float,
    /** One sentence for the breeding screen. */
    val summary: String,
)

/**
 * Other creatures: who visits, who stays, who becomes family.
 *
 * The colony exists because a tamagotchi's whole world is otherwise two entities — the pet and
 * the hand that feeds it — and that world has nowhere to go once the needs are met. Visitors
 * give the pet something to want that is not a stat, and breeding gives a lineage a direction.
 *
 * Everything here is kept deliberately shallow. Companions are [Pal] records, not second
 * simulations: six fully-simulated pets would be six pets to neglect, and only one relationship
 * in this game is allowed to fail. The cost of that choice is that a visitor cannot surprise
 * you on its own; the benefit is that the save stays small and a long absence stays cheap.
 *
 * Every rate here is written per hour and converted against the elapsed step, never as a
 * per-tick constant. [Simulation.advance] slices a catch-up into steps of one to sixty seconds
 * depending on how long the player was away, so a per-tick chance would quietly make visitors
 * sixty times rarer for anyone who came back after a day than for anyone watching live.
 */
object Colony {

    // ---- how busy the room gets ---------------------------------------------------------

    /**
     * Chance per hour that somebody calls round, before temperament. Low on purpose: a visitor
     * that turns up every ten minutes is scenery, and the point of a visitor is that meeting one
     * is an event worth looking up for.
     */
    private const val ARRIVAL_PER_HOUR = 0.30f

    /** How often the arrival roll is made, in pet seconds. Must not be smaller than a step. */
    private const val ARRIVAL_CHECK_INTERVAL = 60L

    /**
     * How many companions a save remembers at all.
     *
     * The save is one JSON blob rewritten on every tick, so this cannot be unbounded — but the
     * real reason is smaller than that. A friends list of forty names is a list nobody reads,
     * and the pet is supposed to have friends, not followers.
     */
    const val MAX_REMEMBERED_PALS = 12

    /** How many visitors can be in the room at once. Family does not count against it. */
    const val MAX_PRESENT_VISITORS = 3

    /** How many eggs the nest holds. Two is a clutch; more is a queue. */
    const val MAX_NEST_EGGS = 2

    /** How long a stranger hangs about before letting itself out, in pet seconds. */
    private const val VISIT_SECONDS = 900L

    /** A friend stays for longer, because that is most of what being a friend is. */
    private const val FRIEND_VISIT_SECONDS = 2_700L

    // ---- affinity -----------------------------------------------------------------------

    /**
     * Affinity gained per second of company, before temperament and rapport.
     *
     * Tuned so a first visit ends short of friendship and a second one crosses it. Friendship
     * that lands inside one visit costs the player nothing and therefore means nothing; two
     * visits means the pet had to still be there when they came back.
     */
    private const val AFFINITY_PER_SECOND = 0.030f

    /**
     * Affinity gained per second simply by being in the room together, before temperament and
     * rapport. Three tenths of [AFFINITY_PER_SECOND]: about six visits to a friendship instead
     * of two.
     *
     * This is the difference between company and a visit. Going over to somebody is an act the
     * creature performs — it needs the skill, and it needs the player's permission to act at
     * all — but two creatures in one room grow used to each other whether or not either of them
     * decided to, and that is not a thing autonomy has any business gating. Without it the only
     * road to a friendship ran through [Brain]'s SOCIALISE activity, which needs FULL autonomy,
     * which is not the default: every visitor a default player ever saw arrived at nothing and
     * left at nothing, and the entire colony downstream of the number — courting, the nest, the
     * offspring, the lineage — was unreachable in the shipped configuration.
     */
    private const val COMPANY_PER_SECOND = AFFINITY_PER_SECOND * 0.30f

    /** Affinity lost per hour apart. A fortnight of silence loses an acquaintance entirely. */
    private const val DECAY_PER_HOUR = 2.5f

    /**
     * Floors under the decay, by how the companion stands with the pet.
     *
     * Without these, a night's sleep is enough to walk back a friendship the player spent two
     * visits building, and the pet wakes up alone through no decision anybody made. Decay is
     * meant to make an ignored acquaintance fade, not to charge rent on a friend.
     */
    private const val FRIEND_FLOOR = Pal.FRIEND_AT + 4f
    private const val MATE_FLOOR = Pal.COURT_AT
    private const val FAMILY_FLOOR = 70f

    /** Offspring start here: they are family before they have done anything to earn it. */
    private const val OFFSPRING_AFFINITY = 88f

    // ---- growing up ----------------------------------------------------------------------

    /**
     * How long a child stays at home, in pet seconds: exactly as long as the player's own pet
     * takes to get from newly hatched to grown, because it is the same journey.
     *
     * Read from [Simulation] rather than written down again, so the two can never drift apart —
     * a hard-coded number here would quietly stop meaning "grown up" the first time anybody
     * retuned a life stage.
     */
    private fun leaveHomeSeconds(config: GameConfig): Long =
        Simulation.stageDuration(LifeStage.BABY, config) +
            Simulation.stageDuration(LifeStage.CHILD, config) +
            Simulation.stageDuration(LifeStage.TEEN, config)

    /**
     * How long a grown child can go without calling in before the roster lets it go.
     *
     * A whole adulthood — about half a life at [GameConfig.lifeSpeed] 1.0. This is the one thing
     * that keeps a full colony from being permanent, and it is deliberately the *only* way a
     * family member is ever forgotten: still at home, or in touch, and nothing can dislodge them.
     * What is lost is a row on a roster, not the fact of them — the diary keeps every child that
     * ever hatched, and the run record keeps the line.
     */
    private fun outOfTouchSeconds(config: GameConfig): Long =
        Simulation.stageDuration(LifeStage.ADULT, config)

    // ---- eggs ---------------------------------------------------------------------------

    /** Incubation at [GameConfig.lifeSpeed] 1.0. Long enough to be looked forward to. */
    private const val INCUBATION_BASE_SECONDS = 1_800L

    /** How many of the parent's skills a child can be taught. */
    private const val TAUGHT_SKILL_LIMIT = 3

    // -------------------------------------------------------------------------------------

    /**
     * Visitors arriving and leaving, company, affinity decay, eggs ripening.
     *
     * Runs whatever [PetState.autonomy] says, because none of it is a decision the pet makes.
     * Somebody knocking at the door is not an act of will, an egg does not wait for permission
     * to hatch, and two creatures sharing a room grow used to each other without either of them
     * choosing to — gating any of it on autonomy would mean a player on Manual never sees the
     * feature exist at all.
     *
     * Expects [PetState.ageSeconds] to have already advanced by [dt] this step, which is how
     * [Simulation] orders its own handlers: the interval checks below read the clock, and a
     * clock that has not moved yet fires them one step late for ever.
     */
    fun tick(
        state: PetState,
        config: GameConfig,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (dt <= 0L) return state
        var s = state
        s = departures(s, config, events)
        s = decayAffinity(s, dt)
        // Before arrivals, so nobody is paid for a step they spent somewhere else.
        s = company(s, dt, events)
        s = arrivals(s, config, dt, random, events)
        s = hatchEggs(s, random, events)
        return capRemembered(s, config, events)
    }

    /**
     * One step of a social activity aimed at [palId]. Called by the brain and by the player.
     *
     * Takes [dt] rather than granting a flat amount per call, so that the brain holding an
     * activity for four minutes and the player tapping "visit" once are paid at the same rate.
     * A per-call grant would make tapping the fastest way to a friendship, and the pet's own
     * social life would be strictly worse than button-mashing.
     *
     * Deliberately does not touch happiness, bond, or [PetState.socialActions]. Those belong to
     * whatever started the activity, which is the only thing that knows when a visit has actually
     * finished; crediting them here as well would pay a socialising pet twice for one visit.
     */
    fun interact(
        state: PetState,
        palId: String,
        kind: ActivityKind,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (dt <= 0L || state.isDead || state.isSleeping || !state.stage.isHatched) return state
        // A pet this miserable will not be talked round; see PetState.isSulking.
        if (state.isSulking) return state
        val index = state.pals.indexOfFirst { it.id == palId }
        if (index < 0) return state
        val pal = state.pals[index]
        if (!pal.present) return state

        val gain = AFFINITY_PER_SECOND * dt *
            warmth(state) *
            rapport(state.personality, pal.personality) *
            weightOf(kind) *
            (0.85f + random.nextFloat() * 0.30f)

        val raised = pal.copy(affinity = (pal.affinity + gain).coerceIn(0f, 100f))
        val updated = promoted(raised, events)
        val pals = state.pals.toMutableList()
        pals[index] = updated
        return state.copy(pals = pals)
    }

    /**
     * Lays an egg from the pet and [palId] if the pairing is allowed; otherwise returns state
     * unchanged.
     *
     * The child's genome is rolled here and stored on the egg rather than at hatching, so a
     * player who liked the roll cannot lose it by closing the app — see [NestEgg].
     *
     * Each egg is rolled from its own stream, not from [random] directly. The only seed a caller
     * has to hand is [PetState.rngSeed], and that moves only when [Simulation.advance] runs a
     * step — which it declines to do until the wall clock has crossed a whole second. Two eggs
     * laid in one sitting were therefore rolled from the same seed over the same two parents and
     * came out gene for gene identical: a clutch of twins nobody bred for, in the one part of
     * the game whose entire point is that a child is a roll of the dice.
     */
    fun pair(
        state: PetState,
        palId: String,
        config: GameConfig,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (pairingBlocker(state, palId) != null) return state
        val index = state.pals.indexOfFirst { it.id == palId }
        if (index < 0) return state
        val pal = state.pals[index]

        // Every term is a fact about the state or the caller's own seed, so a test that fixes
        // both still pins the child exactly — the fix is per-egg entropy, not unpredictability.
        // What the twins do not share is how many eggs were already in the nest.
        val eggSeed = random.nextLong() * 0x9E3779B97F4A7C15uL.toLong() +
            (state.nest.size + 1L) * 0x2545F4914F6CDD1DuL.toLong() +
            palId.hashCode().toLong()
        val eggRandom = Random(eggSeed)

        val egg = NestEgg(
            id = "egg_${state.generation}_${state.ageSeconds}_${state.nest.size}",
            genome = Genome.breed(state.genome, pal.genome, eggRandom),
            // Either parent's family can carry, so a line can change species without changing
            // its genes. Species drives the palette; the genome drives the shape.
            species = if (eggRandom.nextBoolean()) state.species else pal.species,
            laidAtSeconds = state.ageSeconds,
            hatchesAtSeconds = state.ageSeconds + incubationSeconds(config),
            otherParentId = pal.id,
            otherParentName = pal.name,
            parentName = state.name,
        )

        // A pairing is a promotion whether or not affinity happened to cross the line during an
        // interaction, and [promoted] is the only thing allowed to announce one — so a mate is
        // always announced exactly once, however they got here.
        val pals = state.pals.toMutableList()
        pals[index] = promoted(pal, events)

        events += GameEvent.EggLaid(egg)
        // The seed moves on the way out as well. The salt above is what makes a clutch different
        // from itself; this is what stops the *next* thing to read PetState.rngSeed inside the
        // same second from replaying a stream that has already been spent. Anything that
        // consumes randomness and hands back a state should leave a fresh seed behind, or the
        // second-resolution clock decides how random the game is.
        return state.copy(pals = pals, nest = state.nest + egg, rngSeed = eggRandom.nextLong())
    }

    /**
     * Why a pairing is not allowed right now, phrased for the player, or null when it is allowed.
     *
     * Every refusal names its own reason. A breeding screen that greys out the button without
     * saying why teaches the player nothing, and the rules here — a skill, two ages, a trust
     * level, a full nest, a shared bloodline — are not guessable from the outside.
     */
    fun pairingBlocker(state: PetState, palId: String): String? {
        val pal = state.pals.firstOrNull { it.id == palId }
            ?: return "You have not met anyone by that name."
        if (state.isDead) return "${state.name} is no longer with us."
        if (!pal.present) return "${pal.name} is not here at the moment."
        if (Skill.COURT !in state.skills) return "${state.name} has not learned how to court yet."
        if (state.stage.order < LifeStage.TEEN.order) return "${state.name} is far too young to start a family."
        if (pal.stage.order < LifeStage.TEEN.order) return "${pal.name} is far too young to start a family."
        // Family is barred by name, not left to the genome test below. A child of a distant
        // pairing can sit far enough from its parent to pass that test, and "the numbers happen
        // to allow it" is not a rule anybody wants applied here. Reachable since children grew
        // up: an offspring is family at 88 fondness on the day it hatches, so trust and age are
        // never what stops it.
        if (pal.relation == Relation.OFFSPRING || pal.relation == Relation.PARENT) {
            return "${pal.name} is ${state.name}'s own family."
        }
        if (pal.affinity < Pal.COURT_AT) return "${pal.name} is not close enough to ${state.name} yet."
        if (state.nest.size >= MAX_NEST_EGGS) return "The nest is already full."
        if (Genome.distance(state.genome, pal.genome) < Genome.MIN_USEFUL_DISTANCE) {
            return "${pal.name} is too closely related — the pair would only repeat themselves."
        }
        return null
    }

    // ---- read-only helpers for the UI ---------------------------------------------------

    /** How long an egg sits in the nest at this pace. Exposed so the nest screen can say so. */
    fun incubationSeconds(config: GameConfig): Long =
        (INCUBATION_BASE_SECONDS / config.lifeSpeed.coerceIn(0.1f, 20f)).toLong().coerceAtLeast(60L)

    /** Seconds until the next egg is due, or null when the nest is empty. */
    fun nextHatchInSeconds(state: PetState): Long? =
        state.nest.minOfOrNull { (it.hatchesAtSeconds - state.ageSeconds).coerceAtLeast(0L) }

    /** Everyone the pet is related to or fond of, sorted for display. Pure; nothing is stored. */
    fun familyTree(state: PetState): FamilyTree = FamilyTree(
        name = state.name,
        parentNames = state.parentNames,
        mates = state.pals.filter { it.relation == Relation.MATE },
        offspring = state.pals.filter { it.relation == Relation.OFFSPRING },
        friends = state.pals.filter { it.relation == Relation.FRIEND }.sortedByDescending { it.affinity },
        expecting = state.nest,
    )

    /**
     * The likely child of the pet and [palId], or null when there is no such companion.
     *
     * Available even when [pairingBlocker] refuses, on purpose: "here is what you would get, and
     * here is why you cannot have it yet" is a goal, whereas a blank panel is a dead end.
     */
    fun previewChild(state: PetState, palId: String): ChildPreview? {
        val pal = state.pals.firstOrNull { it.id == palId } ?: return null
        val mine = state.genome.toList()
        val theirs = pal.genome.toList()
        val average = Genome.fromList(List(Genome.GENE_COUNT) { i -> (mine[i] + theirs[i]) / 2f })
        val shift = average.houndliness - state.genome.houndliness
        return ChildPreview(
            genome = average,
            // Shown as an adult: a preview drawn at the child's own stage is a featureless
            // newborn every time, which tells the player nothing about the pairing.
            morphology = Morphology.of(average, LifeStage.ADULT, state.branch),
            parentDistance = Genome.distance(state.genome, pal.genome),
            houndlinessShift = shift,
            summary = summarise(state, average, shift),
        )
    }

    // ---- internals ----------------------------------------------------------------------

    /**
     * True exactly once per [window] of pet time, whatever the step size. The same guard
     * [Simulation] uses: a plain modulo test fires twice at small steps and skips whole windows
     * at large ones, so the real rate would depend on how the elapsed time happened to be
     * sliced rather than on the number written down.
     */
    private fun crossedWindow(ageSeconds: Long, dt: Long, window: Long): Boolean =
        window > 0 && (ageSeconds / window) != ((ageSeconds - dt) / window)

    /** How readily this pet warms to company, 0.6..1.4. */
    private fun warmth(state: PetState): Float = 0.6f + state.genome.sociability * 0.8f

    /**
     * How well two temperaments get on, 0.7..1.3.
     *
     * Not symmetric-by-accident but symmetric on purpose — the pair either clicks or does not,
     * and a table where A likes B more than B likes A would need a second affinity number that
     * nothing in the game ever shows.
     */
    private fun rapport(mine: Personality, theirs: Personality): Float {
        if (mine == theirs) {
            // Two of the same mostly get on, except for the two that compete.
            return if (mine == Personality.GREEDY || mine == Personality.PLAYFUL) 0.95f else 1.25f
        }
        val pair = setOf(mine, theirs)
        return when {
            pair == setOf(Personality.BRAVE, Personality.SHY) -> 1.30f
            pair == setOf(Personality.CALM, Personality.SHY) -> 1.25f
            pair == setOf(Personality.PLAYFUL, Personality.BRAVE) -> 1.20f
            pair == setOf(Personality.CALM, Personality.GREEDY) -> 1.10f
            pair == setOf(Personality.PLAYFUL, Personality.SHY) -> 0.75f
            pair == setOf(Personality.GREEDY, Personality.BRAVE) -> 0.80f
            pair == setOf(Personality.GREEDY, Personality.SHY) -> 0.85f
            else -> 1.0f
        }
    }

    /** How much a given activity counts as time spent together. */
    private fun weightOf(kind: ActivityKind): Float = when (kind) {
        ActivityKind.COURT -> 1.20f
        ActivityKind.SOCIALISE -> 1.00f
        ActivityKind.PLAY -> 0.90f
        ActivityKind.GROOM -> 0.55f
        ActivityKind.EAT -> 0.45f
        // Doing something else in the same room still counts for a little. It is company.
        else -> 0.25f
    }

    /**
     * Raises [pal]'s standing to match its affinity, announcing each step once.
     *
     * The latch is [Pal.relation] itself, and this is the only place it moves. Testing the
     * affinity number against the threshold instead is the classic version of this bug: affinity
     * sits on the boundary, wobbles a tenth either way with every tick, and the player gets
     * "you are now friends" forty times a minute. A rank, once given, is never taken back.
     */
    private fun promoted(pal: Pal, events: MutableList<GameEvent>): Pal {
        // Family outranks affinity and is never re-announced as a friendship.
        if (pal.relation == Relation.OFFSPRING || pal.relation == Relation.PARENT) return pal
        var p = pal
        if (p.relation == Relation.VISITOR && p.affinity >= Pal.FRIEND_AT) {
            p = p.copy(relation = Relation.FRIEND)
            events += GameEvent.Befriended(p)
        }
        if (p.relation == Relation.FRIEND && p.canCourt) {
            p = p.copy(relation = Relation.MATE)
            events += GameEvent.Paired(p)
        }
        return p
    }

    /** The floor decay is not allowed to push a companion below, by standing. */
    private fun floorFor(relation: Relation): Float = when (relation) {
        Relation.VISITOR -> 0f
        Relation.FRIEND -> FRIEND_FLOOR
        Relation.MATE -> MATE_FLOOR
        Relation.OFFSPRING, Relation.PARENT -> FAMILY_FLOOR
    }

    /**
     * Fondness earned by everybody currently in the room, at [COMPANY_PER_SECOND].
     *
     * Takes no [Random]: the ordinary passage of time in company is not a die roll, and keeping
     * it deterministic means it cannot shift the visitor stream — the same seed still builds the
     * same world.
     *
     * The pet has to actually be there for it: asleep, dead, sulking or not yet old enough to
     * have noticed anybody is nobody's company. Whoever the creature has *chosen* to spend the
     * step with is skipped, because [interact] is about to pay them at the full rate and paying
     * them here as well would credit the same second twice.
     */
    private fun company(state: PetState, dt: Long, events: MutableList<GameEvent>): PetState {
        if (state.isDead || state.isSleeping || !state.isMindAwake || state.isSulking) return state
        if (state.pals.none { it.present }) return state
        val activity = state.activity
        val engaged = if (activity?.kind == ActivityKind.SOCIALISE || activity?.kind == ActivityKind.COURT) {
            activity.targetId
        } else {
            null
        }
        var changed = false
        val pals = state.pals.map { pal ->
            if (!pal.present || pal.id == engaged || pal.affinity >= 100f) return@map pal
            val gain = COMPANY_PER_SECOND * dt * warmth(state) * rapport(state.personality, pal.personality)
            if (gain <= 0f) return@map pal
            changed = true
            promoted(pal.copy(affinity = (pal.affinity + gain).coerceIn(0f, 100f)), events)
        }
        return if (changed) state.copy(pals = pals) else state
    }

    /** Time apart cools an acquaintance. Company is [company]'s and [interact]'s business. */
    private fun decayAffinity(state: PetState, dt: Long): PetState {
        if (state.pals.isEmpty()) return state
        val loss = DECAY_PER_HOUR * (dt / 3600f)
        var changed = false
        val pals = state.pals.map { pal ->
            if (pal.present) return@map pal
            val floor = floorFor(pal.relation)
            if (pal.affinity <= floor) return@map pal
            changed = true
            pal.copy(affinity = (pal.affinity - loss).coerceAtLeast(floor))
        }
        return if (changed) state.copy(pals = pals) else state
    }

    /**
     * Sees out anyone whose visit has run its course, and sends grown children out into the
     * world.
     *
     * On a timer rather than a die roll, so that a visitor's stay is something the player can
     * learn the shape of: a stranger is gone within a quarter of an hour unless you spend it
     * with them, and that is the pressure the whole social loop runs on.
     *
     * Children leaving is on the same kind of timer and is the reason the colony has a future.
     * A child that never grows up is a room that only ever fills, and the roster cap turns from
     * a limit into a stop: twelve children who never leave and can never be forgotten meant no
     * new visitor, no new mate, and no thirteenth child, for ever. Growing up is the ordinary
     * way a household makes room for the next one.
     */
    private fun departures(state: PetState, config: GameConfig, events: MutableList<GameEvent>): PetState {
        if (state.pals.none { it.present }) return state
        val leaveHome = leaveHomeSeconds(config)
        var changed = false
        val pals = state.pals.map { pal ->
            if (!pal.present) return@map pal
            // A child still at home. It is not visiting, so the visit timer does not apply to it;
            // what applies is whether it has grown up. [Pal.stage] is the latch — once it has
            // moved out it is an adult, and any later visit is an ordinary visit.
            if (pal.relation == Relation.OFFSPRING && pal.stage.order < LifeStage.ADULT.order) {
                if (state.ageSeconds - pal.metAtSeconds < leaveHome) return@map pal
                changed = true
                events += GameEvent.PalLeft(pal.name)
                return@map pal.copy(
                    stage = LifeStage.ADULT,
                    present = false,
                    lastSeenSeconds = state.ageSeconds,
                )
            }
            val stay = if (pal.isFriend) FRIEND_VISIT_SECONDS else VISIT_SECONDS
            if (state.ageSeconds - pal.lastSeenSeconds < stay) return@map pal
            changed = true
            events += GameEvent.PalLeft(pal.name)
            pal.copy(present = false, lastSeenSeconds = state.ageSeconds)
        }
        return if (changed) state.copy(pals = pals) else state
    }

    /**
     * Rolls for somebody calling round.
     *
     * A companion the pet already knows is as likely to come back as a stranger is to turn up
     * for the first time, which is the whole point of remembering them. A world that only ever
     * produces strangers has no relationships in it, only introductions.
     *
     * "Somebody the pet already knows" includes its own grown children. They were excluded back
     * when an offspring was always in the room and there was nothing to come back from.
     */
    private fun arrivals(
        state: PetState,
        config: GameConfig,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (state.isDead || state.isSleeping || !state.isMindAwake) return state
        if (!crossedWindow(state.ageSeconds, dt, ARRIVAL_CHECK_INTERVAL)) return state
        if (state.pals.count { it.present && it.relation != Relation.OFFSPRING } >= MAX_PRESENT_VISITORS) return state

        val chance = ARRIVAL_PER_HOUR * warmth(state) * (ARRIVAL_CHECK_INTERVAL / 3600f)
        if (random.nextFloat() >= chance) return state

        val away = state.pals.filter { !it.present }
        if (away.isNotEmpty() && random.nextFloat() < 0.5f) {
            // Somebody the pet already knows, favouring whoever it is fondest of.
            val returning = away.maxByOrNull { it.affinity + random.nextFloat() * 10f } ?: return state
            val back = returning.copy(present = true, lastSeenSeconds = state.ageSeconds)
            events += GameEvent.MetPal(back)
            return state.copy(pals = state.pals.map { if (it.id == back.id) back else it })
        }

        // A full roster is a state, not a stop: no new face while the twelve are all still in
        // touch, and it lifts of its own accord as soon as one of them is not.
        if (state.pals.size >= MAX_REMEMBERED_PALS &&
            state.pals.none { evictable(it, state.ageSeconds, config) }
        ) {
            return state
        }
        val stranger = generatePal(state, random)
        events += GameEvent.MetPal(stranger)
        return state.copy(pals = state.pals + stranger)
    }

    /**
     * A new face.
     *
     * Drawn from a random species and then drifted well past what [Genome.founder] would give,
     * because a visitor from the pet's own family drawn tightly around the same centre is often
     * closer than [Genome.MIN_USEFUL_DISTANCE] — and a colony of pals nobody is allowed to breed
     * with is a colony with no second half. The drift is what gives breeding somewhere to go.
     */
    private fun generatePal(state: PetState, random: Random): Pal {
        val species = Species.entries[random.nextInt(Species.entries.size)]
        val base = Genome.founder(species, random).toList()
        val genome = Genome.fromList(
            List(Genome.GENE_COUNT) { i ->
                (base[i] + (random.nextFloat() - random.nextFloat()) * WANDER_SPREAD).coerceIn(0f, 1f)
            },
        )
        val name = uniqueName(state.pals.map { it.name } + state.name, random)
        return Pal(
            id = "pal_${state.ageSeconds}_${random.nextInt(100_000)}",
            name = name,
            species = species,
            genome = genome,
            personality = Personality.entries[random.nextInt(Personality.entries.size)],
            // Visitors are grown: a wandering baby would be somebody's lost child, which is a
            // story this game has no way to finish.
            stage = if (random.nextFloat() < 0.25f) LifeStage.TEEN else LifeStage.ADULT,
            relation = Relation.VISITOR,
            affinity = 0f,
            metAtSeconds = state.ageSeconds,
            lastSeenSeconds = state.ageSeconds,
            skills = emptySet(),
            present = true,
        )
    }

    /** How far a visitor's genes are allowed to wander past its species' own centre. */
    private const val WANDER_SPREAD = 0.34f

    private val NAME_HEADS = listOf(
        "Bo", "Mi", "Ka", "Ru", "Ta", "Ne", "Zu", "Li", "Fen", "Sol",
        "Vex", "Ori", "Pud", "Wis", "Nim", "Cob", "Tam", "Ril", "Jun", "Hex",
        "Dov", "Ash", "Moe", "Pip", "Sig", "Yar",
    )
    private val NAME_TAILS = listOf(
        "", "bo", "ka", "lo", "mi", "na", "ri", "sk", "ta", "vi", "zu", "by",
    )

    /**
     * A short, pronounceable name nobody in the room is already using.
     *
     * Two identical names in one friends list is worse than an odd name: the player cannot tell
     * which one they befriended, and every screen that names a companion becomes ambiguous.
     */
    private fun uniqueName(taken: List<String>, random: Random): String {
        repeat(16) {
            val candidate = NAME_HEADS[random.nextInt(NAME_HEADS.size)] +
                NAME_TAILS[random.nextInt(NAME_TAILS.size)]
            if (candidate !in taken) return candidate
        }
        // Vanishingly unlikely with a dozen names in play, but the list must never collide.
        return NAME_HEADS[random.nextInt(NAME_HEADS.size)] +
            NAME_TAILS[random.nextInt(NAME_TAILS.size)] +
            NAME_TAILS[random.nextInt(NAME_TAILS.size)]
    }

    /** Eggs whose time has come become companions with the strongest standing in the game. */
    private fun hatchEggs(
        state: PetState,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        if (state.nest.isEmpty()) return state
        if (state.nest.none { it.isReady(state.ageSeconds) }) return state

        val remaining = ArrayList<NestEgg>(state.nest.size)
        val hatched = ArrayList<Pal>(state.nest.size)
        for (egg in state.nest) {
            if (!egg.isReady(state.ageSeconds)) {
                remaining += egg
                continue
            }
            // Teaching is a skill the parent had to learn, so a child inheriting anything is
            // something the player did rather than something the genome did. The easiest skills
            // go first: nobody teaches a newborn to read before it can feed itself.
            val taught = if (Skill.TEACH in state.skills) {
                state.skills.sortedBy { it.intellectRequired }.take(TAUGHT_SKILL_LIMIT).toSet()
            } else {
                emptySet()
            }
            val child = Pal(
                id = "pal_child_${egg.id}",
                name = uniqueName(state.pals.map { it.name } + hatched.map { it.name } + state.name, random),
                species = egg.species,
                genome = egg.genome,
                personality = Personality.entries[random.nextInt(Personality.entries.size)],
                stage = LifeStage.BABY,
                relation = Relation.OFFSPRING,
                affinity = OFFSPRING_AFFINITY,
                metAtSeconds = state.ageSeconds,
                lastSeenSeconds = state.ageSeconds,
                // Whoever laid it, not whoever is standing in the room: an egg can outlive its
                // parent, and the family tree is the one place that has to stay true to that.
                parentNames = listOf(egg.parentName.ifBlank { state.name }, egg.otherParentName),
                skills = taught,
                present = true,
            )
            hatched += child
            events += GameEvent.ChildHatched(child)
        }
        if (hatched.isEmpty()) return state
        return state.copy(pals = state.pals + hatched, nest = remaining)
    }

    /**
     * A companion nothing would be lost by forgetting.
     *
     * Two of them: an acquaintance who left and never mattered, and a grown child who moved out
     * and has not been round since its parent's whole adulthood. The second one is what stops a
     * household of twelve from being the last thing that ever happens — see [outOfTouchSeconds].
     * Anybody in the room, and any family still in touch, is not on this list at all.
     */
    private fun evictable(pal: Pal, ageSeconds: Long, config: GameConfig): Boolean = when {
        pal.present -> false
        pal.relation == Relation.VISITOR -> true
        pal.relation == Relation.OFFSPRING && pal.stage.order >= LifeStage.ADULT.order ->
            ageSeconds - pal.lastSeenSeconds >= outOfTouchSeconds(config)
        else -> false
    }

    /**
     * Keeps the remembered list inside [MAX_REMEMBERED_PALS].
     *
     * Runs on every tick rather than only after an arrival, so a save written by an older build
     * — or by a bug — is pulled back inside the cap the first time it is loaded instead of
     * growing for ever from wherever it started.
     */
    private fun capRemembered(
        state: PetState,
        config: GameConfig,
        events: MutableList<GameEvent>,
    ): PetState {
        if (state.pals.size <= MAX_REMEMBERED_PALS) return state
        val keep = state.pals
            // Ties broken by who was seen most recently, so when the roster has to give somebody
            // up it is the one furthest out of touch rather than whoever happens to be last in
            // the list — which, with a houseful of equally-loved children, was the newborn.
            .sortedWith(
                compareByDescending<Pal> { keepScore(it, state.ageSeconds, config) }
                    .thenByDescending { it.lastSeenSeconds },
            )
            .take(MAX_REMEMBERED_PALS)
            .map { it.id }
            .toSet()
        state.pals.forEach { pal ->
            // Somebody who was in the room has to be seen to leave it, or the UI shows a
            // companion that silently stops existing between one frame and the next. Family goes
            // on the record whether or not it was in the room, because losing one off the roster
            // is the biggest thing this function ever does.
            if (pal.id !in keep && (pal.present || pal.relation != Relation.VISITOR)) {
                events += GameEvent.PalLeft(pal.name)
            }
        }
        // Filtered rather than rebuilt from the sorted copy, so the list keeps its own order and
        // the friends screen does not reshuffle itself every time somebody is forgotten.
        return state.copy(pals = state.pals.filter { it.id in keep })
    }

    /**
     * Who is worth remembering, most first: standing, then fondness, then who is here now.
     *
     * The one demotion is the one [evictable] already names — a grown child gone long enough to
     * count as out of touch drops below a stranger standing in the room. Without it the two
     * disagreed: arrivals would let a new face in on the strength of an out-of-touch child, and
     * then this would throw the new face straight back out again, which is a meeting that never
     * happened and an event announcing that it did.
     */
    private fun keepScore(pal: Pal, ageSeconds: Long, config: GameConfig): Float {
        val rank = when (pal.relation) {
            Relation.OFFSPRING -> 400f
            Relation.PARENT -> 350f
            Relation.MATE -> 300f
            Relation.FRIEND -> 200f
            Relation.VISITOR -> 100f
        }
        val outOfTouch = if (pal.relation != Relation.VISITOR && evictable(pal, ageSeconds, config)) -350f else 0f
        return rank + outOfTouch + pal.affinity / 200f + if (pal.present) 40f else 0f
    }

    /** One trait, and what a move in either direction reads as on the breeding screen. */
    private class Trait(val up: String, val down: String, val of: (Genome) -> Float)

    private val TRAITS = listOf(
        Trait("a longer muzzle", "a rounder face") { it.muzzle },
        Trait("bigger ears", "smaller ears") { it.ears },
        Trait("floppier ears", "more upright ears") { it.earDroop },
        Trait("longer legs", "shorter legs") { it.limbs },
        Trait("a lower stance", "a more upright stance") { it.stance },
        Trait("a fuller tail", "a stubbier tail") { it.tail },
        Trait("a heavier build", "a slighter build") { it.build },
        Trait("a shaggier coat", "a smoother coat") { it.coat },
    )

    /** One sentence naming the change the player is most likely to actually notice. */
    private fun summarise(state: PetState, child: Genome, shift: Float): String {
        var best: Trait? = null
        var bestDelta = 0f
        for (trait in TRAITS) {
            val delta = trait.of(child) - trait.of(state.genome)
            if (kotlin.math.abs(delta) > kotlin.math.abs(bestDelta)) {
                best = trait
                bestDelta = delta
            }
        }
        val trend = when {
            shift > 0.04f -> "further from ${state.name}'s round shape"
            shift < -0.04f -> "rounder than ${state.name}"
            else -> "much like ${state.name}"
        }
        if (best == null || kotlin.math.abs(bestDelta) < 0.03f) {
            return "A child would look $trend."
        }
        val note = if (bestDelta > 0f) best.up else best.down
        return "A child would look $trend, with $note."
    }
}
