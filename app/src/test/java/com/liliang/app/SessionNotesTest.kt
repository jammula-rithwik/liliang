package com.liliang.app

import com.liliang.app.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R20: spoken set notes, superset tags and per-side detection — the pure parts. */
class SessionNotesTest {

    private fun set(index: Int, comment: String = "") =
        WorkoutSessionSet(id = index.toLong(), setIndex = index, repsText = "10", comment = comment, loggedAtMillis = 1L)

    @Test
    fun joinCommentsDropsBlanksAndTrims() {
        assertEquals("grip slipping · dropped 5kg", joinComments(" grip slipping ", "", "dropped 5kg"))
        assertEquals("", joinComments("", "  "))
    }

    @Test
    fun pendingNoteSettlesOnTheLastLoggedSetAtTheSeal() {
        val item = WorkoutSessionItem(
            id = 1,
            exerciseName = "Bench Press",
            loggedSets = listOf(set(1), set(2), set(3, comment = "grindy")),
            pendingComment = "bar drifted forward",
        )
        val settled = item.settlePendingComment()
        assertEquals("", settled.pendingComment)
        assertEquals("grindy · after set 3: bar drifted forward", settled.loggedSets.last().comment)
        assertEquals("", settled.loggedSets.first().comment)
        assertEquals(3, settled.loggedSets.size)
    }

    @Test
    fun aSkippedExerciseKeepsItsNoteOnTheItem() {
        val item = WorkoutSessionItem(id = 1, exerciseName = "Dips", pendingComment = "skipped, elbow")
        assertSame(item, item.settlePendingComment())
    }

    @Test
    fun noPendingNoteChangesNothing() {
        val item = WorkoutSessionItem(id = 1, exerciseName = "Row", loggedSets = listOf(set(1)))
        assertSame(item, item.settlePendingComment())
    }

    @Test
    fun supersetsGetLettersInRunningOrder() {
        val items = listOf(
            WorkoutSessionItem(id = 1, exerciseName = "Incline Press"),
            WorkoutSessionItem(id = 2, exerciseName = "Face Pull", supersetId = "pull"),
            WorkoutSessionItem(id = 3, exerciseName = "Dead Bug", supersetId = "coreA"),
            WorkoutSessionItem(id = 4, exerciseName = "Lateral Raise", supersetId = "pull"),
            WorkoutSessionItem(id = 5, exerciseName = "Plank", supersetId = "coreA"),
        )
        val tags = supersetTags(items)
        assertNull(tags[1])
        assertEquals('A', tags.getValue(2).letter)
        assertEquals('A', tags.getValue(4).letter)
        assertEquals('B', tags.getValue(3).letter)
        assertEquals(1, tags.getValue(5).index)
        assertEquals(listOf("Lateral Raise"), tags.getValue(2).partners)
        assertEquals(listOf("Dead Bug"), tags.getValue(5).partners)
    }

    @Test
    fun aLoneSupersetIdIsNotASuperset() {
        val items = listOf(
            WorkoutSessionItem(id = 1, exerciseName = "Face Pull", supersetId = "pull"),
            WorkoutSessionItem(id = 2, exerciseName = "Curl"),
        )
        assertTrue(supersetTags(items).isEmpty())
    }

    @Test
    fun perSideComesFromTheExerciseOrThePlanText() {
        val plank = ExerciseCatalogItem(name = "Side Plank", defaultMuscle = "Core", defaultCategory = "", logFormat = LogFormat.SECONDS, isBodyweight = true, perSide = true)
        val plain = plank.copy(perSide = false)
        assertTrue(isPerSide(WorkoutSessionItem(exerciseName = "Side Plank", targetDurationSeconds = 30), plank))
        assertTrue(isPerSide(WorkoutSessionItem(exerciseName = "Dead Bug", targetReps = "10/side"), null))
        assertTrue(isPerSide(WorkoutSessionItem(exerciseName = "Lunge", targetReps = "8 each side"), null))
        assertTrue(isPerSide(WorkoutSessionItem(exerciseName = "Hold", targetReps = "20s / side"), plain))
        assertFalse(isPerSide(WorkoutSessionItem(exerciseName = "Side Plank", targetDurationSeconds = 30), plain))
        assertFalse(isPerSide(WorkoutSessionItem(exerciseName = "Bench", targetReps = "8-12"), null))
    }
}
