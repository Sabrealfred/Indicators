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
import com.neopal.pet.domain.Lesson
import com.neopal.pet.domain.MindChoice
import com.neopal.pet.domain.MindProvider
import com.neopal.pet.domain.MindReply
import com.neopal.pet.domain.MindRole
import com.neopal.pet.domain.OnDeviceMemory
import com.neopal.pet.domain.OnDeviceMind
import com.neopal.pet.domain.OnDeviceMindConfig
import com.neopal.pet.domain.OnDeviceModel
import com.neopal.pet.domain.OnDeviceVerdict
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
 * been generated, and no latency has been measured by anybody. The API surface used below was read
 * from Google's own Kotlin sources rather than from memory, and it is deliberately the smallest
 * surface that does the job — see the comment on [buildEngine].
 *
 * The half that could be checked without a device was pushed out of this file on purpose:
 * [OnDeviceWire] holds every decision about what goes into the prompt and what is made of the
 * reply and compiles in the local harness, and [com.neopal.pet.domain.OnDeviceMind] holds the
 * arithmetic that decides whether the weights may be loaded at all. Both have test suites. What is
 * left here is lifetime and ownership, which needs a real runtime to mean anything.
 *
 * ---- the two rules this file exists to keep ------------------------------------------------
 *
 * **It is never on the autonomous loop.** §5 of the design calls that a limit rather than a
 * preference, and it is enforced twice over. [OnDeviceMind.SERVES] names the one job an on-device
 * model may be asked — conversation — so `choose`, `plan` and `distil` return a constant, and
 * there is no reference to [engine] anywhere in any of the three. And an engine is only ever built
 * from [onStart]: no [MindProvider] method can cause a load, so a caller with no screen has no
 * engine, whatever it asks for. The autonomous half runs every few minutes with the app closed,
 * which means [onStop] has already fired, which means there is nothing loaded for it to reach.
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
     * free memory right now" is this minute only. [OnDeviceVerdict] draws the line; this carries
     * whichever sentence it produced.
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
 * comes back from a worker thread is posted to it before it touches any field. [speak] may be
 * called from anywhere.
 */
class OnDeviceMindClient(
    context: Context,
    private val configProvider: () -> OnDeviceMindConfig,
) : MindProvider, DefaultLifecycleObserver, AutoCloseable {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

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
     * The creature answers, out of its own head.
     *
     * Null for every reason there is: nothing loaded, nothing asked, a model that produced prose
     * where JSON was asked for, a decode that ran past its budget. Null means the local `Brain`
     * answers, which is the ordinary case rather than a failure — see the KDoc on [MindProvider].
     * The player is never shown an error for a brain they may not know is running.
     */
    override suspend fun speak(brief: PetBrief, history: List<ChatTurn>, message: String): MindReply? {
        if (!OnDeviceMind.serves(MindRole.CONVERSE)) return null
        // Read once. An engine handed back between here and the generation is handled by the gate
        // and by `isInitialized`, not by re-reading a field that may have changed underneath.
        val live = engine ?: return null
        val config = configProvider().sanitised()
        if (!config.usable) return null
        val prompt = OnDeviceWire.conversation(brief, history, message) ?: return null

        // The clock covers the whole thing — prefill, decode and parse — because that is what the
        // player experiences. The parse in particular must be inside it and off the main thread:
        // `MindWire.extractJson` is quadratic in the number of open braces, and a small model
        // repeating itself is exactly how it gets a lot of them.
        return withTimeoutOrNull(config.timeoutMillis) {
            withContext(Dispatchers.IO) { generate(live, prompt) }
        }
    }

    /**
     * Refused, structurally.
     *
     * `choose` has exactly one call site in this app and it is the reconsider throttle: a timer
     * that runs every few minutes for as long as the process lives. §5 of docs/CEREBRO-LOCAL.md is
     * unambiguous that a model must never run there, and the rule is [OnDeviceMind.SERVES] rather
     * than a comment so that a machine holds it — `OnDeviceMindTest` asserts that set is exactly
     * one role, so widening it fails a test rather than warming somebody's phone.
     *
     * Note what is *not* the reason. The index-into-a-validated-list contract on [MindChoice] is
     * sound, and it is the thing that would make even a 1 B model safe to let decide: the worst a
     * confused reply can do is pick a legal option badly, which is a creature with poor judgement,
     * and this game is allowed to have one. That contract is not in doubt here. The schedule is.
     */
    override suspend fun choose(brief: PetBrief, options: List<Consideration>): MindChoice? = null

    /** Refused, structurally. The errand throttle is the other half of the autonomous loop. */
    override suspend fun plan(
        brief: PetBrief,
        tools: Map<ToolId, String>,
        options: List<Consideration>,
    ): Plan? = null

    /**
     * Refused, and this one is a judgement rather than a rule.
     *
     * Distilling runs once per life rather than on a timer, so it is not the autonomous loop. It
     * is refused anyway: it is the largest single generation the game asks for, it happens while
     * the player is looking at a hatching rather than at a conversation — so no engine is loaded
     * and one would have to be — and `Lineage.distilLocally` has already given the heir its
     * inheritance by then. Nothing is lost, and what is gained is that exactly one method on this
     * class has a path to the engine.
     */
    override suspend fun distil(
        brief: PetBrief,
        record: RunRecord,
        decisions: List<Decision>,
    ): List<Lesson> = emptyList()

    // ------------------------------------------------------------------ lifetime

    /**
     * The screen appeared, so the weights may be read.
     *
     * This is the only place in this file that builds an engine, and that is the structural half
     * of the "never on the autonomous loop" rule: there is no path from any [MindProvider] method
     * to here, so nothing a background caller asks for can cause three gigabytes to be loaded. It
     * can only be handed an engine that a visible screen already built.
     */
    override fun onStart(owner: LifecycleOwner) {
        if (closed || awake) return
        awake = true

        val config = configProvider().sanitised()
        val model = config.model
        if (!config.usable || model == null) {
            _state.value = OnDeviceState.Idle
            return
        }
        if (engine != null || loading) return

        // Checked before loading, never after failing. Running out of memory here is not an
        // exception that can be caught: the kernel takes the process, the app disappears
        // mid-sentence, and to a player who has been raising this creature for a week that is
        // indistinguishable from it having died.
        val verdict = OnDeviceMind.verdict(model, readMemory())
        if (verdict !is OnDeviceVerdict.Fits) {
            _state.value = OnDeviceState.Unavailable(reasonOf(verdict))
            return
        }

        _state.value = OnDeviceState.Loading
        loading = true
        startLoad(config, model)
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
    private fun startLoad(config: OnDeviceMindConfig, model: OnDeviceModel) {
        Thread(
            {
                // Blocking, and up to about ten seconds of it. Never on the main thread; the
                // engine's own documentation says so and a frozen creature would say it louder.
                val built = buildEngine(config, model.absolutePath)
                main.post { settle(built) }
            },
            "neopal-mind-load",
        ).start()
    }

    /** What became of a load. Main thread only: it is the only writer of [engine]. */
    private fun settle(built: Engine?) {
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
     * own Kotlin sources, and that restraint is the point rather than laziness. This project's
     * build has gone red three times on a signature that looked plausible and did not exist, and
     * `litertlm-android` is pinned to a version whose parameter lists could not be checked from
     * this environment. So: `EngineConfig` by named argument, `Backend.CPU`, `initialize`,
     * `createConversation()` with no arguments and `sendMessage(text)`. Nothing else.
     *
     * Two things that are *not* used, and why:
     *
     *  - `Backend.GPU`. It needs two `uses-native-library` entries in the manifest, it fails in
     *    ways that depend on the driver, and no measurement exists saying it is faster for a
     *    creature that speaks two lines. CPU first; GPU is a decision for whoever has a phone.
     *  - `ConversationConfig(systemInstruction = …)`, which would let the character sheet be a
     *    real system turn instead of the top of a text block. It is the nicer prompt and it is
     *    also a parameter list that could not be checked against the pinned version. [OnDeviceWire]
     *    documents what it does instead.
     *
     * [EngineConfig.cacheDir] *is* used, and points at the system cache. That is not a
     * contradiction of §5 of the design, which insists the weights live on permanent private
     * storage: this is the engine's own derived scratch, and the worst that happens when Android
     * reclaims it is a slower next load. Losing a three-gigabyte download is what §5 is protecting
     * against, and that file is not this one.
     */
    private fun buildEngine(config: OnDeviceMindConfig, modelPath: String): Engine? {
        // Asked before the native layer is, because a missing file there is a JNI failure with
        // nothing useful in it, and this is the one cause a player can actually do something about.
        if (!runCatching { File(modelPath).isFile }.getOrDefault(false)) return null
        return runCatching {
            val built = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(threadCount = threadCount()),
                    maxNumTokens = config.contextTokens,
                    cacheDir = engineCacheDir()?.absolutePath,
                ),
            )
            built.initialize()
            built
        }.getOrNull()
    }

    /** Half the cores, never all of them. The rule and the reasoning are in [OnDeviceMind]. */
    private fun threadCount(): Int =
        OnDeviceMind.threadsFor(runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(0))

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
     * One answer, or null, from inside a blocking native call.
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
    private suspend fun generate(live: Engine, prompt: String): MindReply? =
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
                val said = fresh.sendMessage(prompt).toString()
                continuation.resume(OnDeviceWire.answer(said))
            } catch (ignored: Throwable) {
                // Deliberately total, exactly as the remote client's is. A native initialiser that
                // refuses, a conversation closed underneath us, a JNI exception, a cancellation
                // landing between two lines — every one of them ends in the same place, which is
                // the local brain answering and nobody being told. Resuming a continuation that
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
     * What this phone will admit about its own memory.
     *
     * `MemoryInfo` gives what is free and where the system's own killing threshold sits;
     * `isLowRamDevice` is the manufacturer saying the handset was built without headroom at all.
     *
     * `memoryClass` is deliberately *not* consulted, though §5 of the design mentions it. It is
     * the ceiling on the Java heap, and a `.litertlm` is not on the Java heap — the weights are a
     * native allocation, bounded by the process and the kernel rather than by the runtime.
     * Refusing a load because the Java heap class is small would rule out handsets that can run it
     * perfectly well, and passing one because it is large would prove nothing.
     *
     * A reading that cannot be taken comes back as [OnDeviceMemory.UNKNOWN], which refuses.
     */
    private fun readMemory(): OnDeviceMemory = runCatching {
        val manager = appContext.getSystemService(ActivityManager::class.java)
            ?: return OnDeviceMemory.UNKNOWN
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        OnDeviceMemory(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            thresholdBytes = info.threshold,
            lowMemoryNow = info.lowMemory,
            lowRamDevice = manager.isLowRamDevice,
        )
    }.getOrDefault(OnDeviceMemory.UNKNOWN)

    /** The sentence a refusal carries. Both kinds have one; only their permanence differs. */
    private fun reasonOf(verdict: OnDeviceVerdict): String = when (verdict) {
        is OnDeviceVerdict.TooBig -> verdict.reason
        is OnDeviceVerdict.NotNow -> verdict.reason
        OnDeviceVerdict.Fits -> LOAD_FAILED
    }

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
    }
}
