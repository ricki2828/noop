package com.noop.bodyage

import com.noop.bodyage.BodyAgePlus.FactorKey
import com.noop.bodyage.BodyAgePlus.MissingReason
import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BodyAgeInputsTest {

    private val end = LocalDate.of(2026, 11, 8)
    private val utc = ZoneId.of("UTC")

    private fun day(offset: Int, sleepMin: Double? = 420.0, rhr: Int? = 56, hrv: Double? = 40.0, steps: Int? = 8000, dev: String = "my-whoop") =
        DailyMetric(deviceId = dev, day = end.minusDays(offset.toLong()).toString(), totalSleepMin = sleepMin, restingHr = rhr, avgHrv = hrv, steps = steps)

    private fun raw(
        days: List<DailyMetric> = emptyList(),
        vo2: List<MetricSeriesRow> = emptyList(),
        workouts: List<WorkoutRow> = emptyList(),
        drinks: List<DrinkEntry> = emptyList(),
        zones: Map<LocalDate, ZoneMinutes.DayZones> = emptyMap(),
        thresholds: Boolean = true,
        unresolved: Set<LocalDate> = emptySet(),
    ) = BodyAgeInputs.Raw(46.0, "male", days, vo2, workouts, drinks, zones, thresholds, utc, unresolved)

    @Test
    fun fixtureRowsGiveExpectedInputs() {
        val days = (0..6).map { day(it, sleepMin = 360.0 + it * 10, rhr = 54 + it, hrv = 30.0 + it, steps = 6000 + it * 100) }
        val i = BodyAgeInputs.build(raw(days), end)
        // sleep minutes 360..420 -> mean 390 min = 6.5 h
        assertEquals(6.5, i.sleepHours!!, 1e-9)
        assertEquals(57.0, i.restingHR!!, 1e-9) // median of 54..60
        assertEquals(33.0, i.rmssd!!, 1e-9) // median of 30..36
        assertEquals(6300.0, i.steps!!, 1e-9)
        assertNotNull(i.sleepConsistency)
        assertNull(i.vo2max)
    }

    @Test
    fun emptyDatabaseGivesAllNullAndNoNumber() {
        val i = BodyAgeInputs.build(raw(), end)
        assertNull(i.restingHR); assertNull(i.sleepHours); assertNull(i.rmssd); assertNull(i.steps)
        assertNull(i.vo2max); assertNull(i.zoneLowMinPerWeek); assertNull(i.strengthSessionsPerWeek)
        assertEquals(MissingReason.TOO_FEW_LOGS, i.missing[FactorKey.ALCOHOL])
        assertNull(BodyAgePlus.compute(i).bodyAge)
    }

    @Test
    fun importedAndLiveRowsOnTheSameDayAreNotDoubleCounted() {
        val imported = day(0, sleepMin = 480.0, rhr = 50, hrv = 45.0, steps = 9000, dev = "my-whoop")
        val live = day(0, sleepMin = null, rhr = 52, hrv = null, steps = null, dev = "my-whoop-noop")
        val deduped = BodyAgeInputs.dedupeDays(listOf(live, imported))
        assertEquals(1, deduped.size)
        assertEquals("my-whoop", deduped[0].deviceId)
        val i = BodyAgeInputs.build(raw(listOf(live, imported)), end)
        assertEquals(8.0, i.sleepHours!!, 1e-9)
        assertEquals(50.0, i.restingHR!!, 1e-9)
    }

    @Test
    fun windowIgnoresDaysOutsideSevenAndFutureDays() {
        val rows = listOf(day(0, rhr = 60), day(6, rhr = 60), day(7, rhr = 90), day(-1, rhr = 90))
        assertEquals(60.0, BodyAgeInputs.build(raw(rows), end).restingHR!!, 1e-9)
    }

    @Test
    fun vo2UsesLatestEstimateOnOrBeforeTheEndDay() {
        val series = listOf(
            MetricSeriesRow("d", end.minusDays(40).toString(), "vo2max_est", 36.0),
            MetricSeriesRow("d", end.minusDays(3).toString(), "vo2max_est", 38.0),
            MetricSeriesRow("d", end.plusDays(2).toString(), "vo2max_est", 50.0),
        )
        val i = BodyAgeInputs.build(raw(vo2 = series), end)
        assertEquals(38.0, i.vo2max!!, 0.0)
        assertEquals(36.8, i.expectedVO2max!!, 1e-9) // male, age 46: 37 at 45 and 36 at 50
    }

    @Test
    fun expectedVo2FollowsTheTableAndSex() {
        assertEquals(40.0, BodyAgeInputs.expectedVo2(40.0, "male"), 1e-9)
        assertEquals(38.5, BodyAgeInputs.expectedVo2(42.5, "male"), 1e-9)
        assertEquals(34.0, BodyAgeInputs.expectedVo2(40.0, "female"), 1e-9)
        assertEquals(44.0, BodyAgeInputs.expectedVo2(20.0, "male"), 1e-9)
        assertEquals(30.0, BodyAgeInputs.expectedVo2(80.0, "male"), 1e-9)
    }

    @Test
    fun otherSexUsesTheMidFraction() {
        assertEquals(34.225, BodyAgeInputs.expectedVo2(45.0, "nonbinary"), 1e-9)
    }

    @Test
    fun zonesSumOverTheWindowAndNeedEvidence() {
        val z = mapOf(
            end to ZoneMinutes.DayZones(60.0, 10.0, ZoneMinutes.Source.HR),
            end.minusDays(2) to ZoneMinutes.DayZones(40.0, 5.0, ZoneMinutes.Source.WORKOUT),
            end.minusDays(20) to ZoneMinutes.DayZones(500.0, 500.0, ZoneMinutes.Source.WORKOUT),
        )
        val i = BodyAgeInputs.build(raw(zones = z), end)
        assertEquals(100.0, i.zoneLowMinPerWeek!!, 1e-9)
        assertEquals(15.0, i.zoneHighMinPerWeek!!, 1e-9)
        val none = BodyAgeInputs.build(raw(zones = mapOf(end to ZoneMinutes.NO_ZONES)), end)
        assertNull(none.zoneLowMinPerWeek)
        assertNull(none.missing[FactorKey.ZONE_LOW])
    }

    @Test
    fun noThresholdsPutsBothZoneFactorsInNotCounted() {
        val z = mapOf(end to ZoneMinutes.DayZones(60.0, 10.0, ZoneMinutes.Source.HR))
        val i = BodyAgeInputs.build(raw(days = (0..6).map { day(it) }, zones = z, thresholds = false), end)
        assertNull(i.zoneLowMinPerWeek)
        val nc = BodyAgePlus.compute(i).notCounted.associateBy { it.key }
        assertEquals(MissingReason.NO_THRESHOLDS, nc[FactorKey.ZONE_LOW]!!.reason)
        assertEquals(MissingReason.NO_THRESHOLDS, nc[FactorKey.ZONE_HIGH]!!.reason)
    }

    private fun strengthWorkout(offset: Int, sport: String = "Strength", device: String = "d") =
        end.minusDays(offset.toLong()).atStartOfDay(utc).toEpochSecond().let {
            WorkoutRow(device, it + 3600, it + 7200, sport, "test", durationS = 3600.0)
        }

    @Test
    fun strengthCountsSessionsFromEverySourceInTheWindow() {
        val ws = listOf(
            strengthWorkout(1), strengthWorkout(3, "Weightlifting"), strengthWorkout(2, "Running"),
            strengthWorkout(4, "Weight Training", device = "health-connect"), strengthWorkout(5, "Lifting", device = "lifting"),
            strengthWorkout(10),
        )
        assertEquals(4.0, BodyAgeInputs.build(raw(workouts = ws), end).strengthSessionsPerWeek!!, 0.0)
    }

    @Test
    fun strengthIsAZeroOnlyWhenOtherWorkoutsProveTheWeekWasRecorded() {
        assertEquals(0.0, BodyAgeInputs.build(raw(workouts = listOf(strengthWorkout(2, "Running"))), end).strengthSessionsPerWeek!!, 0.0)
    }

    @Test
    fun noWorkoutOfAnyKindInTheWindowIsNotCountedNeverZero() {
        val i = BodyAgeInputs.build(raw(workouts = listOf(strengthWorkout(30))), end)
        assertNull(i.strengthSessionsPerWeek)
        assertNull(BodyAgeInputs.build(raw(), end).strengthSessionsPerWeek)
        val nc = BodyAgePlus.compute(i).notCounted.associateBy { it.key }
        assertEquals(MissingReason.NO_DATA, nc[FactorKey.STRENGTH]!!.reason)
    }

    @Test
    fun alcoholNeedsFourAnswersThenScalesToAWeek() {
        val three = listOf(0, 1, 2).map { DrinkEntry(end.minusDays(it.toLong()).toString(), 2) }
        val few = BodyAgeInputs.build(raw(drinks = three), end)
        assertNull(few.alcoholDaysPerWeek)
        assertEquals(MissingReason.TOO_FEW_LOGS, few.missing[FactorKey.ALCOHOL])

        // 4 answered, 2 of them drinking -> 3.5 drinking days a week
        val four = listOf(DrinkEntry(end.toString(), 0), DrinkEntry(end.minusDays(1).toString(), 3),
            DrinkEntry(end.minusDays(2).toString(), 1), DrinkEntry(end.minusDays(3).toString(), 0))
        assertEquals(3.5, BodyAgeInputs.build(raw(drinks = four), end).alcoholDaysPerWeek!!, 1e-9)
    }

    @Test
    fun trendDropsWeeksWithTooFewFactors() {
        // Only the last two weeks have data; earlier weeks must be gaps, not zeros or interpolations.
        val days = (0..13).map { day(it) }
        val t = BodyAgeInputs.trend(raw(days), end, weeks = 8)
        assertEquals(listOf(end.minusDays(7), end), t.map { it.day })
    }

    @Test
    fun trendSkipsWeeksWhoseHrIsStillBeingRead() {
        val days = (0..20).map { day(it) }
        val unresolved = (8..10).map { end.minusDays(it.toLong()) }.toSet() // inside the window ending end-7 only
        val t = BodyAgeInputs.trend(raw(days, unresolved = unresolved), end, weeks = 3)
        assertEquals(listOf(end.minusDays(14), end), t.map { it.day })
        assertTrue(!BodyAgeInputs.windowResolved(raw(unresolved = unresolved), end.minusDays(7)))
        assertTrue(BodyAgeInputs.windowResolved(raw(unresolved = unresolved), end))
    }

    @Test
    fun todaysPartialRowNeverEntersAWindowEndingYesterday() {
        val yesterday = end.minusDays(1)
        val rows = (1..7).map { day(it, steps = 8000) } + day(0, steps = 300) // "today" = end, partial steps
        assertEquals(8000.0, BodyAgeInputs.build(raw(rows), yesterday).steps!!, 1e-9)
    }
}
