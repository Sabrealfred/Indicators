package com.neopal.pet.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.random.Random

/**
 * The heritable half of a creature.
 *
 * Every gene is a plain 0..1 dial rather than a discrete allele, for one reason: a player has to
 * be able to *see* inheritance working. Discrete traits make a child either identical to a parent
 * or a surprise, and both read as randomness. Continuous genes give a child that is visibly
 * halfway between the two, with a nudge — which is what breeding for a longer muzzle over four
 * generations needs in order to feel like breeding rather than gambling.
 *
 * Morphology genes decide the silhouette; temperament genes decide how the brain weighs its
 * options. They live in one type because they are inherited as one package — you cannot pick the
 * long ears without also taking whatever came with them.
 */
@Serializable
data class Genome(
    // ---- morphology ----
    /** 0 = flat round face, 1 = long snout. The single biggest lever on "does this read as a dog". */
    val muzzle: Float = 0.14f,
    /** 0 = barely-there nubs, 1 = long hound ears. */
    val ears: Float = 0.26f,
    /** 0 = pricked upright, 1 = fully floppy. */
    val earDroop: Float = 0.30f,
    /** 0 = stubby, 1 = leggy. */
    val limbs: Float = 0.34f,
    /** 0 = stands upright like a plush toy, 1 = walks on four legs. */
    val stance: Float = 0.06f,
    /** 0 = stub, 1 = long plume. */
    val tail: Float = 0.40f,
    /** 0 = slight, 1 = barrel-chested. */
    val build: Float = 0.50f,
    /** 0 = smooth, 1 = shaggy. */
    val coat: Float = 0.28f,
    /** Palette shift within the species' own family, so siblings are recognisably related. */
    val hue: Float = 0.50f,

    // ---- temperament ----
    /** Weights exploring and studying against resting. */
    val curiosity: Float = 0.50f,
    /** Weights seeking company, and how fast affinity with a companion grows. */
    val sociability: Float = 0.50f,
    /** Weights eating, and how early hunger starts to feel urgent. */
    val appetite: Float = 0.50f,
    /** Weights play and movement; also how quickly energy comes back. */
    val vigor: Float = 0.50f,
    /** Multiplies how fast intellect and skills are picked up. */
    val wit: Float = 0.50f,
) {
    /**
     * 0..1: how far this creature has drifted from the round starter blob toward a four-legged,
     * long-muzzled, floppy-eared animal. The art reads this to decide whether to draw a biped or
     * a quadruped, and the chronicle reads it to notice when a lineage has changed shape.
     *
     * Weighted, not averaged: stance and muzzle are what an eye actually reads as "dog", while
     * tail and limbs only support the impression once those two are already there.
     */
    val houndliness: Float
        get() = (stance * 0.38f + muzzle * 0.30f + earDroop * ears * 0.16f +
            limbs * 0.10f + tail * 0.06f).coerceIn(0f, 1f)

    /** Every gene in a fixed order. Crossover and distance walk this instead of naming fields. */
    fun toList(): List<Float> = listOf(
        muzzle, ears, earDroop, limbs, stance, tail, build, coat, hue,
        curiosity, sociability, appetite, vigor, wit,
    )

    companion object {
        /** How many genes there are. Kept honest by [GeneticsInvariants]. */
        const val GENE_COUNT = 14

        fun fromList(g: List<Float>): Genome {
            require(g.size == GENE_COUNT) { "expected $GENE_COUNT genes, got ${g.size}" }
            fun at(i: Int) = g[i].coerceIn(0f, 1f)
            return Genome(
                muzzle = at(0), ears = at(1), earDroop = at(2), limbs = at(3), stance = at(4),
                tail = at(5), build = at(6), coat = at(7), hue = at(8),
                curiosity = at(9), sociability = at(10), appetite = at(11), vigor = at(12), wit = at(13),
            )
        }

        /**
         * A founder genome for [species].
         *
         * Founders are drawn tightly around the species' own centre rather than uniformly across
         * the range. A first pet that hatched already half-dog would spend the whole feature's
         * budget in the first ten seconds; the point is that the shape is somewhere to *get to*.
         */
        fun founder(species: Species, random: Random): Genome {
            /** Draws around [centre] with a triangular spread, so extremes stay rare. */
            fun near(centre: Float, spread: Float): Float =
                (centre + (random.nextFloat() - random.nextFloat()) * spread).coerceIn(0f, 1f)

            val base = Genome()
            return when (species) {
                // Aqua: smooth, short-limbed, sociable.
                Species.AQUA -> base.copy(
                    muzzle = near(0.12f, 0.10f), ears = near(0.18f, 0.10f), earDroop = near(0.25f, 0.18f),
                    limbs = near(0.28f, 0.10f), stance = near(0.05f, 0.06f), tail = near(0.46f, 0.14f),
                    build = near(0.46f, 0.16f), coat = near(0.16f, 0.10f),
                    sociability = near(0.62f, 0.20f), vigor = near(0.48f, 0.20f),
                )
                // Ember: stocky, big appetite, short fuse.
                Species.EMBER -> base.copy(
                    muzzle = near(0.20f, 0.12f), ears = near(0.30f, 0.14f), earDroop = near(0.20f, 0.16f),
                    limbs = near(0.32f, 0.12f), stance = near(0.10f, 0.08f), tail = near(0.34f, 0.14f),
                    build = near(0.64f, 0.16f), coat = near(0.34f, 0.16f),
                    appetite = near(0.66f, 0.20f), vigor = near(0.58f, 0.20f),
                )
                // Leaf: calm, shaggy, studious.
                Species.LEAF -> base.copy(
                    muzzle = near(0.14f, 0.10f), ears = near(0.34f, 0.16f), earDroop = near(0.42f, 0.20f),
                    limbs = near(0.30f, 0.10f), stance = near(0.06f, 0.06f), tail = near(0.36f, 0.14f),
                    build = near(0.52f, 0.16f), coat = near(0.48f, 0.18f),
                    curiosity = near(0.60f, 0.20f), wit = near(0.62f, 0.20f),
                )
                // Volt: leggy, restless, already leaning four-footed.
                Species.VOLT -> base.copy(
                    muzzle = near(0.22f, 0.12f), ears = near(0.40f, 0.16f), earDroop = near(0.16f, 0.14f),
                    limbs = near(0.44f, 0.14f), stance = near(0.16f, 0.10f), tail = near(0.50f, 0.16f),
                    build = near(0.40f, 0.16f), coat = near(0.24f, 0.14f),
                    vigor = near(0.70f, 0.18f), curiosity = near(0.58f, 0.20f),
                )
            }
        }

        /**
         * A child of [a] and [b].
         *
         * Per gene: inherit one parent's value outright, or the midpoint, then mutate. Straight
         * averaging would collapse a whole lineage to the mean within three generations and no
         * amount of breeding would ever produce anything new; picking one parent outright keeps
         * variance alive, and the midpoint draw is what makes children look like *both* parents
         * rather than like a coin flip.
         *
         * [mutation] is the standard deviation of the nudge applied afterwards. It is what lets a
         * line reach a shape neither founder had — without it, breeding could only ever shuffle
         * the genes that happened to be dealt at the start.
         */
        fun breed(a: Genome, b: Genome, random: Random, mutation: Float = DEFAULT_MUTATION): Genome {
            val ga = a.toList()
            val gb = b.toList()
            val child = ArrayList<Float>(GENE_COUNT)
            for (i in 0 until GENE_COUNT) {
                val roll = random.nextFloat()
                val inherited = when {
                    roll < 0.36f -> ga[i]
                    roll < 0.72f -> gb[i]
                    else -> (ga[i] + gb[i]) / 2f
                }
                // Triangular noise: small nudges are common, a leap is rare but possible.
                val drift = (random.nextFloat() - random.nextFloat()) * mutation
                child += (inherited + drift).coerceIn(0f, 1f)
            }
            return fromList(child)
        }

        /**
         * Mean absolute difference across every gene, 0..1.
         *
         * Used to keep a line from folding in on itself: two creatures that are already nearly
         * identical make a child with nothing new in it, and the breeding screen says so rather
         * than letting the player grind a pairing that cannot go anywhere.
         */
        fun distance(a: Genome, b: Genome): Float {
            val ga = a.toList()
            val gb = b.toList()
            var sum = 0f
            for (i in 0 until GENE_COUNT) sum += abs(ga[i] - gb[i])
            return sum / GENE_COUNT
        }

        /** Below this the pair is too closely related for the child to be interesting. */
        const val MIN_USEFUL_DISTANCE = 0.06f

        /** Standard mutation. Roughly one visible change every couple of generations. */
        const val DEFAULT_MUTATION = 0.085f
    }
}

/** Guards the one thing [Genome.toList] and [Genome.fromList] can silently disagree about. */
internal object GeneticsInvariants {
    val roundTripsCleanly: Boolean
        get() = Genome().toList().size == Genome.GENE_COUNT
}

/**
 * The *expressed* body: what the renderer actually draws.
 *
 * Genes are not the whole story. A creature's shape is also its age (babies are all head), its
 * weight, and how it has lived — an athletic pet grows longer legs than its genes alone asked
 * for, and a feral one carries itself lower. Keeping that arithmetic here rather than in the art
 * means the drawing code never has to know what a life stage or an evolution branch is; it asks
 * for a body and gets numbers.
 *
 * All values are fractions of the creature's own height unless noted.
 */
data class Morphology(
    /** How far the snout juts past the face, 0 = none. */
    val muzzleLength: Float,
    /** Ear length. */
    val earLength: Float,
    /** 0 = pricked, 1 = hanging. */
    val earDroop: Float,
    /** Leg length. */
    val legLength: Float,
    /**
     * 0 = stands and is drawn upright, 1 = drawn on all fours with the body horizontal.
     * The art crossfades between the two, so intermediate values are a legitimate crouch.
     */
    val quadruped: Float,
    val tailLength: Float,
    /** Multiplier on the body's horizontal radius. */
    val bodyWidth: Float,
    /** 0 = smooth outline, 1 = fur tufts break the silhouette. */
    val shagginess: Float,
    /** Hue rotation applied to the species palette, −1..1. */
    val hueShift: Float,
) {
    /** 0..1 for UI copy: "mostly blob", "leggy", "hound". */
    val houndliness: Float
        get() = (quadruped * 0.42f + (muzzleLength / MAX_MUZZLE) * 0.30f +
            earDroop * (earLength / MAX_EAR) * 0.16f + (legLength / MAX_LEG) * 0.12f).coerceIn(0f, 1f)

    companion object {
        // Ceilings, so houndliness is a ratio against something real rather than a magic scale.
        const val MAX_MUZZLE = 0.34f
        const val MAX_EAR = 0.46f
        const val MAX_LEG = 0.52f

        /**
         * Expresses [genome] on a creature at [stage].
         *
         * Babies express almost none of it: a newborn is a head with eyes whatever its genes say,
         * and the shape it is growing into arrives over the following stages. That is the whole
         * reward loop of breeding — you can see the parents in the child, but only once it grows.
         */
        fun of(
            genome: Genome,
            stage: LifeStage,
            branch: EvolutionBranch = EvolutionBranch.BALANCED,
            weightGrams: Float = 12f,
        ): Morphology {
            // How much of the genome the body is allowed to show yet.
            val maturity = when (stage) {
                LifeStage.EGG -> 0f
                LifeStage.BABY -> 0.22f
                LifeStage.CHILD -> 0.52f
                LifeStage.TEEN -> 0.82f
                LifeStage.ADULT -> 1f
                // Elders settle back down: joints stiffen, the stance lowers, the ears droop more.
                LifeStage.ELDER -> 0.92f
            }

            // Lifestyle. These are deliberately small next to the genes: a run of athletic pets
            // must not be mistakable for a lineage that was actually bred long-legged.
            val legBonus = if (branch == EvolutionBranch.ATHLETIC) 0.05f else 0f
            val stanceBonus = when (branch) {
                // A pet raised by absence learns to get around on its own four feet.
                EvolutionBranch.FERAL -> 0.18f
                EvolutionBranch.ATHLETIC -> 0.07f
                else -> 0f
            }
            val elderDroop = if (stage == LifeStage.ELDER) 0.12f else 0f

            val fat = ((weightGrams - 12f) / 108f).coerceIn(0f, 1f)
            val widthFromBranch = when (branch) {
                EvolutionBranch.ATHLETIC -> -0.06f
                EvolutionBranch.GOURMAND -> 0.10f
                else -> 0f
            }

            return Morphology(
                muzzleLength = MAX_MUZZLE * genome.muzzle * maturity,
                earLength = MAX_EAR * genome.ears * maturity,
                earDroop = (genome.earDroop * maturity + elderDroop).coerceIn(0f, 1f),
                legLength = MAX_LEG * (genome.limbs + legBonus).coerceIn(0f, 1f) * maturity,
                quadruped = ((genome.stance + stanceBonus) * maturity).coerceIn(0f, 1f),
                tailLength = 0.62f * genome.tail * maturity,
                bodyWidth = 0.86f + genome.build * 0.24f + fat * 0.30f + widthFromBranch,
                shagginess = genome.coat * maturity,
                // Centred so 0.5 is "no shift"; the swing is small on purpose, because a hue
                // rotation wide enough to leave the species palette stops reading as family.
                hueShift = (genome.hue - 0.5f) * 0.5f,
            )
        }
    }
}
