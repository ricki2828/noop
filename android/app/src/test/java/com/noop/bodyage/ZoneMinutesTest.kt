package com.noop.bodyage

import com.noop.analytics.HrZones
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ZoneMinutesTest {

    // maxHR 180 -> zone starts 90, 108, 126, 144, 162
    private val zones = HrZones.zones(maxHR = 180.0)
    private val utc = ZoneId.of("UTC")

    private fun trace(startTs: Long, seconds: Int, bpm: Int) =
        (0 until seconds).map { HrSample("d", startTs + it, bpm) }

    @Test
    fun knownTraceGivesKnownMinutes() {
        // 600 s at 130 bpm (zone 3, one bout), 300 s at 170 bpm (zone 5), 120 s at 60 bpm (below zone 1)
        val s = trace(0, 600, 130) + trace(600, 300, 170) + trace(900, 120, 60)
        val split = ZoneMinutes.fromHr(s, zones)
        assertEquals(10.0, split.low, 1e-9)
        assertEquals(5.0, split.high, 1e-9)
        assertEquals(17.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun zoneTwoThreeNeedsATenMinuteBout() {
        assertEquals(0.0, ZoneMinutes.fromHr(trace(0, 540, 130), zones).low, 0.0)
        assertEquals(9.0, ZoneMinutes.fromHr(trace(0, 540, 130), zones).creditedMinutes, 1e-9)
    }

    @Test
    fun aBoutContinuesAcrossZoneTwoAndThree() {
        val s = trace(0, 300, 115) + trace(300, 300, 130) // zone 2 then zone 3, no break
        assertEquals(10.0, ZoneMinutes.fromHr(s, zones).low, 1e-9)
    }

    @Test
    fun zoneOneIsNeverCountedAndBreaksTheBout() {
        // 400 s zone 3, 100 s zone 1 (ordinary walking), 400 s zone 3: two short bouts, nothing counts.
        val s = trace(0, 400, 130) + trace(400, 100, 100) + trace(500, 400, 130)
        assertEquals(0.0, ZoneMinutes.fromHr(s, zones).low, 0.0)
        assertEquals(0.0, ZoneMinutes.fromHr(trace(0, 3600, 100), zones).low, 0.0)
    }

    @Test
    fun aDropoutEndsTheBoutAndIsNotCreditedAsZoneTime() {
        // 700 s zone 3, a 10 minute dropout, 700 s zone 3: two qualifying bouts, the gap adds nothing.
        val s = trace(0, 700, 130) + trace(1300, 700, 130)
        val split = ZoneMinutes.fromHr(s, zones)
        assertEquals(1400.0 / 60.0, split.low, 1e-9)
        assertEquals(1400.0 / 60.0, split.creditedMinutes, 1e-9)
        // The same two stretches at 400 s each are two short bouts and count for nothing.
        assertEquals(0.0, ZoneMinutes.fromHr(trace(0, 400, 130) + trace(1000, 400, 130), zones).low, 0.0)
    }

    @Test
    fun hardZonesCountWithoutABoutRule() {
        assertEquals(4.0, ZoneMinutes.fromHr(trace(0, 240, 170), zones).high, 1e-9)
    }

    @Test
    fun emptyAndDuplicateTimestampsAreSafe() {
        assertEquals(0.0, ZoneMinutes.fromHr(emptyList(), zones).creditedMinutes, 0.0)
        val dup = trace(0, 60, 130) + trace(0, 60, 170)
        assertEquals(1.0, ZoneMinutes.fromHr(dup, zones).creditedMinutes, 1e-9)
    }

    private fun workout(start: Long, mins: Int, sport: String, zonesJson: String? = null, device: String = "d") =
        WorkoutRow(deviceId = device, startTs = start, endTs = start + mins * 60L, sport = sport, source = "test",
            durationS = mins * 60.0, zonesJSON = zonesJson)

    @Test
    fun workoutZonesUseZoneTwoToFivePercentTimesDuration() {
        val w = workout(0, 60, "Running", """{"zone1":10,"zone2":20,"zone3":30,"zone4":30,"zone5":10}""")
        val (low, high) = ZoneMinutes.fromWorkout(w)!!
        assertEquals(30.0, low, 1e-9) // zones 2-3 = 50% of 60 min; zone 1 excluded
        assertEquals(24.0, high, 1e-9)
        assertNull(ZoneMinutes.fromWorkout(workout(0, 60, "Running")))
    }

    @Test
    fun shortWorkoutZoneTwoThreeDoesNotCount() {
        val w = workout(0, 20, "Walking", """{"zone1":40,"zone2":20,"zone3":0,"zone4":0,"zone5":0}""")
        assertEquals(0.0, ZoneMinutes.fromWorkout(w)!!.first, 0.0) // 4 min of zone 2 is under one bout
    }

    @Test
    fun hrDayNeedsEnoughCoverage() {
        val long = ZoneMinutes.hrDay(trace(0, 130 * 60, 130), zones)!!
        assertEquals(ZoneMinutes.Source.HR, long.source)
        assertEquals(130.0, long.low, 1e-6)
        assertNull(ZoneMinutes.hrDay(trace(0, 30 * 60, 130), zones))
    }

    private val day = LocalDate.of(2026, 11, 8)
    private val base = day.atStartOfDay(utc).toEpochSecond()
    private val run = workout(base + 3600, 60, "Running", """{"zone1":0,"zone2":0,"zone3":50,"zone4":50,"zone5":0}""")

    @Test
    fun oneSourceRulePerDay() {
        val hrEntry = ZoneCache.Entry(day, ZoneMinutes.Source.HR, 70.0, 5.0)
        val r = ZoneMinutes.resolve(listOf(day), mapOf(day to hrEntry), listOf(run), utc)
        assertEquals(ZoneMinutes.DayZones(70.0, 5.0, ZoneMinutes.Source.HR), r.zonesByDay[day])
        assertTrue(r.unresolved.isEmpty())

        // Checked, not enough HR: workout zones, resolved.
        val none = ZoneCache.Entry(day, ZoneMinutes.Source.NONE, 0.0, 0.0)
        val r2 = ZoneMinutes.resolve(listOf(day), mapOf(day to none), listOf(run), utc)
        assertEquals(ZoneMinutes.DayZones(30.0, 30.0, ZoneMinutes.Source.WORKOUT), r2.zonesByDay[day])
        assertTrue(r2.unresolved.isEmpty())

        // Never checked: workout zones for now, flagged unresolved so trends skip it.
        val r3 = ZoneMinutes.resolve(listOf(day), emptyMap(), listOf(run), utc)
        assertEquals(setOf(day), r3.unresolved)
        assertEquals(ZoneMinutes.NO_ZONES, ZoneMinutes.resolve(listOf(day), emptyMap(), emptyList(), utc).zonesByDay[day])
    }

    @Test
    fun strengthSportsAreRecognised() {
        listOf("Strength", "Weightlifting", "Functional Fitness", "Powerlifting", "Bodybuilding", "Calisthenics", "Lifting", "Weight Training")
            .forEach { assertTrue(it, ZoneMinutes.isStrength(it)) }
        listOf("Running", "Yoga", "Cycling", "HIIT").forEach { assertFalse(it, ZoneMinutes.isStrength(it)) }
    }

    @Test
    fun dedupeDropsTheSameSessionFromTwoSources() {
        val imported = workout(1000, 60, "Weightlifting", """{"zone1":50,"zone2":50}""", device = "my-whoop")
        val live = workout(1300, 55, "Strength", null, device = "my-whoop-noop")
        val other = workout(100000, 30, "Running")
        val out = ZoneMinutes.dedupeWorkouts(listOf(live, other, imported))
        assertEquals(2, out.size)
        assertEquals("Weightlifting", out[0].sport)
        assertEquals("Running", out[1].sport)
    }

    @Test
    fun dedupeKeepsTwoSeparateSessionsOfTheSameKind() {
        assertEquals(2, ZoneMinutes.dedupeWorkouts(listOf(workout(0, 45, "Strength"), workout(86400, 45, "Strength"))).size)
    }
}
