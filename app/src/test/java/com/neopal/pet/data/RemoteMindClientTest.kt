package com.neopal.pet.data

import com.neopal.pet.domain.ActivityKind
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Errands
import com.neopal.pet.domain.LessonKind
import com.neopal.pet.domain.Lineage
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.MindRole
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.Species
import com.neopal.pet.domain.ToolId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the remote brain that does not need a socket, which is also the half that can hurt
 * somebody. Everything here is a rule about untrusted input: the reply is model output relayed
 * over a network by a host the player typed into a settings box, and the tests worth having are
 * the ones that ask what happens when it lies.
 */
class RemoteMindClientTest {

    /**
     * A grown creature to stamp plans against.
     *
     * Needed because policy moved: [Errands.sanitise] takes the creature rather than a number of
     * seconds, so that a plan can only ever be dated by something that knows what time it is.
     */
    private fun petForPlans(): PetState = Simulation
        .advance(Simulation.newGame("T", Species.LEAF, 1_000_000L), 1_120_000L, GameConfig.Default)
        .state
        .copy(stage = LifeStage.ADULT, ageSeconds = 10_800L)


    private val brief = PetBrief(
        name = "Mossling",
        species = "Leafling",
        stage = "Child",
        branch = "Balanced",
        personality = "Curious",
        ageDays = 4,
        generation = 3,
        satiety = 23,
        happiness = 61,
        energy = 44,
        hygiene = 80,
        health = 91,
        bond = 55,
        intellect = 31,
        isSick = false,
        isSleeping = false,
        skills = listOf("Feeding itself"),
        company = listOf("Bramble (friend)"),
        recentDiary = listOf("Woke up hungry."),
        lastDecision = "eating: I was down to 23%.",
        inheritedLessons = listOf("My parent waited too long to eat. I do not wait."),
    )

    private val options = listOf(
        Consideration(ActivityKind.EAT, 0.81f, "I was down to 23% and there was a berry in the tin.", null),
        Consideration(ActivityKind.SLEEP, 0.40f, "The lights were off.", "it is broad daylight"),
        Consideration(ActivityKind.IDLE, 0.12f, "Nothing wanted me.", null),
    )

    private val keyedConfig = MindConfig(
        enabled = true,
        baseUrl = "https://example.invalid/api/v1",
        apiKey = "sk-secret-do-not-leak-0123456789",
        model = "some/model:free",
    )

    // ------------------------------------------------------------------ what leaves the device

    @Test
    fun `the brief tells the model how the creature actually feels`() {
        val text = MindWire.briefJson(brief).toString()
        assertTrue("the creature has to know its own name", text.contains("Mossling"))
        assertTrue("hunger is the whole reason it would say anything", text.contains("23"))
        assertTrue(text.contains("Leafling"))
        assertTrue(text.contains("Bramble"))
        assertTrue(text.contains("Woke up hungry."))
    }

    @Test
    fun `the request body never carries the player's key`() {
        val messages = listOf(
            RemoteMindClient.Message("system", MindWire.speakSystemPrompt(brief)),
            RemoteMindClient.Message("user", "are you all right?"),
        )
        val body = MindWire.requestBody(keyedConfig, messages)

        assertTrue("the prompt is still the prompt", body.contains("Mossling"))
        assertTrue(body.contains("some/model:free"))
        assertFalse("a key in the body would be a key in every log and crash report",
            body.contains(keyedConfig.apiKey))
        assertFalse(body.contains("sk-secret"))
        assertFalse("nor should it ever be phrased as a header inside the body",
            body.contains("Authorization"))
    }

    // ---- a model per job -----------------------------------------------------------------

    @Test
    fun `one model means every job goes to it`() {
        val messages = listOf(RemoteMindClient.Message("user", "hello"))
        MindRole.entries.forEach { role ->
            assertTrue(
                "$role should use the only model configured",
                MindWire.requestBody(keyedConfig, messages, role).contains("some/model:free"),
            )
        }
    }

    @Test
    fun `a quick model takes the deciding and nothing else`() {
        val split = keyedConfig.copy(quickModel = "tiny/model:free")
        val messages = listOf(RemoteMindClient.Message("user", "hello"))

        assertTrue(
            "deciding runs many times an hour; that is the one to make cheap",
            MindWire.requestBody(split, messages, MindRole.DECIDE).contains("tiny/model:free"),
        )
        listOf(MindRole.CONVERSE, MindRole.PLAN, MindRole.DISTIL).forEach { role ->
            val body = MindWire.requestBody(split, messages, role)
            assertTrue("$role is where a big model earns its keep", body.contains("some/model:free"))
            assertFalse("$role must not be quietly downgraded", body.contains("tiny/model:free"))
        }
    }

    @Test
    fun `deciding is given a smaller reply budget than talking`() {
        assertTrue(keyedConfig.maxTokensFor(MindRole.DECIDE) < keyedConfig.maxTokensFor(MindRole.CONVERSE))
    }

    @Test
    fun `a tiny configured budget is never divided down to unusable`() {
        // The share is a fraction, and a fraction of a small number is a reply cut off mid-word.
        val mean = keyedConfig.copy(maxTokens = 40)
        assertTrue(mean.maxTokensFor(MindRole.DECIDE) >= MindConfig.MIN_TOKENS)
        MindRole.entries.forEach { assertTrue(mean.maxTokensFor(it) > 0) }
    }

    @Test
    fun `naming the same model twice is not a split`() {
        assertFalse("the settings screen should not claim a split that does nothing",
            keyedConfig.copy(quickModel = keyedConfig.model).splitsModels)
        assertFalse(keyedConfig.splitsModels)
        assertTrue(keyedConfig.copy(quickModel = "tiny/model:free").splitsModels)
    }

    @Test
    fun `no prompt mentions the key or anything about the player`() {
        val prompts = listOf(
            MindWire.speakSystemPrompt(brief),
            MindWire.chooseSystemPrompt(brief),
            MindWire.chooseUserPrompt(brief, options),
            MindWire.distilSystemPrompt(),
        )
        for (prompt in prompts) {
            assertFalse(prompt.contains(keyedConfig.apiKey))
            assertFalse(prompt.contains("sk-secret"))
        }
    }

    @Test
    fun `a player's own key wins over the shared service`() {
        val both = keyedConfig.copy(proxyUrl = "https://shared.invalid")
        val route = MindWire.routeOf(both)
        assertNotNull(route)
        assertEquals("https://example.invalid/api/v1/chat/completions", route!!.endpoint)
        assertEquals(keyedConfig.apiKey, route.bearer)

        val proxyOnly = MindConfig(enabled = true, proxyUrl = "https://shared.invalid/", apiKey = "")
        val proxied = MindWire.routeOf(proxyOnly)
        assertNotNull(proxied)
        assertEquals("https://shared.invalid/chat/completions", proxied!!.endpoint)
        assertNull("the shared route must never be handed a key", proxied.bearer)

        assertNull("an unusable config has no route at all", MindWire.routeOf(MindConfig()))
    }

    @Test
    fun `an endpoint the player already completed is not completed twice`() {
        assertEquals(
            "https://example.invalid/v1/chat/completions",
            MindWire.chatEndpoint("https://example.invalid/v1/chat/completions"),
        )
    }

    // ------------------------------------------------------------------ digging JSON out of prose

    @Test
    fun `json survives a fenced block with prose on both sides`() {
        val raw = """
            Sure! Here is what Mossling would say:

            ```json
            {"say": "I am starving, you know.", "warmth": 0.4}
            ```

            Let me know if you would like a different tone.
        """.trimIndent()
        val reply = MindWire.interpretReply(raw)
        assertNotNull(reply)
        assertEquals("I am starving, you know.", reply!!.text)
        assertEquals(0.4f, reply.warmth, 0.001f)
    }

    @Test
    fun `braces inside the creature's own words do not end the object early`() {
        val raw = """Here you go: {"say": "They left the tin {empty} again.", "warmth": -0.2} — cheers!"""
        val reply = MindWire.interpretReply(raw)
        assertNotNull(reply)
        assertEquals("They left the tin {empty} again.", reply!!.text)
    }

    @Test
    fun `a stray brace in the preamble does not stop the real object being found`() {
        val raw = """I thought about { how to answer and settled on {"say": "Hungry.", "warmth": 0.1}"""
        assertEquals("Hungry.", MindWire.interpretReply(raw)?.text)
    }

    @Test
    fun `malformed and empty replies yield nothing rather than throwing`() {
        val duds = listOf(
            "",
            "   ",
            "I am sorry, I cannot help with that.",
            "{",
            "{\"say\":",
            "```json\n```",
            "[]",
            "{\"say\": \"\"}",
            "{\"say\": null}",
            "{\"warmth\": 0.5}",
        )
        for (dud in duds) {
            assertNull("a reply of <$dud> must not become speech", MindWire.interpretReply(dud))
            assertNull(MindWire.interpretChoice(dud, options))
            assertTrue(MindWire.interpretLessons(dud, 3).isEmpty())
        }
    }

    @Test
    fun `an over-long reply is capped before anybody sees it`() {
        val flood = "la ".repeat(4_000)
        val reply = MindWire.interpretReply("""{"say": "$flood"}""")
        assertNotNull(reply)
        assertEquals(MindWire.MAX_REPLY_CHARS, reply!!.text.length)
    }

    @Test
    fun `warmth is clamped to the band the game will act on`() {
        assertEquals(1f, MindWire.interpretReply("""{"say":"hi","warmth": 99}""")!!.warmth, 0.001f)
        assertEquals(-1f, MindWire.interpretReply("""{"say":"hi","warmth": -8.5}""")!!.warmth, 0.001f)
        assertEquals(0f, MindWire.interpretReply("""{"say":"hi","warmth": "lots"}""")!!.warmth, 0.001f)
    }

    // ------------------------------------------------------------------ choosing

    @Test
    fun `a legal choice is taken, with the model's own reason`() {
        val choice = MindWire.interpretChoice("""{"index": 0, "reason": "I could not think past it."}""", options)
        assertNotNull(choice)
        assertEquals(0, choice!!.index)
        assertEquals("I could not think past it.", choice.reason)
    }

    @Test
    fun `an index outside the options is refused`() {
        for (bad in listOf(3, 47, -1, 1000)) {
            assertNull(
                "index $bad is not on the menu and must never reach the simulation",
                MindWire.interpretChoice("""{"index": $bad, "reason": "why not"}""", options),
            )
        }
        assertNull(MindWire.interpretChoice("""{"index": 0}""", emptyList()))
    }

    @Test
    fun `an option the rules blocked cannot be chosen`() {
        val blocked = options.indexOfFirst { it.blockedBy != null }
        assertTrue("the fixture has to contain a blocked option for this to mean anything", blocked >= 0)
        assertNull(
            "wanting a thing the rules forbid is exactly the failure the index guard exists for",
            MindWire.interpretChoice("""{"index": $blocked, "reason": "I fancied a nap."}""", options),
        )
    }

    @Test
    fun `an index given as text is still read, and still guarded`() {
        assertEquals(2, MindWire.interpretChoice("""{"index": "2", "reason": "quiet day"}""", options)?.index)
        assertNull(MindWire.interpretChoice("""{"index": "9"}""", options))
        assertNull(MindWire.interpretChoice("""{"index": "eating"}""", options))
    }

    @Test
    fun `a choice with no reason falls back to the option's own words`() {
        val choice = MindWire.interpretChoice("""{"index": 0, "reason": "   "}""", options)
        assertEquals(options[0].reason, choice?.reason)
    }

    @Test
    fun `an over-long reason is capped before it reaches the decision log`() {
        val flood = "because ".repeat(500)
        val choice = MindWire.interpretChoice("""{"index": 0, "reason": "$flood"}""", options)
        assertEquals(MindWire.MAX_REASON_CHARS, choice!!.reason.length)
    }

    // ------------------------------------------------------------------ lessons

    @Test
    fun `a lesson kind the game does not know is dropped rather than guessed at`() {
        val raw = """
            {"lessons": [
              {"kind": "EAT_SOONER", "text": "I do not wait for the tin to be empty.", "strength": 0.7},
              {"kind": "eat sooner", "text": "friendly label, wrong spelling", "strength": 0.9},
              {"kind": "BE_LUCKIER", "text": "invented out of thin air", "strength": 1.0},
              {"kind": null, "text": "no kind at all", "strength": 0.5},
              {"text": "no kind field", "strength": 0.5}
            ]}
        """.trimIndent()
        val lessons = MindWire.interpretLessons(raw, generation = 3)
        assertEquals(1, lessons.size)
        assertEquals(LessonKind.EAT_SOONER, lessons[0].kind)
        assertEquals(3, lessons[0].fromGeneration)
    }

    @Test
    fun `every lesson passes through the same gate as a locally distilled one`() {
        val flood = "and then ".repeat(200)
        val raw = """
            {"lessons": [
              {"kind": "REST_SOONER", "text": "$flood", "strength": 9.9},
              {"kind": "TIDY_SOONER", "text": "   ", "strength": 0.8},
              {"kind": "PLAY_MORE", "text": "I make time for a game.", "strength": 0.0}
            ]}
        """.trimIndent()
        val lessons = MindWire.interpretLessons(raw, generation = 2)
        assertEquals("blank text and no strength are both nothing at all", 1, lessons.size)
        val kept = lessons.single()
        assertEquals(LessonKind.REST_SOONER, kept.kind)
        assertEquals(Lineage.MAX_LESSON_CHARS, kept.text.length)
        assertEquals(1f, kept.strength, 0.001f)
    }

    @Test
    fun `one life hands down no more lessons than a local distillation would`() {
        val entries = LessonKind.entries.joinToString(",") {
            """{"kind": "${it.name}", "text": "Something about ${it.name}.", "strength": 0.6}"""
        }
        val lessons = MindWire.interpretLessons("""{"lessons": [$entries]}""", generation = 1)
        assertEquals(MindWire.MAX_LESSONS_PER_RUN, lessons.size)
    }

    @Test
    fun `the same lesson twice is one lesson, not two`() {
        val raw = """
            [
              {"kind": "EAT_SOONER", "text": "weakly held", "strength": 0.2},
              {"kind": "EAT_SOONER", "text": "hard won", "strength": 0.9}
            ]
        """.trimIndent()
        val lessons = MindWire.interpretLessons(raw, generation = 4)
        assertEquals(1, lessons.size)
        assertEquals("hard won", lessons[0].text)
    }

    // ------------------------------------------------------------------ errands

    private val tools = mapOf(
        ToolId.CHECK_SELF to "Satiety 23%, happiness 61%, energy 44%, hygiene 80%, health 91%.",
        ToolId.LOOK_IN_PANTRY to "In the pantry: Berry x2.",
        ToolId.CHECK_COMPANY to "Bramble (friend, 70%)",
    )

    @Test
    fun `a plan is read back with its goal and its steps in order`() {
        val raw = """
            {"goal": "I want my tea and then a tidy room.",
             "steps": [{"kind": "EAT", "why": "There are berries and I am on 23%."},
                       {"kind": "TIDY", "why": "The floor is a disgrace."}]}
        """.trimIndent()
        val plan = MindWire.interpretPlan(raw)
        assertNotNull(plan)
        assertEquals("I want my tea and then a tidy room.", plan!!.goal)
        assertEquals(listOf(ActivityKind.EAT, ActivityKind.TIDY), plan.steps.map { it.kind })
        assertEquals("There are berries and I am on 23%.", plan.steps[0].why)
        assertEquals("a fresh plan has never been acted on", 0, plan.done)
    }

    @Test
    fun `an activity name the game does not know is dropped rather than guessed at`() {
        val raw = """
            {"goal": "A productive afternoon.",
             "steps": [{"kind": "FORAGE_IN_THE_WOODS", "why": "invented"},
                       {"kind": "eat", "why": "right word, wrong case"},
                       {"kind": "Eating", "why": "the friendly label"},
                       {"kind": "STUDY", "why": "I want to learn the pantry latch."},
                       {"kind": null, "why": "no kind at all"},
                       {"why": "no kind field"}]}
        """.trimIndent()
        val plan = MindWire.interpretPlan(raw)
        assertNotNull(plan)
        assertEquals(listOf(ActivityKind.STUDY), plan!!.steps.map { it.kind })
    }

    @Test
    fun `a plan of nothing but idling is no plan at all`() {
        val raw = """
            {"goal": "I shall have a lovely sit down.",
             "steps": [{"kind": "IDLE", "why": "nothing needs me"},
                       {"kind": "IDLE", "why": "still nothing"},
                       {"kind": "IDLE", "why": "and again"}]}
        """.trimIndent()
        // The client reads the shape; Errands decides what a plan is allowed to be. A reply of
        // nothing but idling parses fine and is refused a step later, by the one place that
        // refuses it however it arrived.
        val parsed = MindWire.interpretPlan(raw)
        assertNull(
            "three ways of doing nothing would park the creature for the plan's whole lifetime",
            parsed?.let { Errands.sanitise(it, petForPlans()) },
        )
    }

    @Test
    fun `idling is stripped out of a plan that also means to do something`() {
        val raw = """
            {"goal": "Tea, eventually.",
             "steps": [{"kind": "IDLE", "why": "a moment first"},
                       {"kind": "EAT", "why": "then my tea."}]}
        """.trimIndent()
        val plan = MindWire.interpretPlan(raw)?.let { Errands.sanitise(it, petForPlans()) }
        assertEquals(listOf(ActivityKind.EAT), plan?.steps?.map { it.kind })
    }

    @Test
    fun `an over-long goal and an over-long step list are both bounded`() {
        val flood = "and then a bit more ".repeat(60)
        val many = (1..40).joinToString(",") { """{"kind": "PLAY", "why": "$flood"}""" }
        val plan = MindWire.interpretPlan("""{"goal": "$flood", "steps": [$many]}""")
            ?.let { Errands.sanitise(it, petForPlans()) }
        assertNotNull(plan)
        assertEquals(Errands.MAX_GOAL_CHARS, plan!!.goal.length)
        assertEquals(Errands.MAX_STEPS, plan.steps.size)
        for (step in plan.steps) assertTrue(step.why.length <= Errands.MAX_GOAL_CHARS)
    }

    @Test
    fun `a step with no reason of its own borrows the goal`() {
        val plan = MindWire.interpretPlan("""{"goal": "I want a wash.", "steps": [{"kind": "GROOM"}]}""")
        assertEquals("I want a wash.", plan?.steps?.single()?.why)
    }

    @Test
    fun `a plan arrives unstamped, for the caller to date`() {
        val plan = MindWire.interpretPlan("""{"goal": "Tea.", "steps": [{"kind": "EAT", "why": "hungry"}]}""")
        assertEquals(
            "this layer has no clock, and a plausible invented time would look stamped",
            0L,
            plan?.madeAtSeconds,
        )
    }

    @Test
    fun `progress a model claims to have already made is thrown away`() {
        val raw = """{"goal": "Tea.", "steps": [{"kind": "EAT", "why": "hungry"}], "done": 1}"""
        assertEquals("a plan cannot arrive with its steps already ticked off", 0, MindWire.interpretPlan(raw)?.done)
    }

    @Test
    fun `a malformed or empty plan yields nothing rather than throwing`() {
        val duds = listOf(
            "",
            "   ",
            "I have thought about it and I would rather not say.",
            "{",
            "```json\n```",
            "[]",
            """{"goal": "Tea."}""",
            """{"steps": [{"kind": "EAT", "why": "hungry"}]}""",
            """{"goal": "   ", "steps": [{"kind": "EAT", "why": "hungry"}]}""",
            """{"goal": "Tea.", "steps": []}""",
            """{"goal": "Tea.", "steps": "eat then sleep"}""",
            """{"goal": "Tea.", "steps": ["EAT", "SLEEP"]}""",
        )
        for (dud in duds) {
            assertNull("a plan of <$dud> must not reach the creature", MindWire.interpretPlan(dud))
        }
    }

    @Test
    fun `a plan survives a fenced block with prose around it`() {
        val raw = """
            Here is what Mossling has in mind:

            ```json
            {"goal": "Eat, then find Bramble.", "steps": [{"kind": "EAT", "why": "I am on 23%."},
             {"kind": "SOCIALISE", "why": "Bramble is here and I would like the company."}]}
            ```

            Hope that helps!
        """.trimIndent()
        val plan = MindWire.interpretPlan(raw)
        assertNotNull(plan)
        assertEquals(listOf(ActivityKind.EAT, ActivityKind.SOCIALISE), plan!!.steps.map { it.kind })
    }

    @Test
    fun `the planning prompt offers the activity names it will parse back, and never idling`() {
        val prompt = MindWire.planSystemPrompt(brief)
        for (kind in ActivityKind.entries) {
            if (kind == ActivityKind.IDLE) continue
            assertTrue("$kind must be offered by name", prompt.contains(kind.name))
        }
        assertFalse("offering IDLE invites the one plan that is not a plan", prompt.contains("IDLE"))
        assertTrue(prompt.contains("break character"))
    }

    @Test
    fun `the planning prompt carries what looking around found, in a fixed order`() {
        val prompt = MindWire.planUserPrompt(brief, tools, options)
        assertTrue(prompt.contains("In the pantry: Berry x2."))
        assertTrue(prompt.contains("Bramble (friend, 70%)"))
        assertTrue("the creature still has to know its own state", prompt.contains("Mossling"))

        val shuffled = linkedMapOf(
            ToolId.CHECK_COMPANY to tools.getValue(ToolId.CHECK_COMPANY),
            ToolId.LOOK_IN_PANTRY to tools.getValue(ToolId.LOOK_IN_PANTRY),
            ToolId.CHECK_SELF to tools.getValue(ToolId.CHECK_SELF),
        )
        assertEquals(
            "the same look around must produce the same prompt, whatever order the map was built in",
            prompt,
            MindWire.planUserPrompt(brief, shuffled, options),
        )
    }

    @Test
    fun `the planning prompt marks the options it cannot begin right now`() {
        val prompt = MindWire.planUserPrompt(brief, tools, options)
        assertTrue(prompt.contains("NOT NOW: it is broad daylight"))
        assertTrue(prompt.contains("EAT"))
    }

    @Test
    fun `a player who turned planning off is never charged for a plan`() = runBlocking {
        val client = RemoteMindClient { keyedConfig.copy(makesPlans = false) }
        assertNull(
            "the flag has to be honoured here, where no caller can forget it",
            client.plan(brief, tools, options),
        )
    }

    @Test
    fun `no plan is asked for when nothing could legally be begun`() = runBlocking {
        val blockedOnly = options.map { it.copy(blockedBy = "not learned yet") }
        val client = RemoteMindClient { keyedConfig }
        assertNull(client.plan(brief, tools, blockedOnly))
        assertNull("and none at all when the whole feature is off", RemoteMindClient { MindConfig() }
            .plan(brief, tools, options))
    }

    // ------------------------------------------------------------------ the envelope

    @Test
    fun `an empty choices array reads as no answer at all`() {
        assertNull(MindWire.extractContent("""{"choices": []}"""))
        assertNull(MindWire.extractContent("""{"error": {"message": "rate limited"}}"""))
        assertNull(MindWire.extractContent("""{"choices": [{"message": {"content": ""}}]}"""))
        assertNull(MindWire.extractContent("not json at all"))
        assertNull(MindWire.extractContent(""))
    }

    @Test
    fun `the assistant's text is lifted out of the envelope untouched`() {
        val envelope = """{"choices":[{"message":{"role":"assistant","content":"{\"say\":\"Hungry.\"}"}}]}"""
        val content = MindWire.extractContent(envelope)
        assertEquals("""{"say":"Hungry."}""", content)
        assertEquals("Hungry.", MindWire.interpretReply(content!!)?.text)
    }

    // ------------------------------------------------------------------ staying in character

    @Test
    fun `the prompts tell the creature it is an animal and not a helper`() {
        val speak = MindWire.speakSystemPrompt(brief)
        assertTrue(speak.contains("You are ${brief.name}"))
        assertTrue(speak.contains("living creature"))
        assertTrue("breaking character is the one failure that ruins the illusion",
            speak.contains("break character"))
    }

    @Test
    fun `the choice prompt names every option, blocked ones marked as such`() {
        val prompt = MindWire.chooseUserPrompt(brief, options)
        for (index in options.indices) assertTrue(prompt.contains("$index. ${options[index].kind.displayName}"))
        assertTrue(prompt.contains("UNAVAILABLE"))
        assertTrue(prompt.contains("it is broad daylight"))
    }

    @Test
    fun `the distillation prompt lists lesson kinds by the exact name it will parse back`() {
        val prompt = MindWire.distilSystemPrompt()
        for (kind in LessonKind.entries) assertTrue("$kind must be offered by name", prompt.contains(kind.name))
    }

    // ------------------------------------------------------------------ the provider itself

    @Test
    fun `readiness follows the config, and follows it as it changes`() {
        var config = MindConfig()
        val client = RemoteMindClient { config }
        assertFalse("off by default, and it stays off", client.isReady)

        config = keyedConfig
        assertTrue("a client that cached the old config would stay broken after settings", client.isReady)

        config = keyedConfig.copy(enabled = false)
        assertFalse(client.isReady)

        config = MindConfig(enabled = true, apiKey = "", proxyUrl = "")
        assertFalse("enabled with nowhere to go is not ready", client.isReady)
    }
}
