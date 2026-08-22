package com.neopal.pet.domain

/** A one-shot unlock. [test] is evaluated after every simulation tick and every player action. */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val rewardCoins: Int,
    val test: (PetState) -> Boolean,
)

object Achievements {

    val all: List<Achievement> = listOf(
        Achievement("first_hatch", "It moved!", "Hatch your first egg.", 20) { it.stage.isHatched },
        Achievement("first_meal", "Clean plate", "Feed your pet for the first time.", 10) { it.mealsEaten >= 1 },
        Achievement("chef", "Personal chef", "Serve 50 meals.", 60) { it.mealsEaten >= 50 },
        Achievement("tidy", "Spotless", "Clean up 25 times.", 40) { it.cleanups >= 25 },
        Achievement("player", "Warmed up", "Play 10 minigames.", 30) { it.gamesPlayed >= 10 },
        Achievement("champion", "Champion", "Win 25 minigames.", 90) { it.gamesWon >= 25 },
        Achievement("bonded", "Inseparable", "Reach 90 bond.", 80) { it.stats.bond >= 90f },
        Achievement("disciplined", "Well raised", "Reach 80 discipline.", 70) { it.stats.discipline >= 80f },
        Achievement("child", "Growing up", "Reach the Child stage.", 25) { it.stage.order >= LifeStage.CHILD.order },
        Achievement("teen", "Awkward years", "Reach the Teen stage.", 45) { it.stage.order >= LifeStage.TEEN.order },
        Achievement("adult", "All grown up", "Reach the Adult stage.", 80) { it.stage.order >= LifeStage.ADULT.order },
        Achievement("elder", "A long life", "Reach the Elder stage.", 150) { it.stage.order >= LifeStage.ELDER.order },
        Achievement("athletic", "Track star", "Raise an Athletic form.", 60) { it.branch == EvolutionBranch.ATHLETIC },
        Achievement("gourmand", "Gourmand", "Raise a Gourmand form.", 60) { it.branch == EvolutionBranch.GOURMAND },
        Achievement("scholar", "Bookworm", "Raise a Scholar form.", 60) { it.branch == EvolutionBranch.SCHOLAR },
        Achievement("feral", "Untamed", "Raise a Feral form.", 60) { it.branch == EvolutionBranch.FERAL },
        Achievement("nurse", "Get well soon", "Cure 5 illnesses.", 50) { it.medicineDoses >= 5 },
        Achievement("rich", "Piggy bank", "Hold 500 coins at once.", 50) { it.coins >= 500 },
        Achievement("level10", "Veteran keeper", "Reach keeper level 10.", 100) { it.level >= 10 },
        Achievement("collector", "Wardrobe", "Own 4 hats.", 70) { st ->
            ItemCatalog.hats.count { (st.inventory[it.id] ?: 0) > 0 } >= 4
        },
        Achievement("decorator", "Interior design", "Own 3 room themes.", 70) { st ->
            ItemCatalog.rooms.count { (st.inventory[it.id] ?: 0) > 0 } >= 3
        },
        Achievement("photographer", "Family album", "Collect 6 album pictures.", 60) { it.album.size >= 6 },
        Achievement("perfect_day", "Perfect care", "Hold every stat above 80.", 120) { st ->
            val s = st.stats
            st.stage.isHatched && !st.isDead &&
                s.satiety > 80f && s.happiness > 80f && s.energy > 80f && s.hygiene > 80f && s.health > 80f
        },
        Achievement("second_gen", "Legacy", "Start a second generation.", 40) { it.generation >= 2 },
        // Deliberately "score in", not "win". Three of the seven cannot be lost — the duet has no
        // wrong note by design — so a completion badge phrased as winning would be one nobody
        // could ever finish.
        Achievement("all_games", "Tried everything", "Score in all seven minigames.", 140) { st ->
            MiniGame.playedCount(st) == MiniGame.entries.size
        },
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String): Achievement? = byId[id]

    /**
     * Unlocks everything newly satisfied and pays out the rewards.
     * Returns the updated state plus the achievements that fired, so the UI can show a banner.
     */
    fun evaluate(state: PetState): Pair<PetState, List<Achievement>> {
        val newly = all.filter { it.id !in state.unlockedAchievements && it.test(state) }
        if (newly.isEmpty()) return state to emptyList()
        val updated = state.copy(
            unlockedAchievements = state.unlockedAchievements + newly.map { it.id },
            coins = state.coins + newly.sumOf { it.rewardCoins },
        )
        return updated to newly
    }
}
