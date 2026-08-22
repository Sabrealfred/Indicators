package com.neopal.pet.domain

/** What an item does when tapped from the inventory. */
enum class ItemKind { MEAL, SNACK, MEDICINE, TOY, HAT, ROOM }

/**
 * A shop / inventory entry. Stat deltas are applied once on use; cosmetics ([HAT], [ROOM])
 * are permanent unlocks instead of consumables.
 */
data class Item(
    val id: String,
    val name: String,
    val kind: ItemKind,
    val price: Int,
    /** Drawn procedurally by `ui.art.ItemArt` — no bitmaps ship with the app. */
    val iconKey: String,
    val tint: Long,
    val satiety: Float = 0f,
    val happiness: Float = 0f,
    val energy: Float = 0f,
    val hygiene: Float = 0f,
    val health: Float = 0f,
    val weight: Float = 0f,
    val bond: Float = 0f,
    val description: String = "",
) {
    val isConsumable: Boolean get() = kind == ItemKind.MEAL || kind == ItemKind.SNACK || kind == ItemKind.MEDICINE
    val isCosmetic: Boolean get() = kind == ItemKind.HAT || kind == ItemKind.ROOM

    /**
     * Bought once and kept: hats, rooms and toys.
     *
     * Not a synonym for [isCosmetic], which is the trap this exists to close. A [ItemKind.TOY]
     * is neither eaten nor worn, so every rule written as "cosmetic or consumable" quietly
     * treated the three toys as a fourth thing with no rules at all — the shop was willing to
     * sell you a second Bounce Ball, and the next generation threw the first one away.
     */
    val isDurable: Boolean get() = !isConsumable
}

/** Static catalog. Ids are the save-file keys, so they never change once shipped. */
object ItemCatalog {

    val all: List<Item> = listOf(
        // ---- Meals: heavy satiety, some weight ----
        Item("meal_bowl", "Kibble Bowl", ItemKind.MEAL, 12, "bowl", 0xFFE8A33D,
            satiety = 34f, weight = 1.4f, happiness = 3f, description = "A solid everyday meal."),
        Item("meal_stew", "Warm Stew", ItemKind.MEAL, 28, "stew", 0xFFD2603C,
            satiety = 48f, weight = 2.2f, happiness = 8f, health = 4f, description = "Filling and comforting."),
        Item("meal_salad", "Green Salad", ItemKind.MEAL, 22, "salad", 0xFF6FCF74,
            satiety = 26f, weight = -0.6f, health = 8f, description = "Light. Keeps the weight down."),
        Item("meal_sushi", "Sushi Set", ItemKind.MEAL, 46, "sushi", 0xFFF2F0E6,
            satiety = 40f, happiness = 16f, health = 6f, weight = 0.8f, bond = 4f,
            description = "A favourite of every family."),

        // ---- Snacks: happiness first, satiety second, weight cost ----
        Item("snack_berry", "Sun Berry", ItemKind.SNACK, 6, "berry", 0xFFE0555F,
            satiety = 10f, happiness = 12f, weight = 0.5f, description = "Sweet little pick-me-up."),
        Item("snack_cake", "Cloud Cake", ItemKind.SNACK, 18, "cake", 0xFFF6C6D9,
            satiety = 14f, happiness = 24f, weight = 2.0f, description = "Big smile, bigger belly."),
        Item("snack_icecream", "Frost Cone", ItemKind.SNACK, 14, "icecream", 0xFF8FD8E8,
            satiety = 8f, happiness = 20f, weight = 1.2f, energy = 4f, description = "Cold and cheerful."),
        Item("snack_energy", "Volt Gum", ItemKind.SNACK, 20, "gum", 0xFFF5E663,
            satiety = 4f, happiness = 6f, energy = 30f, description = "Chases away a yawn."),

        // ---- Medicine ----
        Item("medicine", "Pill", ItemKind.MEDICINE, 25, "pill", 0xFFFFFFFF,
            health = 34f, happiness = -6f, description = "Cures one bout of illness."),
        Item("medicine_super", "Elixir", ItemKind.MEDICINE, 70, "elixir", 0xFF9C7BF0,
            health = 100f, happiness = 6f, description = "Full cure, no bitter face."),
        Item("soap", "Bubble Soap", ItemKind.MEDICINE, 10, "soap", 0xFF7FD4F5,
            hygiene = 55f, happiness = 4f, description = "A proper scrub-down."),

        // ---- Toys: used from the play menu, never consumed ----
        Item("toy_ball", "Bounce Ball", ItemKind.TOY, 30, "ball", 0xFFFF6B57,
            happiness = 14f, energy = -8f, bond = 3f, description = "Unlocks Ball Rally."),
        Item("toy_drum", "Beat Drum", ItemKind.TOY, 45, "drum", 0xFF5AA9E6,
            happiness = 16f, energy = -10f, bond = 4f, description = "Unlocks Rhythm Tap."),
        Item("toy_cards", "Memory Cards", ItemKind.TOY, 45, "cards", 0xFFB48CE8,
            happiness = 12f, energy = -6f, bond = 5f, description = "Unlocks Memory Match."),

        // ---- Hats: pure cosmetics, drawn on top of the creature ----
        Item("hat_cap", "Racer Cap", ItemKind.HAT, 60, "cap", 0xFFFF3C28, description = "Classic red cap."),
        Item("hat_crown", "Tiny Crown", ItemKind.HAT, 180, "crown", 0xFFF5C542, description = "For a well-raised pet."),
        Item("hat_bow", "Ribbon Bow", ItemKind.HAT, 80, "bow", 0xFFF56AA5, description = "A neat little bow."),
        Item("hat_goggles", "Sky Goggles", ItemKind.HAT, 120, "goggles", 0xFF00C3E3, description = "Pilot ready."),
        Item("hat_leaf", "Leaf Hat", ItemKind.HAT, 90, "leafhat", 0xFF6FCF74, description = "Straight from the garden."),

        // ---- Room themes: repaint the whole scene ----
        Item("room_default", "Cozy Room", ItemKind.ROOM, 0, "room", 0xFF3A6EA5, description = "Where every pet starts."),
        Item("room_beach", "Sunset Beach", ItemKind.ROOM, 150, "room", 0xFFFF9A5B, description = "Waves and warm sand."),
        Item("room_space", "Orbit Deck", ItemKind.ROOM, 220, "room", 0xFF2B2D6E, description = "Stars all night long."),
        Item("room_forest", "Deep Forest", ItemKind.ROOM, 180, "room", 0xFF2E6B4F, description = "Fireflies included."),
        Item("room_arcade", "Neon Arcade", ItemKind.ROOM, 260, "room", 0xFF6B2BA5, description = "Cabinets humming."),
    )

    private val byId: Map<String, Item> = all.associateBy { it.id }

    operator fun get(id: String): Item? = byId[id]
    fun require(id: String): Item = byId.getValue(id)

    fun ofKind(kind: ItemKind): List<Item> = all.filter { it.kind == kind }
    val foods: List<Item> get() = all.filter { it.kind == ItemKind.MEAL || it.kind == ItemKind.SNACK }
    val rooms: List<Item> get() = ofKind(ItemKind.ROOM)
    val hats: List<Item> get() = ofKind(ItemKind.HAT)
}
