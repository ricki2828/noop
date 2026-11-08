package com.noop.bodyage

import com.noop.analytics.HrZones
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 600 s at 130 bpm (zone 3), 300 s at 170 bpm (zone 5), 120 s at 60 bpm (below zone 1)
        val s = trace(0, 600, 130) + trace(600, 300, 170) + trace(900, 120, 60)
        val split = ZoneMinutes.fromHr(s, zones)
        assertEquals(10.0, split.low, 1e-9)
        assertEquals(5.0, split.high, 1e-9)
        assertEquals(17.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun hrGapsAreNotCountedAsZoneTime() {
        // 60 s in zone 4, then a 10 minute dropout, then 60 s in zone 4 again.
        val s = trace(0, 60, 150) + trace(660, 60, 150)
        val split = ZoneMinutes.fromHr(s, zones)
        assertEquals(2.0, split.high, 1e-9)
        assertEquals(2.0, split.creditedMinutes, 1e-9)
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
    fun workoutZonesUsePercentTimesDuration() {
        val w = workout(0, 60, "Running", """{"zone1":10,"zone2":20,"zone3":30,"zone4":30,"zone5":10}""")
        val (low, high) = ZoneMinutes.fromWorkout(w)!!
        assertEquals(36.0, low, 1e-9)
        assertEquals(24.0, high, 1e-9)
        assertEquals(null, ZoneMinutes.fromWorkout(workout(0, 60, "Running")))
    }

    @Test
    fun hrDayWinsOnlyWithEnoughCoverage() {
        val day = LocalDate.of(2026, 11, 8)
        val base = day.atStartOfDay(utc).toEpochSecond()
        val longHr = trace(base, 130 * 60, 130) // 130 min in zone 3
        val shortHr = trace(base, 30 * 60, 130)
        val w = workout(base + 3600, 60, "Running", """{"zone1":0,"zone2":0,"zone3":50,"zone4":50,"zone5":0}""")

        val hrDay = ZoneMinutes.byDay(listOf(day), mapOf(day to longHr), listOf(w), zones, utc)[day]!!
        assertEquals(ZoneMinutes.Source.HR, hrDay.source)
        assertEquals(130.0, hrDay.low, 1e-6)

        val fallback = ZoneMinutes.byDay(listOf(day), mapOf(day to shortHr), listOf(w), zones, utc)[day]!!
        assertEquals(ZoneMinutes.Source.WORKOUT, fallback.source)
        assertEquals(30.0, fallback.low, 1e-9)
        assertEquals(30.0, fallback.high, 1e-9)

        val none = ZoneMinutes.byDay(listOf(day), emptyMap(), emptyList(), zones, utc)[day]!!
        assertEquals(ZoneMinutes.Source.NONE, none.source)
    }

    @Test
    fun missingThresholdsIgnoreHrAndUseWorkoutsOnly() {
        val day = LocalDate.of(2026, 11, 8)
        val base = day.atStartOfDay(utc).toEpochSecond()
        val r = ZoneMinutes.byDay(listOf(day), mapOf(day to trace(base, 130 * 60, 130)), emptyList(), null, utc)[day]!!
        assertEquals(ZoneMinutes.Source.NONE, r.source)
    }

    @Test
    fun strengthSportsAreRecognised() {
        listOf("Strength", "Weightlifting", "Functional Fitness", "Powerlifting", "Bodybuilding", "Calisthenics", "Lifting")
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
        val a = workout(0, 45, "Strength")
        val b = workout(86400, 45, "Strength")
        assertEquals(2, ZoneMinutes.dedupeWorkouts(listOf(a, b)).size)
    }
}
