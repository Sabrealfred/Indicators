package com.neopal.pet.data

import com.neopal.pet.domain.ActivityKind
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.LessonKind
import com.neopal.pet.domain.Lineage
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.PetBrief
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
