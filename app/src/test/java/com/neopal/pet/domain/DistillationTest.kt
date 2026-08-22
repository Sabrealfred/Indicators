package com.neopal.pet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between a remote mind's reading of a finished life and the line that inherits it.
 *
 * These are the rules that make it safe to call [MindProvider.distil] at all. Until this existed
 * the method was never called from anywhere, which meant none of them had a place to live.
 */
class DistillationTest {

    private fun heir(generation: Int = 2, lessons: List<Lesson> = emptyList()) = PetState(
        name = "Heir",
        species = Species.LEAF,
        stage = LifeStage.CHILD,
        generation = generation,
        lessons = lessons,
    )

    private fun lesson(
        kind: LessonKind = LessonKind.EAT_SOONER,
        text: String = "My parent waited too long. I do not wait.",
        strength: Float = 0.5f,
        from: Int = 1,
    ) = Lesson(kind, text, strength, from)

    @Test
    fun `a lesson about the life this heir inherited is folded in`() {
        val folded = Distillation.fold(heir(generation = 2), fromGeneration = 1, remote = listOf(lesson()))
        assertNotNull("the model's reading has to reach the creature or the call is pointless", folded)
        assertEquals(1, folded!!.lessons.size)
        assertEquals(LessonKind.EAT_SOONER, folded.lessons.first().kind)
    }

    @Test
    fun `it merges with what the line already worked out for itself`() {
        // The local distillation has already run by the time an answer arrives; the model adds to
        // that inheritance, it does not replace it.
        val local = lesson(kind = LessonKind.EAT_SOONER, text = "Local wording.", strength = 0.4f)
        val folded = Distillation.fold(
            heir(generation = 2, lessons = listOf(local)),
            fromGeneration = 1,
            remote = listOf(lesson(kind = LessonKind.EAT_SOONER, text = "The model's wording.", strength = 0.5f)),
        )
        assertNotNull(folded)
        assertEquals("same kind, one line", 1, folded!!.lessons.size)
        assertEquals("the stronger wording wins", "The model's wording.", folded.lessons.first().text)
        assertEquals("and the strengths sum", 0.9f, folded.lessons.first().strength, 0.0001f)
    }

    @Test
    fun `an answer about a grandparent is dropped`() {
        // The reply took long enough that the player buried another pet in the meantime. Folding
        // it in now would hand the line the same lesson twice for one death.
        assertNull(
            Distillation.fold(heir(generation = 3), fromGeneration = 1, remote = listOf(lesson())),
        )
    }

    @Test
    fun `an answer that arrives before the line has moved on is dropped too`() {
        assertNull(
            "a lesson from a life that has not finished is not a lesson",
            Distillation.fold(heir(generation = 2), fromGeneration = 2, remote = listOf(lesson())),
        )
    }

    @Test
    fun `nothing to say leaves the save alone`() {
        assertNull("the normal case: offline, off, or out of quota",
            Distillation.fold(heir(), fromGeneration = 1, remote = emptyList()))
    }

    @Test
    fun `every lesson passes the same gate as a locally distilled one`() {
        val hostile = listOf(
            lesson(text = "   "),
            lesson(strength = 0f),
            lesson(strength = Float.NaN),
        )
        assertNull("nothing survivable came back", Distillation.fold(heir(), 1, hostile))

        val overblown = Distillation.fold(heir(), 1, listOf(lesson(strength = 9f)))
        assertNotNull(overblown)
        assertTrue(
            "one reply must not blow past the bias cap in a single generation",
            overblown!!.lessons.all { it.strength <= 1f },
        )
    }

    @Test
    fun `a reply that changes nothing is not a reason to rewrite the save`() {
        val already = lesson(strength = 1f)
        assertNull(
            Distillation.fold(heir(generation = 2, lessons = listOf(already)), 1, listOf(already)),
        )
    }
}
