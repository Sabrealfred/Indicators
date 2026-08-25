package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/** One gibibyte. Every memory figure below is in bytes, because that is what Android reports. */
private const val GIB = 1_073_741_824L

/**
 * Which of the three on-device models this phone is being asked about.
 *
 * ## Why there are three, and why the player is not asked which
 *
 * "Light or good?" is not a question anybody can answer — it asks a player to predict their own
 * phone's memory behaviour, which is a thing Android itself gets wrong. So all three ship as
 * options and the choice is a **measurement**: read what this device can actually hold, offer the
 * largest that fits, and offer nothing where none of them do.
 *
 * The asymmetry in [LocalModelFit.choose] is the important part. A player may force a *smaller*
 * model — that is a preference about battery and heat, and being wrong about it costs them a
 * slightly duller creature. A player may **not** force a larger one onto a phone that cannot hold
 * it, because being wrong about *that* costs them the process: Android does not slow a program
 * down for asking for too much, it kills it. To somebody watching a tamagotchi, an app that
 * vanishes and a pet that died are the same event. It is the worst failure this game has, and it
 * is not something a player can consent to in advance because they cannot see it coming.
 *
 * ## Where the numbers come from
 *
 * [downloadBytes] and [minDeviceMemoryGb] are read off Google's own shipping allowlist for the AI
 * Edge Gallery (`model_allowlists/1_0_19.json`), which is the manifest the reference app fetches
 * before it offers a model: `sizeInBytes` is the exact `.litertlm` size and `minDeviceMemoryInGb`
 * is the device-RAM floor Google publishes for it. They are exact, not rounded from prose.
 *
 * Note what [minDeviceMemoryGb] is and is not. It is a claim about the **device** — the figure on
 * the box — and it is not the per-process budget Android will actually grant this app. It is
 * therefore treated as necessary and not sufficient: [DeviceTier] checks it, and then checks its
 * own arithmetic and its own heap-class floor on top. See [LocalModelFit.SYSTEM_RESERVE_BYTES].
 */
@Serializable
enum class LocalModelVariant(
    val displayName: String,
    /** What one line of settings says about it. */
    val summary: String,
    /** The artefact's name in the allowlist, so the downloader and this file cannot disagree. */
    val fileName: String,
    /** Exact `.litertlm` size in bytes. Shown to the player before a single byte is fetched. */
    val downloadBytes: Long,
    /**
     * Google's published device-memory floor, in whole nominal gigabytes — the number on the box,
     * not the number `MemoryInfo.totalMem` reports. See [minTotalRamBytes] for the conversion.
     */
    val minDeviceMemoryGb: Int,
    /**
     * Per-process Java heap budget under which this device is not worth trying.
     *
     * A blunt instrument, used as a blunt instrument. `ActivityManager.memoryClass` measures the
     * *Java* heap, and an inference engine allocates natively and maps its weights from a file,
     * so this number does not bound what the model costs. What it does do is sort phones by
     * vintage — the budget tracks the device's real capability closely enough to catch hardware
     * too old for any of this, including hardware that reports a flattering total. It is a floor,
     * never the capacity test.
     */
    val minMemoryClassMb: Int,
) {
    /**
     * Gemma 3, 1 B, int4. 557 MiB on disk and the only one that reaches an ordinary phone.
     *
     * This tier is not a consolation prize. The creature speaks in one- and two-line sentences,
     * and a billion parameters answers that instantly on hardware most players actually own,
     * which is worth more to this game than a better sentence two seconds later.
     */
    TINY(
        displayName = "Compact",
        summary = "Quick, small, and easy on the battery. Enough for a creature that speaks in " +
            "short sentences.",
        fileName = "gemma3-1b-it-int4.litertlm",
        downloadBytes = 584_417_280L,
        minDeviceMemoryGb = 6,
        minMemoryClassMb = 128,
    ),

    /** Gemma 4 E2B, int4/QAT. 2.41 GiB on disk, and an eight-gigabyte phone to hold it. */
    SMALL(
        displayName = "Standard",
        summary = "Noticeably better company. Bigger download, and the phone will warm up while " +
            "it thinks.",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadBytes = 2_588_147_712L,
        minDeviceMemoryGb = 8,
        minMemoryClassMb = 192,
    ),

    /**
     * Gemma 4 E4B, int4/QAT. 3.41 GiB on disk, and Google asks for **twelve** gigabytes of device
     * memory — which is a current flagship, not a recent good phone.
     */
    LARGE(
        displayName = "Full",
        summary = "The best it can do without the internet. Only offered on phones with memory " +
            "to spare.",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadBytes = 3_659_530_240L,
        minDeviceMemoryGb = 12,
        minMemoryClassMb = 256,
    ),
    ;

    /**
     * What it plausibly costs to have loaded and generating.
     *
     * The weights are most of it and not all of it: the engine, the activations and the
     * key-value cache are resident too. [LocalModelFit.RUNTIME_OVERHEAD_BYTES] is this project's
     * own estimate of that remainder, and it is an estimate — nobody here has measured one. It
     * exists so that the install decision is arithmetic somebody can check and argue with,
     * instead of a floor taken on trust from a JSON file.
     */
    val estimatedResidentBytes: Long get() = downloadBytes + LocalModelFit.RUNTIME_OVERHEAD_BYTES

    /**
     * [minDeviceMemoryGb] converted into a figure `MemoryInfo.totalMem` can be compared against.
     *
     * The conversion is the whole point of this property. A phone sold as "8 GB" never reports
     * 8 GiB: the kernel image and the hardware carve-outs are taken off the top before Android
     * ever sees the rest, and `totalMem` comes back five to fifteen per cent light. Comparing
     * Google's nominal floor straight against `totalMem` would reject every phone that meets it,
     * and the feature would silently never be offered to anybody — the exact class of bug this
     * whole file is written in pure Kotlin to make visible.
     */
    val minTotalRamBytes: Long
        get() = minDeviceMemoryGb * GIB * LocalModelFit.REPORTED_PERCENT_OF_NOMINAL / 100L

    /** Free space under which the download must not be started. */
    val minFreeDiskBytes: Long get() = downloadBytes + LocalModelFit.DISK_HEADROOM_BYTES

    companion object {
        /** Biggest first, which is the order everything here offers and iterates in. */
        val largestFirst: List<LocalModelVariant> get() = listOf(LARGE, SMALL, TINY)
    }
}

/**
 * The numbers an Android caller reads off the system, handed over as plain longs.
 *
 * Nothing in this file imports Android, and this type is the seam that makes that possible: the
 * caller does the `ActivityManager` and `StatFs` reads, and this half does the arithmetic. That
 * split is not tidiness — it is what lets every rule below be checked against phones that do not
 * exist here, which is all of them.
 */
data class DeviceMemory(
    /** `ActivityManager.MemoryInfo.totalMem`. Always less than the figure on the box. */
    val totalRamBytes: Long,
    /** `MemoryInfo.availMem`. A reading of this second, not a property of the phone. */
    val availableRamBytes: Long,
    /** `ActivityManager.memoryClass`, in megabytes. */
    val memoryClassMb: Int,
    /** `ActivityManager.isLowRamDevice`. Android saying plainly that this is a small device. */
    val isLowRamDevice: Boolean,
    /** Free bytes on the volume the model would live on. */
    val freeDiskBytes: Long,
    /** `MemoryInfo.lowMemory`. True while the system is already reclaiming. */
    val lowMemoryNow: Boolean = false,
) {
    /**
     * True when this is a bad moment to load anything large, whatever the phone can hold in
     * principle.
     *
     * Separate from the install decision on purpose. [totalRamBytes] is a property of the phone
     * and decides once; this is a property of right now — six other apps open, a camera running —
     * and decides every time the talk screen opens. Deciding both from the same number would
     * either refuse the install on a busy afternoon or load the model into a phone that is
     * already out of room.
     */
    val underPressure: Boolean
        get() = lowMemoryNow || availableRamBytes < LocalModelFit.LOAD_FLOOR_BYTES
}

/** How much model this device can hold. A judgement about the hardware, not about the disk. */
enum class DeviceTier {
    /** Nothing runs here. Not a "reduced experience" — the feature is simply never offered. */
    UNFIT,

    /** The compact model, and only it. This is where most phones in the world land. */
    MODEST,

    /** Up to the standard one. */
    CAPABLE,

    /** All three. A current flagship. */
    ROOMY,
    ;

    /** The biggest variant this tier will hold, or null. */
    val largest: LocalModelVariant?
        get() = when (this) {
            UNFIT -> null
            MODEST -> LocalModelVariant.TINY
            CAPABLE -> LocalModelVariant.SMALL
            ROOMY -> LocalModelVariant.LARGE
        }

    /** Everything this tier will hold, largest first. */
    val holds: List<LocalModelVariant>
        get() = when (this) {
            UNFIT -> emptyList()
            MODEST -> listOf(LocalModelVariant.TINY)
            CAPABLE -> listOf(LocalModelVariant.SMALL, LocalModelVariant.TINY)
            ROOMY -> LocalModelVariant.largestFirst
        }

    companion object {
        /**
         * Sorts a device by what it can survive.
         *
         * [DeviceMemory.isLowRamDevice] is checked first and accepted without argument: a device
         * that ships with that flag set has had its whole memory policy tuned around not doing
         * this, and out-arguing it from a `totalMem` reading is how you discover that the total
         * was never the constraint.
         *
         * Below it, three tests have to agree, and they are three because they are three
         * different claims. Google's floor is what Google says the device needs; the heap class
         * is what Android says this app gets; the headroom sum is what this project can work out
         * for itself. Any one of them could be the wrong one to trust, so none of them is trusted
         * alone.
         */
        fun of(memory: DeviceMemory): DeviceTier {
            if (memory.isLowRamDevice) return UNFIT
            return when (LocalModelVariant.largestFirst.firstOrNull { memory.canHold(it) }) {
                LocalModelVariant.LARGE -> ROOMY
                LocalModelVariant.SMALL -> CAPABLE
                LocalModelVariant.TINY -> MODEST
                null -> UNFIT
            }
        }

        private fun DeviceMemory.canHold(variant: LocalModelVariant): Boolean =
            totalRamBytes >= variant.minTotalRamBytes &&
                memoryClassMb >= variant.minMemoryClassMb &&
                totalRamBytes - variant.estimatedResidentBytes >= LocalModelFit.SYSTEM_RESERVE_BYTES
    }
}

/** Why nothing is being offered. Two of these are permanent and one is housekeeping. */
enum class ModelBlocker(val headline: String, val note: String) {
    /**
     * Android has flagged the device itself. Nothing the player does changes this, so the
     * sentence must not imply there is anything to try.
     */
    LOW_RAM_DEVICE(
        "This phone is not built for it",
        "Android reports this as a low-memory device, and a model would be shut down " +
            "mid-sentence. Everything else in the game works exactly as it does everywhere else.",
    ),

    /** Enough phone to run the game, not enough to hold a model. Also permanent. */
    NOT_ENOUGH_RAM(
        "There is not enough memory for it",
        "A model has to stay in memory the whole time it is thinking, and this phone has no room " +
            "for even the compact one alongside everything else it is already running.",
    ),

    /**
     * The one worth saying out loud, because it is the one the player can fix. Kept distinct from
     * the memory verdict for exactly that reason: "your phone cannot" and "your phone is full"
     * deserve different sentences, and merging them tells somebody to give up on a problem they
     * could have solved in a minute.
     */
    NOT_ENOUGH_DISK(
        "There is not enough space free",
        "The download needs room to spare afterwards. Free some space and this will be offered " +
            "again.",
    ),
}

/**
 * What this particular device may install, and what it may not.
 *
 * Pure arithmetic over [DeviceMemory]. Everything the player is allowed to choose comes from here,
 * and — more to the point — everything they are *not* allowed to choose is refused here rather
 * than at load time, where the refusal arrives as a dead process and no stack trace.
 */
data class LocalModelFit(
    val tier: DeviceTier,
    /** Everything installable on this device right now, largest first. Empty when nothing is. */
    val allowed: List<LocalModelVariant>,
    /** What is offered unless the player says otherwise. Null when [allowed] is empty. */
    val recommended: LocalModelVariant?,
    /** Why nothing is offered, or null when something is. */
    val blocker: ModelBlocker?,
    /**
     * Bytes that would have to be freed before [DeviceTier.largest] became installable.
     *
     * Zero when it already is, and zero when no amount of free space would help — a phone that
     * cannot hold a model in memory is not one clear-out away from being able to, and telling
     * somebody to delete photographs for a feature they still will not get wastes their evening
     * and then says no anyway.
     */
    val freeUpBytes: Long,
) {
    /** True when there is anything to offer at all. */
    val hasAnything: Boolean get() = allowed.isNotEmpty()

    /** The least demanding thing on offer, for the player who wants to spend nothing. */
    val smallest: LocalModelVariant? get() = allowed.lastOrNull()

    /** True when this device may install [variant]. The only gate that matters before a download. */
    fun permits(variant: LocalModelVariant): Boolean = variant in allowed

    /**
     * What to install, given whatever the player has asked for.
     *
     * A [preferred] variant this device can hold is honoured, whichever direction it points — a
     * player picking the compact model on a phone that could run the full one is trading
     * sentences for battery, and it is theirs to trade. A [preferred] variant this device cannot
     * hold is **not** refused with an error and it is **not** granted: it quietly becomes
     * [recommended], the largest thing that actually fits. That is the asymmetry described on
     * [LocalModelVariant], expressed as one `when` — there is no argument to this function that
     * returns something [permits] would reject.
     */
    fun choose(preferred: LocalModelVariant?): LocalModelVariant? = when {
        !hasAnything -> null
        preferred != null && permits(preferred) -> preferred
        else -> recommended
    }

    /**
     * True when [variant] may be brought into memory *now*.
     *
     * Two questions, and they are not the same one. [permits] asks whether this device should
     * ever hold this model; this also asks whether this is a sensible minute to bring it in. A
     * model loaded while the system is already reclaiming is a model that gets the app killed for
     * asking — so the check happens before the allocation rather than inside a `catch` that will
     * never run, because an out-of-memory kill is not an exception anybody catches.
     *
     * It only ever narrows. A phone with gigabytes free this second still may not load something
     * [permits] rejects.
     */
    fun loadableNow(variant: LocalModelVariant, memory: DeviceMemory): Boolean =
        permits(variant) && !memory.underPressure

    companion object {
        /**
         * What a nominal gigabyte is actually worth, once the kernel and the carve-outs have
         * taken theirs, as a percentage.
         *
         * Integer percent rather than a float so the comparison is exact and reproducible. The
         * value is deliberately generous: a phone sold as 8 GB reports somewhere between 7.2 and
         * 7.9 billion bytes depending on the vendor, and a stricter conversion would reject
         * devices that meet Google's stated floor. Being slightly too lenient here is caught by
         * the other two tests in [DeviceTier.of]; being too strict is not caught by anything,
         * because a feature that is never offered generates no complaints.
         */
        const val REPORTED_PERCENT_OF_NOMINAL = 85L

        /**
         * This project's estimate of everything resident that is not the weights: the engine, the
         * activations, and the key-value cache for a short conversation.
         *
         * An estimate, and labelled as one. It is here so that the install decision contains
         * arithmetic somebody can check rather than resting entirely on a floor taken on trust
         * from a manifest. The first real figure comes from a phone.
         */
        const val RUNTIME_OVERHEAD_BYTES = 805_306_368L // 768 MiB

        /**
         * What is left for Android, the launcher, the keyboard, this game, and whatever the
         * player was doing before they opened it.
         *
         * Set high on purpose. The cost of setting it too high is that a phone which would have
         * coped gets offered a smaller model, and nobody will ever notice; the cost of setting it
         * too low is a process death that reads as a dead pet. Those are not comparable, so this
         * errs in the direction that is merely disappointing.
         */
        const val SYSTEM_RESERVE_BYTES = 1_879_048_192L // 1.75 GiB

        /**
         * Free space that has to survive the download.
         *
         * Not slack for the transfer — the file is the file. This is what stops the game from
         * being the reason a phone hits zero bytes free, which breaks the camera, the messages
         * app and every save this game has ever written, all at once, and all blamed on whatever
         * the player happened to open next.
         */
        const val DISK_HEADROOM_BYTES = 1_073_741_824L // 1 GiB

        /**
         * Below this much free memory right now, nothing is loaded.
         *
         * A pressure signal, not a capacity figure. `availMem` on a healthy phone with a model's
         * worth of room to spare still reads far below the model's size, because Android keeps
         * memory usefully full rather than usefully empty; comparing it against
         * [LocalModelVariant.estimatedResidentBytes] would refuse every load on every device
         * forever.
         */
        const val LOAD_FLOOR_BYTES = 536_870_912L // 512 MiB

        /**
         * The whole decision.
         *
         * Memory decides the tier, disk narrows it, and the two failures are reported separately
         * because only one of them is the player's to fix.
         */
        fun of(memory: DeviceMemory): LocalModelFit {
            val tier = DeviceTier.of(memory)
            val allowed = tier.holds.filter { memory.freeDiskBytes >= it.minFreeDiskBytes }
            val best = tier.largest
            val shortfall =
                if (best == null) 0L
                else (best.minFreeDiskBytes - memory.freeDiskBytes).coerceAtLeast(0L)
            return LocalModelFit(
                tier = tier,
                allowed = allowed,
                recommended = allowed.firstOrNull(),
                blocker = when {
                    allowed.isNotEmpty() -> null
                    memory.isLowRamDevice -> ModelBlocker.LOW_RAM_DEVICE
                    tier == DeviceTier.UNFIT -> ModelBlocker.NOT_ENOUGH_RAM
                    // The tier holds something and the disk is the only reason it is not on
                    // offer. This is the recoverable one, and it is why the two are separate.
                    else -> ModelBlocker.NOT_ENOUGH_DISK
                },
                freeUpBytes = shortfall,
            )
        }
    }
}
