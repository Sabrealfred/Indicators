package com.neopal.pet.data

import com.neopal.pet.domain.ActivityKind
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.DeathReason
import com.neopal.pet.domain.Errands
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.RunRecord
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.Species
import com.neopal.pet.domain.ToolId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote brain, actually run over a socket.
 *
 * Until this existed the network half of [RemoteMindClient] had never been executed once — not in
 * this repository, not anywhere. Its timeouts, its cancellation, its refusal to follow a redirect
 * and its handling of a service that answers with the wrong thing were all *reasoned*, and the
 * project's own notes said so. Reasoning is worth a lot less here than elsewhere, because every
 * one of those behaviours lives in the gap between what an API is documented to do and what it
 * does, which is exactly the gap reading a file cannot cross.
 *
 * The client is pure JVM — there is no Android import anywhere in it — so the only thing standing
 * between it and a real run was something on the other end of a socket. [StubMindServer] is that.
 *
 * These tests assert on what the server *received* as much as on what the client returned. Half
 * the things worth knowing here — that the key travels as a header and never in the body, that a
 * refused redirect means the second host is never contacted at all — are invisible from the
 * caller's side and can only be seen from the other end.
 */
class RemoteMindOverTheWireTest {

    private val brief = PetBrief(
        name = "Mossling", species = "Leafling", stage = "Child", branch = "Balanced",
        personality = "Curious", ageDays = 4, generation = 3,
        satiety = 23, happiness = 61, energy = 44, hygiene = 80, health = 91, bond = 55,
        intellect = 31, isSick = false, isSleeping = false,
        skills = listOf("Feeding itself"), company = emptyList(),
        recentDiary = listOf("Woke up hungry."), lastDecision = null, inheritedLessons = emptyList(),
    )

    private val options = listOf(
        Consideration(ActivityKind.EAT, 0.81f, "There was a berry in the tin.", null),
        Consideration(ActivityKind.SLEEP, 0.40f, "The lights were off.", "it is broad daylight"),
        Consideration(ActivityKind.PLAY, 0.30f, "Nothing else wanted me.", null),
    )

    private val key = "sk-secret-do-not-leak-0123456789"

    private fun pet(): PetState = Simulation
        .advance(Simulation.newGame("T", Species.LEAF, 1_000_000L), 1_120_000L, GameConfig.Default)
        .state
        .copy(stage = LifeStage.ADULT, ageSeconds = 10_800L)

    private fun configFor(server: StubMindServer, timeoutMillis: Long = 4_000L) = MindConfig(
        enabled = true,
        baseUrl = server.baseUrl,
        apiKey = key,
        model = "some/model:free",
        timeoutMillis = timeoutMillis,
    )

    private fun clientFor(server: StubMindServer, timeoutMillis: Long = 4_000L): RemoteMindClient {
        val config = configFor(server, timeoutMillis)
        return RemoteMindClient { config }
    }

    // ---- it works at all -----------------------------------------------------------------

    @Test
    fun `the creature answers over a real socket`() {
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion("""{"text":"I am famished."}""")) }
            .use { server ->
                val reply = runBlocking { clientFor(server).speak(brief, emptyList(), "how are you?") }
                assertNotNull("the whole feature comes down to this returning something", reply)
                assertEquals("I am famished.", reply!!.text)
                assertEquals("and it took exactly one request to do it", 1, server.received.size)
            }
    }

    @Test
    fun `it posts json to the completions path`() {
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion("""{"text":"hello"}""")) }
            .use { server ->
                runBlocking { clientFor(server).speak(brief, emptyList(), "hello") }
                val sent = server.received.single()
                assertEquals("POST", sent.method)
                assertTrue("a base URL has to grow the completions path", sent.path.endsWith("/chat/completions"))
                assertTrue(sent.headers["content-type"]?.contains("application/json") == true)
                assertTrue("the model has to be named in the body", sent.body.contains("some/model:free"))
            }
    }

    @Test
    fun `the key travels as a header and is nowhere in the body`() {
        // Asserted from the server's side, because from the caller's side it is unobservable —
        // and a key in the body is a key in every proxy log the request passes through.
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion("""{"text":"hi"}""")) }
            .use { server ->
                runBlocking { clientFor(server).speak(brief, emptyList(), "hi") }
                val sent = server.received.single()
                assertEquals("Bearer $key", sent.headers["authorization"])
                assertFalse("never in the body", sent.body.contains(key))
                assertFalse("nor in the path, where it would land in an access log", sent.path.contains(key))
            }
    }

    @Test
    fun `it chooses from the list it was given`() {
        StubMindServer {
            StubMindServer.Reply(body = StubMindServer.completion("""{"index":0,"reason":"I am starving."}"""))
        }.use { server ->
            val choice = runBlocking { clientFor(server).choose(brief, options) }
            assertNotNull(choice)
            assertEquals(0, choice!!.index)
            assertEquals("I am starving.", choice.reason)
        }
    }

    @Test
    fun `it comes back with a plan that survives sanitising`() {
        val json = """{"goal":"Sort myself out.","steps":[{"kind":"EAT","why":"hungry"},""" +
            """{"kind":"PLAY","why":"then something nice"}]}"""
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion(json)) }.use { server ->
            val tools = ToolId.entries.associateWith { "nothing much" }
            val plan = runBlocking { clientFor(server).plan(brief, tools, options) }
            assertNotNull(plan)
            assertEquals("Sort myself out.", plan!!.goal)
            assertEquals(listOf(ActivityKind.EAT, ActivityKind.PLAY), plan.steps.map { it.kind })
        }
    }

    @Test
    fun `it distils a finished life into lessons`() {
        val json = """{"lessons":[{"kind":"EAT_SOONER","text":"I left it too late.","strength":0.5}]}"""
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion(json)) }.use { server ->
            val record = RunRecord(deathReason = DeathReason.STARVATION, lifespanSeconds = 3_600L)
            val lessons = runBlocking { clientFor(server).distil(brief, record, emptyList()) }
            assertTrue(lessons.isNotEmpty())
            assertEquals("I left it too late.", lessons.first().text)
        }
    }

    // ---- it fails the way the game needs it to -------------------------------------------

    @Test
    fun `a service that is out of quota leaves the local brain in charge`() {
        // 429 is the one every free tier eventually returns, and it must be a quiet no rather
        // than an exception thrown up through a coroutine into the clock loop.
        StubMindServer { StubMindServer.Reply(status = 429, body = """{"error":"rate limited"}""") }
            .use { server ->
                assertNull(runBlocking { clientFor(server).speak(brief, emptyList(), "hello") })
            }
    }

    @Test
    fun `an error body is never handed back to be shown to anyone`() {
        // The error text is written by a service nobody here controls and it can echo the request
        // back — including, on a misconfigured proxy, the header it was sent with.
        StubMindServer {
            StubMindServer.Reply(status = 500, body = """{"error":{"message":"failed: $key"}}""")
        }.use { server ->
            val reply = runBlocking { clientFor(server).speak(brief, emptyList(), "hello") }
            assertNull("nothing from an error body may reach the creature's mouth", reply)
        }
    }

    @Test
    fun `a service that answers in prose rather than json is a quiet no`() {
        // This is the one the project could not settle by reading: a free model that ignores the
        // instruction and replies conversationally. It must not crash and must not put unparsed
        // text on screen — the creature simply has nothing to say.
        StubMindServer {
            StubMindServer.Reply(body = StubMindServer.completion("Oh, I am quite well thank you!"))
        }.use { server ->
            val reply = runBlocking { clientFor(server).speak(brief, emptyList(), "how are you?") }
            assertNull("prose is refused rather than half-parsed", reply)
        }
    }

    @Test
    fun `an index outside the list is refused rather than clamped`() {
        StubMindServer {
            StubMindServer.Reply(body = StubMindServer.completion("""{"index":99,"reason":"that one"}"""))
        }.use { server ->
            assertNull(runBlocking { clientFor(server).choose(brief, options) })
        }
    }

    @Test
    fun `a blocked option cannot be chosen however confidently it is named`() {
        // Index 1 is SLEEP, which the local brain already ruled out. Over the wire it looks
        // exactly like a legal answer.
        StubMindServer {
            StubMindServer.Reply(body = StubMindServer.completion("""{"index":1,"reason":"I am tired."}"""))
        }.use { server ->
            assertNull(runBlocking { clientFor(server).choose(brief, options) })
        }
    }

    @Test
    fun `a service that never answers gives up on time`() {
        val budget = 700L
        StubMindServer {
            StubMindServer.Reply(body = StubMindServer.completion("""{"text":"too late"}"""), delayMillis = 5_000L)
        }.use { server ->
            val started = System.nanoTime()
            val reply = runBlocking { clientFor(server, timeoutMillis = budget).speak(brief, emptyList(), "hi") }
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertNull("a hung service must not hang the creature", reply)
            assertTrue("gave up after ${tookMillis}ms, which is not giving up", tookMillis < 4_000)
        }
    }

    @Test
    fun `a server that dribbles is abandoned, and does not latch the creature`() {
        // The bug this pins: cancellation was registered with `invokeOnCompletion`, which fires
        // when a job *completes* — and a job blocked in a socket read cannot complete, so the
        // disconnect that would end the read was waiting on the read. The existing timeout test
        // could not catch it, because a silent server trips the JDK's read timeout and never
        // reaches the coroutine machinery at all. This one keeps writing, which resets that
        // timeout forever, and is the realistic case: OpenRouter sends keep-alive lines.
        StubMindServer { StubMindServer.Reply(dribbleGapMillis = 30L) }.use { server ->
            val started = System.nanoTime()
            val reply = runBlocking {
                withTimeoutOrNull(9_000L) { clientFor(server, timeoutMillis = 900L).speak(brief, emptyList(), "hi") }
            }
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertNull("a dribbling service must not be waited on forever", reply)
            assertTrue(
                "gave up after ${tookMillis}ms against a 900ms budget, which is not giving up",
                tookMillis < 8_000,
            )
        }
    }

    @Test
    fun `a redirect is refused, so the key never reaches the second host`() {
        // The attack this stops: a service the player pasted into settings answers 302 pointing
        // somewhere else, and a client that followed it would re-send the bearer token there.
        StubMindServer { request ->
            if (request.path.contains("/v1/")) {
                StubMindServer.Reply(
                    status = 302,
                    extraHeaders = mapOf("Location" to "http://127.0.0.1:1/stolen/chat/completions"),
                )
            } else {
                StubMindServer.Reply(body = StubMindServer.completion("""{"text":"gotcha"}"""))
            }
        }.use { server ->
            val reply = runBlocking { clientFor(server).speak(brief, emptyList(), "hello") }
            assertNull("a redirect is not an answer", reply)
            assertEquals("and it must not have been followed", 1, server.received.size)
        }
    }

    @Test
    fun `the switch being off means no socket is opened at all`() {
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion("""{"text":"hi"}""")) }
            .use { server ->
                val off = configFor(server).copy(enabled = false)
                val client = RemoteMindClient { off }
                assertNull(runBlocking { client.speak(brief, emptyList(), "hello") })
                assertTrue("a disabled feature must cost nothing, not even a connection",
                    server.received.isEmpty())
            }
    }

    @Test
    fun `a sub-toggle being off stops its own job and no other`() {
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion("""{"text":"hi"}""")) }
            .use { server ->
                val quiet = configFor(server).copy(conversation = false)
                val client = RemoteMindClient { quiet }
                assertNull(runBlocking { client.speak(brief, emptyList(), "hello") })
                assertTrue(server.received.isEmpty())

                runBlocking { client.choose(brief, options) }
                assertEquals("deciding was never switched off", 1, server.received.size)
            }
    }

    @Test
    fun `a plan arrives unstamped and is only made real by the creature it is for`() {
        // This is the footgun the Errands API was reshaped to close, so it is worth pinning from
        // the wire end. What comes back over the network is a *proposal*: checked for shape, and
        // dated zero, because the network layer has no clock and no creature. Dated zero it is
        // already expired for any creature older than a few steps' worth of seconds — and the
        // failure is silent, because the local brain covers for a missing plan perfectly.
        //
        // The stamp can therefore only be applied where the clock is, which is why sanitise takes
        // a PetState rather than a Long. A test that only checked the returned plan would pass
        // while the feature was dead.
        val json = """{"goal":"Tidy up.","steps":[{"kind":"TIDY","why":"it is a mess"}]}"""
        StubMindServer { StubMindServer.Reply(body = StubMindServer.completion(json)) }.use { server ->
            val creature = pet()
            val proposed = runBlocking {
                clientFor(server).plan(brief, ToolId.entries.associateWith { "nothing" }, options)
            }
            assertNotNull(proposed)
            assertTrue(
                "as it stands it is expired, and that is the point of not using it directly",
                proposed!!.isStale(creature.ageSeconds),
            )

            val accepted = Errands.sanitise(proposed, creature)
            assertNotNull("and sanitising against the creature is what makes it usable", accepted)
            assertFalse("stamped now, so it is not born expired", accepted!!.isStale(creature.ageSeconds))
            assertEquals(creature.ageSeconds, accepted.madeAtSeconds)
            assertEquals("Tidy up.", accepted.goal)
        }
    }
}
