package com.noop.bodyage

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Tests the pure snapshot assembly the loader runs after its database reads. */
class BodyAgeLoaderTest {

    private val end = LocalDate.of(2026, 11, 7) // the last completed day (today is the 8th)
    private val utc = ZoneId.of("UTC")

    // Resting HR 55 over the latest week, 75 over the week ending 30 days earlier, 65 in between.
    // Sleep 7.5 h every night (regularity 1.0). Hand calculation of the 30-day change:
    //   now:    rhr -0.100 + sleep 0 + regularity (0.75-1.0)*0.45 = -0.1125  -> -0.2125 ln -> -1.8399 y
    //   before: rhr +0.100 + 0 + -0.1125                                    -> -0.0125 ln -> -0.1082 y
    //   change = -1.7316 y
    private fun days() = (0..40).map { off ->
        DailyMetric(
            deviceId = "d", day = end.minusDays(off.toLong()).toString(), totalSleepMin = 450.0,
            restingHr = when { off <= 6 -> 55; off in 30..36 -> 75; else -> 65 },
        )
    }

    private fun raw(unresolved: Set<LocalDate> = emptySet()) = BodyAgeInputs.Raw(
        46.0, "male", days(), emptyList(), emptyList(), emptyList(), emptyMap(), true, utc, unresolved,
    )

    @Test
    fun resolvedHistoryGivesTheThirtyDayChange() {
        val s = BodyAgeLoader.assemble(raw(), end, 0, emptyList())
        assertEquals(-1.7316, s.thirtyDayChange!!, 0.01)
        assertEquals(end.minusDays(30), s.thirtyDayFrom)
        assertNotNull(s.result.bodyAge)
        assertEquals(end, s.end)
    }

    @Test
    fun unresolvedDaysSuppressTheDeltaAndTheirTrendWeeks() {
        val unresolved = (30..36).map { end.minusDays(it.toLong()) }.toSet()
        val s = BodyAgeLoader.assemble(raw(unresolved), end, 120, emptyList())
        assertNull(s.thirtyDayChange)
        assertNull(s.thirtyDayFrom)
        assertEquals(120, s.backfillingDays)
        // Weeks ending 28 and 35 days back overlap the unresolved days and must be gaps.
        assertTrue(s.trend.none { it.day == end.minusDays(28) || it.day == end.minusDays(35) })
        assertTrue(s.trend.any { it.day == end })
    }

    @Test
    fun drinksAnswerIsTheLastCompletedDay() {
        val s = BodyAgeLoader.assemble(raw(), end, 0, listOf(DrinkEntry(end.toString(), 2), DrinkEntry(end.minusDays(1).toString(), 4)))
        assertEquals(2, s.drinksYesterday)
        assertNull(BodyAgeLoader.assemble(raw(), end, 0, emptyList()).drinksYesterday)
    }

    @Test
    fun zoneSourceCountsComeFromTheHeadlineWeek() {
        val zones = mapOf(
            end to ZoneMinutes.DayZones(30.0, 0.0, ZoneMinutes.Source.HR),
            end.minusDays(1) to ZoneMinutes.DayZones(30.0, 0.0, ZoneMinutes.Source.WORKOUT),
            end.minusDays(2) to ZoneMinutes.NO_ZONES,
            end.minusDays(20) to ZoneMinutes.DayZones(30.0, 0.0, ZoneMinutes.Source.HR),
        )
        val r = raw().copy(zonesByDay = zones)
        val s = BodyAgeLoader.assemble(r, end, 0, emptyList())
        assertEquals(1, s.hrDays)
        assertEquals(2, s.zoneDays)
    }
}
