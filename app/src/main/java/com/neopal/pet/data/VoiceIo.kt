package com.neopal.pet.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.neopal.pet.domain.CreatureVoice
import com.neopal.pet.domain.Utterance
import com.neopal.pet.domain.VoiceChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/*
 * The voice layer: the creature speaking, and the player speaking back.
 *
 * ---- what happens to audio, in one place, so it can be checked quickly --------------------
 *
 * Speaking costs nothing and asks for nothing. Text-to-speech needs no permission, no key and no
 * network; the text goes to the engine already on the device and comes out of the speaker. No
 * file is written and nothing leaves the handset.
 *
 * Listening is the half with a price. This file never opens a microphone, never receives an audio
 * buffer, and never holds one: it hands [SpeechRecognizer] an Intent and is given back a list of
 * candidate *strings*. Recording, and whatever the device's recognition service does with the
 * recording, belongs to that service — on most handsets it is Google's, and unless it is running
 * on the device it will process the audio on Google's servers under Google's policy, which is not
 * something this app can promise anything about. What this app can promise is what it does with
 * the words it gets back, and that is: nothing except put them where a typed message would have
 * gone. They are not written to the save, not logged, and not sent anywhere — with one exception,
 * which is the exception the player switched on themselves. If the remote brain is enabled, a
 * recognised sentence travels to the configured endpoint exactly as a typed one does, in the same
 * request, with the same brief. There is no second path, and searching this file for a network
 * call will find none.
 *
 * [VoiceConfig.preferOnDevice] asks the recogniser to stay on the handset. It is a request, not a
 * guarantee: an engine that cannot honour it either falls back or fails, and the failure surfaces
 * as an ordinary [Heard] outcome rather than as silence.
 *
 * ---- what has never been verified ---------------------------------------------------------
 *
 * Nothing in this file has ever been compiled. There is no Android SDK in this project's local
 * harness and Google Maven is blocked, so `android.speech.*` cannot even be resolved here; the
 * first machine to compile it will be CI. No sound it produces has been heard by anyone. The half
 * that could be checked without a device was deliberately pushed out of this file and into
 * [CreatureVoice], which is pure Kotlin and has a test suite.
 */

/** How the engine that reads lines out is doing. The settings screen says which of these it is. */
sealed interface SpeakerState {
    /** No engine exists. The state a player who never switches the voice on stays in forever. */
    data object Idle : SpeakerState

    /** An engine is being built. Lines that arrive now wait, briefly; see [CreatureSpeaker.say]. */
    data object Starting : SpeakerState

    data object Ready : SpeakerState

    /**
     * This device cannot read anything out, and no amount of retrying will change that.
     * [reason] is a sentence for the screen, because "voice unavailable" tells nobody anything.
     */
    data class Unavailable(val reason: String) : SpeakerState
}

/**
 * The creature's mouth.
 *
 * Deliberately lazy: no [TextToSpeech] is constructed until the first line is actually spoken, so
 * a player who leaves the voice switched off never binds a speech service at all. Deliberately
 * disposable in the other direction too — [release] tears the engine down, and the lifecycle
 * observer calls it when the screen goes away, so nothing is held while the app is not visible.
 *
 * Build it with the *application* context. It outlives any one screen, and an engine holding an
 * Activity is an Activity that cannot be collected.
 *
 * Everything here must be called from the main thread. Engine callbacks arrive on a binder thread
 * and are posted back before they touch any of this object's state.
 */
class CreatureSpeaker(context: Context) : DefaultLifecycleObserver, AutoCloseable {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    val state: StateFlow<SpeakerState> = _state.asStateFlow()

    private var engine: TextToSpeech? = null

    /**
     * The line waiting for an engine that is still starting, and when it arrived.
     *
     * A backlog of exactly one, expiring after [STARTUP_GRACE_MS]. Both halves of that matter. An
     * unbounded queue means a creature that spends a minute working through what it thought about
     * a screen the player has already left; no queue at all means the first line after the voice
     * is switched on — the one that proves the feature works — is always the one that is lost.
     */
    private var pending: Utterance? = null
    private var pendingVoice: CreatureVoice? = null
    private var pendingVolume: Float = 1f
    private var pendingAtMillis: Long = 0L

    /** What is being spoken right now, so a lesser line cannot take the mouth from a greater one. */
    private var speaking: VoiceChannel? = null

    /** The last line actually started. Repeats are dropped; see [Utterance.key]. */
    private var lastKey: String? = null

    private var closed = false

    /**
     * Reads [utterance] out in [voice], at [masterVolume] (the player's slider, 0..1).
     *
     * Silently does nothing when there is nothing to do — the same key twice, a volume of zero, a
     * device with no engine, a quieter line arriving over a louder one. None of those are errors
     * and none of them get a message: the player asked for a creature that talks, not for a report
     * on the occasions it did not.
     */
    fun say(utterance: Utterance, voice: CreatureVoice, masterVolume: Float) {
        if (closed) return
        val volume = (voice.volume * masterVolume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        if (volume <= 0.01f) return
        if (utterance.key == lastKey) return
        val current = _state.value
        if (current is SpeakerState.Unavailable) return
        // A diary line must not cut off a reply the player is waiting for. Equal ranks do cut in;
        // the newest line of a kind is the one worth hearing. A line still waiting for the engine
        // holds the mouth exactly as a line being spoken does — otherwise the one case this grace
        // exists for, the first reply after the voice is switched on, is the one a passing diary
        // line is free to shoulder aside.
        val incumbent = speaking ?: pending?.channel
        if (!utterance.channel.interrupts(incumbent)) return

        val tts = engine
        if (tts == null || current !is SpeakerState.Ready) {
            pending = utterance
            pendingVoice = voice
            pendingVolume = volume
            pendingAtMillis = SystemClock.elapsedRealtime()
            if (current is SpeakerState.Idle) start()
            return
        }
        speakNow(tts, utterance, voice, volume)
    }

    private fun speakNow(tts: TextToSpeech, utterance: Utterance, voice: CreatureVoice, volume: Float) {
        lastKey = utterance.key
        speaking = utterance.channel
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val result = runCatching {
            tts.setPitch(voice.pitch)
            tts.setSpeechRate(voice.rate)
            // FLUSH, always. QUEUE_ADD is how a voice feature turns into a creature narrating a
            // screen from four minutes ago; the guard above has already decided this line is the
            // one worth hearing, so the one it replaces is not.
            tts.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, params, utterance.key)
        }.getOrElse { TextToSpeech.ERROR }
        if (result == TextToSpeech.ERROR) speaking = null
    }

    /** Stops mid-sentence. Cheap, safe to call when nothing is being said. */
    fun stop() {
        pending = null
        pendingVoice = null
        speaking = null
        runCatching { engine?.stop() }
    }

    /**
     * Stops and gives the engine back.
     *
     * Called when the screen goes away, so a backgrounded game holds no speech service. The next
     * line spoken builds a new engine; [STARTUP_GRACE_MS] is what stops that costing an utterance.
     */
    fun release() {
        stop()
        val tts = engine
        engine = null
        _state.value = SpeakerState.Idle
        // Not clearing the progress listener first: `shutdown` is what stops the callbacks, and
        // a late one finds `engine` already null and does nothing.
        runCatching { tts?.shutdown() }
    }

    /** Permanent. For [androidx.lifecycle.ViewModel.onCleared], where the owner is going for good. */
    override fun close() {
        closed = true
        release()
    }

    /**
     * Speech stops with the screen.
     *
     * `onStop` rather than `onPause`: a dialogue over the game is still the game, and cutting the
     * creature off mid-sentence because a permission prompt appeared is exactly the kind of thing
     * that makes a voice feature feel broken.
     */
    override fun onStop(owner: LifecycleOwner) {
        release()
    }

    private fun start() {
        if (closed || engine != null) return
        _state.value = SpeakerState.Starting
        // The listener fires on a binder thread; everything it does is posted to the main thread
        // first, because it lands in the middle of this object's state.
        val tts = TextToSpeech(appContext) { status -> main.post { onEngineReady(status) } }
        engine = tts
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = clearMouth(utteranceId)

                @Deprecated("Kept because the platform still calls it on older engines.")
                override fun onError(utteranceId: String?) = clearMouth(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = clearMouth(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = clearMouth(utteranceId)
            })
        }
    }

    /**
     * The mouth is free again — but only if the line that finished is the one that had it.
     *
     * A flushed utterance reports its ending *after* its replacement has started, so clearing
     * unconditionally would hand the mouth away while the new line is still being spoken, and the
     * priority rule would stop holding exactly when two lines are competing for it.
     */
    private fun clearMouth(utteranceId: String?) {
        main.post { if (utteranceId == null || utteranceId == lastKey) speaking = null }
    }

    private fun onEngineReady(status: Int) {
        val tts = engine ?: return
        if (closed) return release()
        if (status != TextToSpeech.SUCCESS) {
            engine = null
            _state.value = SpeakerState.Unavailable("This device has no voice to lend $CREATURE_WORD.")
            runCatching { tts.shutdown() }
            return
        }

        // A default locale the engine has no data for is the common failure, and it is not fatal:
        // the words are English either way, so falling back to English says them rather than
        // saying nothing. Only when neither works is there genuinely no voice here.
        val languages = listOf(Locale.getDefault(), Locale.US, Locale.ENGLISH)
        val accepted = languages.firstOrNull { locale ->
            val outcome = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            outcome != TextToSpeech.LANG_MISSING_DATA && outcome != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (accepted == null) {
            engine = null
            _state.value = SpeakerState.Unavailable("No voice data is installed for this language.")
            runCatching { tts.shutdown() }
            return
        }

        _state.value = SpeakerState.Ready

        val waiting = pending
        val voice = pendingVoice
        val fresh = SystemClock.elapsedRealtime() - pendingAtMillis <= STARTUP_GRACE_MS
        pending = null
        pendingVoice = null
        if (waiting != null && voice != null && fresh) speakNow(tts, waiting, voice, pendingVolume)
    }

    companion object {
        /**
         * How long a line may wait for an engine that is starting up.
         *
         * Long enough to cover a cold start, short enough that nobody hears a reply to a question
         * they have already given up on.
         */
        const val STARTUP_GRACE_MS = 2_500L

        /** Used only in the "no voice here" sentence, where naming the creature would read oddly. */
        private const val CREATURE_WORD = "your creature"
    }
}

/**
 * What came of listening. Every one of these is a sentence the screen can show.
 *
 * There is no null and no exception in this hierarchy on purpose. Recognition fails constantly and
 * for ordinary reasons — a quiet room, a passing bus, a device with no recogniser, a player who
 * would rather not hand over the microphone — and each of those deserves its own honest line. A
 * feature that answers all of them with nothing at all teaches the player that the button is
 * broken, which is worse than any of the actual outcomes.
 */
sealed interface Heard {
    /** A sentence for the player to see. Never blank, never an error code. */
    val message: String

    /** Words. The only outcome that carries anything the recogniser produced. */
    data class Words(val text: String) : Heard {
        override val message: String get() = text
    }

    /** The microphone was open and nothing in it was a sentence. The most common outcome by far. */
    data object Unclear : Heard {
        override val message: String get() = "I did not catch that."
    }

    /** Silence until the recogniser gave up waiting. */
    data object Silence : Heard {
        override val message: String get() = "I did not hear anything."
    }

    /**
     * The microphone was not granted. Not an error: an answer.
     *
     * The wording matters more here than anywhere else in this file. A player who declined has not
     * done anything wrong, the game is complete without it, and the message says so instead of
     * asking again.
     */
    data object Declined : Heard {
        override val message: String get() = "Typing works just as well. Nothing needs the microphone."
    }

    /** No recognition service on this device. Nothing the player can do, so do not ask them to. */
    data object Unavailable : Heard {
        override val message: String get() = "This device has no speech recognition to use."
    }

    /** Something else is already listening. Transient; trying again in a moment usually works. */
    data object Busy : Heard {
        override val message: String get() = "Something else is using the microphone."
    }

    /** Everything else, already turned into a sentence. */
    data class Failed(override val message: String) : Heard
}

/** Whether the microphone is open, and how loud it is, so the screen can prove it is live. */
data class EarsState(val listening: Boolean = false, val level: Float = 0f)

/**
 * The player's voice, on its way to becoming the same text a typed message would have been.
 *
 * Opt-in twice over: the setting is off until switched on, and switching it on does not grant
 * anything — [PERMISSION] is asked for at the moment it is first needed and declining is a
 * complete, supported answer that leaves every other way of talking to the creature working.
 *
 * The recogniser is a main-thread object; [listen], [cancel] and [close] must all be called from
 * there. Its callbacks already arrive on the main thread.
 */
class CreatureEars(context: Context) : DefaultLifecycleObserver, AutoCloseable {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(EarsState())
    val state: StateFlow<EarsState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onHeard: ((Heard) -> Unit)? = null
    private var closed = false

    /** True when the player has already granted the microphone. */
    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Listens once, then delivers exactly one [Heard] to [onResult].
     *
     * Exactly one, whatever happens, including the cases where the recogniser says nothing back:
     * a caller that has shown "listening" needs to know when to stop showing it, and "the callback
     * that never came" is the failure mode that leaves a microphone icon pulsing forever.
     *
     * [preferOnDevice] asks for recognition that does not leave the handset. Honouring it is up to
     * the recogniser.
     */
    fun listen(preferOnDevice: Boolean, onResult: (Heard) -> Unit) {
        if (closed) return onResult(Heard.Unavailable)
        if (!hasPermission()) return onResult(Heard.Declined)
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return onResult(Heard.Unavailable)

        // Any previous attempt is over as far as its caller is concerned; drop its callback before
        // building a new one so a late error from the old recogniser cannot answer the new call.
        cancel()

        val engine = runCatching { createRecognizer(preferOnDevice) }.getOrNull()
        if (engine == null) return onResult(Heard.Unavailable)
        recognizer = engine
        onHeard = onResult

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = EarsState(listening = true, level = 0f)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The recogniser reports roughly −2..10 dB. Normalised here so the screen can show
                // a level without knowing that, which is the only reason this value is exposed:
                // an open microphone that shows nothing is indistinguishable from a broken one.
                if (_state.value.listening) {
                    _state.value = EarsState(true, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Deliberately empty, and deliberately not removed. This is the one callback that
                // would hand this app raw audio, and a reader checking the claim at the top of the
                // file should be able to see that it is ignored rather than absent.
            }

            override fun onEndOfSpeech() {
                _state.value = EarsState(listening = false, level = 0f)
            }

            override fun onError(error: Int) = finish(errorToHeard(error))

            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                finish(if (best == null) Heard.Unclear else Heard.Words(best.trim()))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Partial results are not shown. They rewrite themselves two or three times a
                // sentence, and a message the creature has not been asked yet flickering in the
                // composer reads as the app guessing at the player rather than listening to them.
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // One candidate. Nothing here ranks alternatives, so asking for five is asking the
            // service to do work whose result is thrown away.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        val started = runCatching {
            _state.value = EarsState(listening = true, level = 0f)
            engine.startListening(intent)
        }.isSuccess
        if (!started) finish(Heard.Unavailable)
    }

    /**
     * The on-device recogniser where the platform has one and the player asked for it.
     *
     * The fallback is not a lesser feature, it is a different privacy story, and it is the one
     * documented at the top of this file. Availability is checked rather than assumed because a
     * device can report the API and still have no on-device model installed.
     */
    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer {
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(appContext)
    }

    /** Delivers the one result and takes the recogniser down. Later callbacks find nobody home. */
    private fun finish(result: Heard) {
        val callback = onHeard
        onHeard = null
        _state.value = EarsState()
        destroyRecognizer()
        callback?.invoke(result)
    }

    /** Stops listening without answering. The caller asked; it does not need to be told. */
    fun cancel() {
        onHeard = null
        _state.value = EarsState()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        val engine = recognizer
        recognizer = null
        // `onHeard` is already null by the time this runs, so a callback that arrives between
        // these two lines has nobody to answer and nothing to do.
        runCatching {
            engine?.cancel()
            engine?.destroy()
        }
    }

    /** The microphone closes with the screen. There is no case for listening to a background app. */
    override fun onStop(owner: LifecycleOwner) {
        cancel()
    }

    override fun close() {
        closed = true
        cancel()
    }

    /**
     * Turns a recogniser error code into something worth showing.
     *
     * The split that matters is between "nothing was said", which is ordinary and should read as
     * ordinary, and "this cannot work", which the player needs to stop retrying. Codes added after
     * API 24 are not named here — the list has grown three times — and land in the last branch,
     * which is a sentence rather than a number.
     */
    private fun errorToHeard(error: Int): Heard = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> Heard.Unclear
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Silence
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.Declined
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Heard.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            Heard.Failed("Speech recognition could not reach the network.")
        SpeechRecognizer.ERROR_AUDIO -> Heard.Failed("The microphone could not be read.")
        SpeechRecognizer.ERROR_SERVER -> Heard.Failed("The speech service turned that down.")
        SpeechRecognizer.ERROR_CLIENT -> Heard.Failed("Speech recognition stopped unexpectedly.")
        else -> Heard.Failed("Speech recognition is not working on this device.")
    }

    companion object {
        /** The one permission this feature needs, named here so call sites do not spell it out. */
        const val PERMISSION: String = Manifest.permission.RECORD_AUDIO

        /**
         * True when this device could recognise speech at all.
         *
         * Worth asking before the permission is: a prompt for a microphone that leads nowhere is
         * a prompt that should never have been shown.
         */
        fun availableOn(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context.applicationContext) }
                .getOrDefault(false)
    }
}
