package com.neopal.pet.data

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Decision
import com.neopal.pet.domain.DeviceMemory
import com.neopal.pet.domain.Lesson
import com.neopal.pet.domain.LocalEngine
import com.neopal.pet.domain.LocalEnginePlan
import com.neopal.pet.domain.LocalMindConfig
import com.neopal.pet.domain.LocalModelFit
import com.neopal.pet.domain.LocalModelVariant
import com.neopal.pet.domain.MindChoice
import com.neopal.pet.domain.MindProvider
import com.neopal.pet.domain.MindReply
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.Plan
import com.neopal.pet.domain.RunRecord
import com.neopal.pet.domain.ToolId
import java.io.File
import java.util.concurrent.Semaphore
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A brain that lives on the handset.
 *
 * The third [MindProvider], after `RemoteMindClient` and `NoMind`. It answers the same interface
 * and returns the same nullable results, so nothing in the game changes shape to accommodate it —
 * which was the whole argument in §3 of docs/CEREBRO-LOCAL.md for it being a safe thing to add.
 *
 * ---- what has never been verified, stated first --------------------------------------------
 *
 * Nothing in this file has been compiled. There is no Android SDK in this project's local harness
 * and Google Maven is blocked from it, so neither `androidx.lifecycle` nor
 * `com.google.ai.edge.litertlm` can even be resolved here; the first machine to compile it will be
 * CI, and the first machine to *run* it will be a phone. No model has been loaded, no token has
 * been generated, and no latency has been measured by anybody. Every call below was read at the
 * tag this project pins — v0.12.0 — and not at the publisher's HEAD, which is the mistake
 * c19de92 exists to record: `Backend.CPU`'s parameter is `numOfThreads` at 0.12.0 and
 * `threadCount` only from later, and naming the later one would be a red build with a one-line
 * diff and a long hunt behind it.
 *
 * The half that could be checked without a device is deliberately not in this file.
 * [OnDeviceWire] holds every decision about what goes into a prompt and what is made of a reply;
 * [LocalModelFit] holds the arithmetic about what this phone can hold; [LocalEngine] holds the
 * numbers this file builds an engine with and the choice of which file it opens; and [MindRouter]
 * holds the decision about which of the four questions below is ever asked here at all. All four
 * compile in the local harness and all four have tests. What is left here is lifetime and
 * ownership, which needs a real runtime to mean anything.
 *
 * ---- who decides what runs, and why it is not this file -------------------------------------
 *
 * An earlier draft of this class carried its own rule that the handset may only ever hold a
 * conversation, and refused the other three jobs from inside. That rule contradicted
 * `docs/CEREBRO-LOCAL.md` §4, which sends a decision, an errand and a distilled life to the phone
 * whenever there is no remote route to send them to, and `MindRouter` implements §4. Two rules
 * disagreeing about the same question is the shape this project keeps catching in itself, so
 * there is now one: **the router decides and this executes.** All four methods work. Which of
 * them is called is not this file's business.
 *
 * What did not change with it is the limit §5 calls a limit rather than a preference: **nothing
 * generates while nobody is watching.** That is held here structurally rather than by a rule that
 * could be argued with. An engine is only ever built in [onStart] and there is no path to
 * building one from any [MindProvider] method, so a caller with no started lifecycle has no
 * engine no matter what it asks for — and the autonomous half runs every few minutes with the app
 * closed, which is to say after [onStop]. A background caller does not get refused here; it finds
 * nothing loaded, and the written brain answers, which is what it has always done.
 *
 * **It dies with the screen.** Loading costs seconds, so it cannot happen per message; a resident
 * multi-gigabyte engine is heat and battery, so it cannot stay. The resolution is the one
 * [CreatureSpeaker] and [CreatureEars] already use — a [DefaultLifecycleObserver] that builds on
 * `ON_START` and gives everything back on `ON_STOP` — and it is deliberately that pattern rather
 * than a third one. The difference from the speaker is the direction of the laziness: the speaker
 * builds its engine on first use, and this one must not, because "on first use" is exactly how a
 * background caller ends up loading three gigabytes.
 */

/** How the on-device brain is doing. A screen can turn any of these into a sentence. */
sealed interface OnDeviceState {

    /** Nothing loaded and nothing being loaded. The state of every player who never asked for it. */
    data object Idle : OnDeviceState

    /**
     * The weights are being read. Seconds, not milliseconds — up to about ten on a large model.
     *
     * Worth showing, unlike the speaker's equivalent: the creature is visibly not answering with
     * its better brain yet, and a player who is not told why concludes the feature does not work.
     */
    data object Loading : OnDeviceState

    /** Loaded and answering. */
    data object Ready : OnDeviceState

    /**
     * It will not run, and [reason] is a sentence rather than a code.
     *
     * Two very different things arrive here and the difference is in the words: "this phone cannot
     * hold a brain this size" is permanent and the offer should be withdrawn, "there is not enough
     * free memory right now" is this minute only. [LocalModelFit] draws that line — [ModelBlocker]
     * for the permanent refusals and [LocalModelFit.loadableNow] for the passing one — and this
     * carries whichever sentence came out of it.
     */
    data class Unavailable(val reason: String) : OnDeviceState
}

/**
 * The creature thinking on the handset it lives on.
 *
 * Build it with the *application* context, as [CreatureSpeaker] requires for the same reason: this
 * outlives any one composition, and an engine holding an Activity is an Activity that cannot be
 * collected.
 *
 * [onStart], [onStop] and [close] must all be called from the main thread, which is where the
 * lifecycle delivers them. Everything expensive is moved off it from there, and everything that
 * comes back from a worker thread is posted to it before it touches any field. The four
 * [MindProvider] methods may be called from anywhere.
 */
class OnDeviceMindClient(
    context: Context,
    private val configProvider: () -> LocalMindConfig,
) : MindProvider, DefaultLifecycleObserver, AutoCloseable {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val store = ModelStore(appContext)

    private val _state = MutableStateFlow<OnDeviceState>(OnDeviceState.Idle)
    val state: StateFlow<OnDeviceState> = _state.asStateFlow()

    /**
     * One generation at a time, and no teardown while one is running.
     *
     * A plain [Semaphore] rather than a coroutine `Mutex`, because both kinds of caller need it:
     * the generation path holds it from inside a blocking JNI call, and the release path holds it
     * from an ordinary thread that has no coroutine to suspend. Closing an engine with a decode
     * still inside it is not an exception — it is a native crash in a process that is holding a
     * player's pet.
     */
    private val gate = Semaphore(1, true)

    /** The loaded engine, or null. Written only from the main thread; read from anywhere. */
    @Volatile
    private var engine: Engine? = null

    /** What that engine was built with, so a generation knows its own budget. */
    @Volatile
    private var loaded: LocalEnginePlan? = null

    /** The conversation a generation is currently inside, so it can be cut short from outside. */
    @Volatile
    private var inFlight: Conversation? = null

    /** True between `ON_START` and `ON_STOP`. The only thing that permits an engine to exist. */
    private var awake = false

    /** True while a load is on its way back. Stops `ON_START` twice from loading twice. */
    private var loading = false

    private var closed = false

    // ------------------------------------------------------------------ the provider

    override val isReady: Boolean get() = _state.value is OnDeviceState.Ready

    /**
     * The creature answers something the player said to it.
     *
     * Null for every reason there is: nothing loaded, nothing asked, a model that produced prose
     * where JSON was asked for, a decode that ran past its budget. Null means the written brain
     * answers, which is the ordinary case rather than a failure — see the KDoc on [MindProvider],
     * and see [MindRoute.OnDevice], whose `ifSilent` already names who covers. The player is never
     * shown an error for a brain they may not know is running.
     */
    override suspend fun speak(brief: PetBrief, history: List<ChatTurn>, message: String): MindReply? =
        ask(OnDeviceWire.conversation(brief, history, message)) { OnDeviceWire.answer(it) }

    /**
     * The creature picks among options the game has already ruled legal.
     *
     * Worth being explicit about why a one-billion-parameter model is allowed near this at all:
     * it is not choosing an action, it is choosing an index into a list the simulation has
     * already validated, and the pick is revalidated when it is executed. The worst a confused
     * reply can do is choose a legal thing badly, which is a creature with poor judgement — a
     * thing this game is allowed to have. That contract is [MindChoice]'s and it is the same
     * contract the remote route runs under.
     */
    override suspend fun choose(brief: PetBrief, options: List<Consideration>): MindChoice? =
        ask(OnDeviceWire.decision(brief, options)) { OnDeviceWire.choice(it, options) }

    /**
     * The creature sets itself an errand.
     *
     * **The returned plan is unstamped**, exactly as the remote route's is: `Errands.sanitise`
     * takes `madeAtSeconds` from whatever it is handed, this side has no clock, and `Plan.isStale`
     * measures from that field — so a caller that stores it without re-stamping it against the
     * creature's own age has stored a plan that expired before it arrived.
     */
    override suspend fun plan(
        brief: PetBrief,
        tools: Map<ToolId, String>,
        options: List<Consideration>,
    ): Plan? = ask(OnDeviceWire.errand(brief, tools, options)) { OnDeviceWire.errandOf(it) }

    /**
     * A finished life turned into what the next one inherits.
     *
     * An empty list on every failure, which is indistinguishable from a model that had nothing to
     * say — and neither is a loss: `Lineage.distilLocally` has already given the heir its
     * inheritance by the time this is asked, so this can only word it better or leave it alone.
     */
    override suspend fun distil(
        brief: PetBrief,
        record: RunRecord,
        decisions: List<Decision>,
    ): List<Lesson> =
        ask(OnDeviceWire.distillation(brief, record, decisions)) {
            OnDeviceWire.lessons(it, brief.generation)
        }.orEmpty()

    /**
     * One question, asked of whatever is loaded, within the budget it was loaded with.
     *
     * The four methods above differ only in which prompt goes in and which reader comes out, so
     * everything they share is here rather than copied four times: no engine means no answer, no
     * prompt means nothing was worth asking, and the clock covers the whole thing.
     *
     * The clock covering the *parse* as well as the decode is not tidiness. `MindWire.extractJson`
     * is a brace matcher that restarts its scan at every failed opener, so it is quadratic in the
     * number of openers, and a small model repeating itself is exactly how it gets a lot of them —
     * the local case is the *likelier* one, because looping here costs no quota and no network.
     * Inside the timeout it is bounded; on [Dispatchers.IO] it cannot freeze a screen even while
     * it is bounded.
     */
    private suspend fun <T : Any> ask(prompt: String?, read: (String?) -> T?): T? {
        // Read once. An engine handed back between here and the generation is handled by the gate
        // and by `isInitialized`, not by re-reading a field that may have changed underneath.
        val live = engine ?: return null
        val budget = loaded?.timeoutMillis ?: LocalEngine.TIMEOUT_MILLIS
        if (prompt == null) return null
        return withTimeoutOrNull(budget) {
            withContext(Dispatchers.IO) { read(generate(live, prompt)) }
        }
    }

    // ------------------------------------------------------------------ lifetime

    /**
     * The screen appeared, so the weights may be read.
     *
     * This is the only place in this file that builds an engine, and that is the structural half
     * of "nothing generates while nobody is watching": there is no path from any [MindProvider]
     * method to here, so nothing a background caller asks for can cause three gigabytes to be
     * loaded. It can only be handed an engine that a visible screen already built.
     */
    override fun onStart(owner: LifecycleOwner) {
        if (closed || awake) return
        awake = true
        if (engine != null || loading) return

        val config = configProvider()
        if (!config.enabled) {
            _state.value = OnDeviceState.Idle
            return
        }

        // Checked before loading, never after failing. Running out of memory here is not an
        // exception that can be caught: the kernel takes the process, the app disappears
        // mid-sentence, and to a player who has been raising this creature for a week that is
        // indistinguishable from it having died.
        val memory = readMemory()
        if (memory == null) {
            _state.value = OnDeviceState.Unavailable(UNREADABLE)
            return
        }
        val fit = LocalModelFit.of(memory)
        val variant = LocalEngine.chooseInstalled(config, fit, onDisk())
        if (variant == null) {
            // Nothing to load is the ordinary state of nearly every player, and it is silent.
            // A phone that could never hold one is a sentence, because Settings offers a
            // download that would be three gigabytes wasted.
            _state.value = fit.blocker
                ?.takeIf { config.hasModel }
                ?.let { OnDeviceState.Unavailable("${it.headline}. ${it.note}") }
                ?: OnDeviceState.Idle
            return
        }
        if (!fit.loadableNow(variant, memory)) {
            // A property of this minute rather than of the phone: six other apps open, or the
            // system already reclaiming. Deliberately a different sentence from the permanent
            // ones, because coming back later genuinely fixes it.
            _state.value = OnDeviceState.Unavailable(NOT_NOW)
            return
        }
        val file = fileFor(variant)
        if (file == null) {
            _state.value = OnDeviceState.Unavailable(LOAD_FAILED)
            return
        }

        _state.value = OnDeviceState.Loading
        loading = true
        startLoad(LocalEngine.plan(variant, cores()), file)
    }

    /**
     * The screen went away, so the engine does.
     *
     * `onStop` rather than `onPause`, matching [CreatureSpeaker]: a dialogue over the game is
     * still the game, and tearing down a multi-gigabyte engine because a permission prompt
     * appeared would mean paying the load again a second later.
     */
    override fun onStop(owner: LifecycleOwner) {
        release()
    }

    /** Permanent. For a `ViewModel` being cleared or a composition leaving for good. */
    override fun close() {
        closed = true
        release()
    }

    /**
     * Hands back whatever is loaded and forbids anything new.
     *
     * A load already on its way back is deliberately *not* interrupted. `Engine.initialize` is a
     * blocking native call that nothing here can shorten, so the only two outcomes available are
     * "let it finish and hand it straight back" and "abandon a multi-gigabyte allocation for the
     * life of the process". [settle] takes the first: it re-reads [awake] when the load lands and
     * releases an engine nobody is waiting for any more.
     */
    private fun release() {
        awake = false
        val live = engine
        engine = null
        loaded = null
        _state.value = OnDeviceState.Idle
        if (live == null) return
        // Cut short whatever is being generated before waiting on it. Without this the teardown
        // waits out a full decode of a reply nobody is on the screen to read. It is a best effort
        // and not a guarantee — a generation that has not yet published its conversation is missed
        // — but that one is bounded by its own timeout, which cancels it the same way.
        runCatching { inFlight?.cancelProcess() }
        handBack(live)
    }

    /**
     * Reads the weights on a thread of its own and comes back to the main one.
     *
     * A plain [Thread] rather than a coroutine, and that is the whole reason this is a method. A
     * cancelled coroutine resumes by throwing, so a load that was cancelled while blocked inside
     * `initialize` would return an engine to a continuation that never runs — a multi-gigabyte
     * allocation with nothing left holding a reference to close it. There is no cancelling this
     * thread, which is exactly the property wanted: whatever it builds, [settle] receives.
     */
    private fun startLoad(plan: LocalEnginePlan, modelPath: String) {
        Thread(
            {
                // Blocking, and up to about ten seconds of it. Never on the main thread; the
                // engine's own documentation says so and a frozen creature would say it louder.
                val built = buildEngine(plan, modelPath)
                main.post { settle(plan, built) }
            },
            "neopal-mind-load",
        ).start()
    }

    /** What became of a load. Main thread only: it is the only writer of [engine]. */
    private fun settle(plan: LocalEnginePlan, built: Engine?) {
        loading = false
        if (built == null) {
            // The design asks for a load that degrades rather than one that promises. Anything at
            // all can come back from a native initialiser reading a file the app downloaded — a
            // truncated download, an unsupported quantisation, a device whose ABI does not match.
            // All of it lands here and the creature carries on with the brain it already had.
            if (awake && !closed) _state.value = OnDeviceState.Unavailable(LOAD_FAILED)
            return
        }
        if (closed || !awake) {
            // The player left while it was loading. Ten seconds is long enough for that to be the
            // common case rather than the exotic one, and an engine nobody asked for any more is
            // exactly the resident heat this whole lifecycle exists to avoid.
            handBack(built)
            _state.value = OnDeviceState.Idle
            return
        }
        engine = built
        loaded = plan
        _state.value = OnDeviceState.Ready
    }

    /**
     * Gives an engine back, on a thread of its own, once nothing is inside it.
     *
     * Off the main thread because releasing native memory of this size is not instant, and on a
     * thread rather than a coroutine for the same reason [startLoad] is: a teardown that can be
     * cancelled halfway is a leak that lasts as long as the process.
     */
    private fun handBack(live: Engine) {
        Thread(
            {
                // Waits for a generation in flight rather than closing underneath it. The
                // conversation is closed by whoever opened it, in its own `finally`, and it must
                // go first: deleting the engine out from under a live conversation leaves that
                // conversation holding a handle to freed memory.
                gate.acquireUninterruptibly()
                try {
                    // `close` on an engine that is already closed throws rather than shrugging,
                    // which is a thing worth knowing about a lifecycle that has two entry points.
                    runCatching { live.close() }
                } finally {
                    gate.release()
                }
            },
            "neopal-mind-release",
        ).start()
    }

    // ------------------------------------------------------------------ the engine

    /**
     * Builds and initialises the engine, or returns null.
     *
     * The configuration here is deliberately the *smallest* surface that was read from Google's
     * own Kotlin sources at the tag this project pins, and that restraint is the point rather
     * than laziness: `EngineConfig` by named argument, `Backend.CPU`, `initialize`,
     * `createConversation()` with no arguments and `sendMessage(text)`. Nothing else.
     *
     * `Backend.CPU`'s parameter is named `numOfThreads` and not `threadCount`, and the difference
     * is not cosmetic: `threadCount` was introduced later, with `numOfThreads` deprecated beside
     * it, and at 0.12.0 naming it is a compile error. It is the exact failure this project keeps
     * having — a signature that is real in *a* version and not in *this* one — and the rule that
     * came out of it is that a signature is only verified against the version in the build file.
     *
     * Two things that are *not* used, and why:
     *
     *  - `Backend.GPU`. It needs two `uses-native-library` entries in the manifest, it fails in
     *    ways that depend on the driver, and no measurement exists saying it is faster for a
     *    creature that speaks two lines. CPU first; GPU is a decision for whoever has a phone.
     *  - `ConversationConfig(systemInstruction = …)` with `initialMessages`, which would let the
     *    character sheet and the transcript be real turns instead of the top of a text block.
     *    That one exists at the pinned version — it was checked — so this is a choice rather than
     *    a limit, and [OnDeviceWire] states the trade honestly.
     *
     * [EngineConfig.cacheDir] *is* used, and points at the system cache. That is not a
     * contradiction of §5 of the design, which insists the weights live on permanent private
     * storage: this is the engine's own derived scratch, and the worst that happens when Android
     * reclaims it is a slower next load. Losing a three-gigabyte download is what §5 is protecting
     * against, and that file is not this one — it is in `filesDir`, where [ModelStore] put it.
     */
    private fun buildEngine(plan: LocalEnginePlan, modelPath: String): Engine? {
        // Asked before the native layer is, because a missing file there is a JNI failure with
        // nothing useful in it, and this is the one cause a player can actually do something about.
        if (!runCatching { File(modelPath).isFile }.getOrDefault(false)) return null
        return runCatching {
            val built = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(numOfThreads = plan.threads),
                    maxNumTokens = plan.contextTokens,
                    cacheDir = engineCacheDir()?.absolutePath,
                ),
            )
            built.initialize()
            built
        }.getOrNull()
    }

    /**
     * Somewhere for the engine to keep whatever it derives from the weights.
     *
     * Null rather than a guess if it cannot be made: the engine treats a missing cache directory
     * as "do without", which costs a slower second load and nothing else.
     */
    private fun engineCacheDir(): File? = runCatching {
        File(appContext.cacheDir, ENGINE_CACHE_DIR).apply { mkdirs() }.takeIf { it.isDirectory }
    }.getOrNull()

    /**
     * One answer as raw text, or null, from inside a blocking native call.
     *
     * `suspendCancellableCoroutine` with the cancellation hook registered *before* the first
     * token, which is the shape `RemoteMindClient.post` arrived at the hard way. A hook that runs
     * on completion is no use: a thread inside a decode cannot complete, so the thing that would
     * end the decode ends up waiting on the decode. `cancelProcess` is the only call that reaches
     * a generation already running, and it has to be registered where a timeout can find it.
     *
     * The plain one-argument `resume` is deliberate and is not laziness.
     * `CancellableContinuation.resume(value, onCancellation)` takes a one-parameter handler in
     * coroutines 1.8.1 — which is what this module compiles against, arriving through
     * `androidx.lifecycle` — and a three-parameter one in 1.9.0, which is what the test source set
     * sees. Writing the newer form compiled locally and broke `:app:compileDebugKotlin`. It is
     * recorded in docs/PLAN.md and it is not going to happen twice.
     */
    private suspend fun generate(live: Engine, prompt: String): String? =
        suspendCancellableCoroutine { continuation ->
            // Held for the whole of this call, including the part spent blocked. Nothing else may
            // generate, and nothing may hand the engine back, until it is released below.
            gate.acquireUninterruptibly()
            var conversation: Conversation? = null
            try {
                if (!live.isInitialized()) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val fresh = live.createConversation()
                conversation = fresh
                inFlight = fresh
                continuation.invokeOnCancellation { runCatching { fresh.cancelProcess() } }

                // A fresh conversation per question rather than one held open across the screen.
                // The whole exchange is in the prompt already — the same thing the remote route
                // sends — so a conversation that also remembered it would be carrying it twice,
                // and a context window that grows all afternoon eventually pushes the question
                // being asked out of itself.
                continuation.resume(fresh.sendMessage(prompt).toString())
            } catch (ignored: Throwable) {
                // Deliberately total, exactly as the remote client's is. A native initialiser that
                // refuses, a conversation closed underneath us, a JNI exception, a cancellation
                // landing between two lines — every one of them ends in the same place, which is
                // the written brain answering and nobody being told. Resuming a continuation that
                // was already cancelled is a no-op.
                runCatching { continuation.resume(null) }
            } finally {
                inFlight = null
                // Before the gate is released, because the gate is what a teardown waits on, and
                // an engine closed while one of its conversations is still alive leaves that
                // conversation pointing at freed memory.
                conversation?.let { runCatching { it.close() } }
                gate.release()
            }
        }

    // ------------------------------------------------------------------ the handset

    /**
     * What this phone will admit about itself, or null when it will not say.
     *
     * Every field here is a reading; not one of them is a judgement. That split is the point of
     * [DeviceMemory] existing at all: the arithmetic that turns these numbers into a verdict is in
     * [LocalModelFit], where it can be checked against phones that do not exist in this room,
     * which is all of them.
     *
     * `memoryClass` is read even though it bounds the *Java* heap and a `.litertlm` is a native
     * allocation that the Java heap does not bound. It is not being used as a capacity test:
     * [LocalModelVariant.minMemoryClassMb] uses it to sort phones by vintage, which it does well,
     * and the capacity question is answered by the other two tests beside it.
     *
     * A reading that cannot be taken comes back as null, which refuses. That is the right
     * direction: a phone that will not say how much memory it has is not a phone to hand three
     * gigabytes to on the assumption it was being modest.
     */
    private fun readMemory(): DeviceMemory? = runCatching {
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        DeviceMemory(
            totalRamBytes = info.totalMem,
            availableRamBytes = info.availMem,
            memoryClassMb = manager.memoryClass,
            isLowRamDevice = manager.isLowRamDevice,
            // -1 when the platform will not say, which fails every disk comparison rather than
            // passing them. See ModelStore.usableBytes.
            freeDiskBytes = store.usableBytes(),
            lowMemoryNow = info.lowMemory,
        )
    }.getOrNull()

    /** Which variants are actually present as finished files. The fact the choice is made from. */
    private fun onDisk(): Set<LocalModelVariant> = runCatching {
        LocalModelVariant.entries
            .filter { variant -> LocalEngine.fetchableFor(variant)?.let { store.hasStored(it) } == true }
            .toSet()
    }.getOrDefault(emptySet())

    /** Where a variant's weights are, or null when this build cannot say where they would be. */
    private fun fileFor(variant: LocalModelVariant): String? = runCatching {
        LocalEngine.fetchableFor(variant)?.let { store.fileFor(it).absolutePath }
    }.getOrNull()

    /** What the runtime says about the processors, or zero — which [LocalEngine] treats as "unknown". */
    private fun cores(): Int = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(0)

    companion object {

        /** Under the system cache, not the permanent store. See [buildEngine]. */
        const val ENGINE_CACHE_DIR = "litertlm"

        /**
         * Shown when the weights are there and will not load.
         *
         * Says what the player can do about it, because the two realistic causes — a download that
         * finished badly, and a build shipped for an ABI this phone is not — end with the same
         * remedy and neither is worth naming on a screen.
         */
        const val LOAD_FAILED =
            "That brain would not start. Removing it and downloading it again usually sorts it out."

        /**
         * Shown when the phone could hold it but not this minute.
         *
         * Kept apart from the permanent refusals for the same reason [ModelBlocker] keeps
         * "not enough space" apart from "not enough memory": one of them is worth trying again
         * and the other is not, and a sentence that blurs them either tells somebody to give up
         * on something they could fix or leaves them retrying something that will never work.
         */
        const val NOT_NOW =
            "Not enough free memory just now. Closing a few other apps and coming back usually does it."

        /** Shown when the phone will not report its own memory at all. Rare, and refused. */
        const val UNREADABLE =
            "This phone will not say how much memory it has, so nothing is loaded rather than risking it."
    }
}
