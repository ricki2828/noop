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
        val hrEntry = ZoneCache.Entry(day, ZoneMinutes.Source.HR, 70.0, 5.0, 600.0, 1L, 1L)
        val r = ZoneMinutes.resolve(listOf(day), mapOf(day to hrEntry), listOf(run), utc)
        assertEquals(ZoneMinutes.DayZones(70.0, 5.0, ZoneMinutes.Source.HR), r.zonesByDay[day])
        assertTrue(r.unresolved.isEmpty())

        // Checked, not enough HR: workout zones, resolved.
        val none = ZoneCache.Entry(day, ZoneMinutes.Source.NONE, 0.0, 0.0, 10.0, 1L, 1L)
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

    // ── Varying traces (maxHR 180: zone 1 = 90-107, zone 2 = 108-125, zone 3 = 126-143, zone 4 = 144-161, zone 5 = 162+) ──

    /** Pieces of (seconds, bpm) at 1 Hz, back to back. */
    private fun pieces(vararg p: Pair<Int, Int>): List<HrSample> {
        val out = ArrayList<HrSample>(); var t = 0L
        for ((sec, bpm) in p) { for (i in 0 until sec) out.add(HrSample("d", t + i, bpm)); t += sec }
        return out
    }

    @Test
    fun oscillatingAroundTheZoneOneTwoBoundaryStaysOneBout() {
        // 30 s at 112 (zone 2) then 20 s at 104 (zone 1), 15 times, then a final 30 s in zone 2.
        // Dips of 20 s are tolerated. Zone time = 16 x 30 = 480 s = 8 min; the bout spans 480 + 15 x 20 = 780 s >= 600.
        val p = ArrayList<Pair<Int, Int>>()
        repeat(15) { p.add(30 to 112); p.add(20 to 104) }
        p.add(30 to 112)
        val split = ZoneMinutes.fromHr(pieces(*p.toTypedArray()), zones)
        assertEquals(8.0, split.low, 1e-9)
        assertEquals(13.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun aSixtySecondDipIsToleratedButSixtyOneEndsTheBout() {
        // 400 s zone 2, 60 s dip, 400 s zone 2: one bout, span 860 s, zone time 800 s.
        assertEquals(800.0 / 60.0, ZoneMinutes.fromHr(pieces(400 to 112, 60 to 104, 400 to 112), zones).low, 1e-9)
        // With a 61 s dip the bout ends at the dip: two 400 s bouts, both under 10 minutes.
        assertEquals(0.0, ZoneMinutes.fromHr(pieces(400 to 112, 61 to 104, 400 to 112), zones).low, 0.0)
        // Two 700 s bouts split by a 61 s dip both qualify.
        assertEquals(1400.0 / 60.0, ZoneMinutes.fromHr(pieces(700 to 112, 61 to 104, 700 to 112), zones).low, 1e-9)
    }

    @Test
    fun aTrailingDipDoesNotLengthenTheBout() {
        // 570 s zone 2 then a 30 s dip then nothing: span 570 < 600, so no credit even though 600 s elapsed.
        assertEquals(0.0, ZoneMinutes.fromHr(pieces(570 to 112, 30 to 104), zones).low, 0.0)
    }

    @Test
    fun aDataGapEndsABoutEvenInsideTheDipTolerance() {
        val a = pieces(400 to 112)
        val b = pieces(400 to 112).map { it.copy(ts = it.ts + 500) } // 100 s of no data between the stretches
        assertEquals(0.0, ZoneMinutes.fromHr(a + b, zones).low, 0.0)
    }

    @Test
    fun intervalsWithRecoveriesCountHardTimeAndOnlyTheLongEasyStretch() {
        // 720 s warm-up in zone 2, then 3 x (240 s at 170 hard, 180 s at 115 recovery).
        // Warm-up is one bout of 12 min; the 180 s recoveries are too short for a bout. Hard = 3 x 4 = 12 min.
        val split = ZoneMinutes.fromHr(
            pieces(720 to 115, 240 to 170, 180 to 115, 240 to 170, 180 to 115, 240 to 170, 180 to 115), zones,
        )
        assertEquals(12.0, split.low, 1e-9)
        assertEquals(12.0, split.high, 1e-9)
        assertEquals(33.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun aRiseIntoTheHardZonesEndsTheEasyBoutAndTheTrailingDipIsNotCounted() {
        // 600 s zone 2, a 30 s dip, then 120 s hard: low bout = 10 min exactly, high = 2 min.
        val split = ZoneMinutes.fromHr(pieces(600 to 112, 30 to 104, 120 to 170), zones)
        assertEquals(10.0, split.low, 1e-9)
        assertEquals(2.0, split.high, 1e-9)
    }

    @Test
    fun aTempoRunOnTheZoneThreeFourLineStaysOneBout() {
        // 40 cycles of 20 s at 143 (zone 3) and 10 s at 144 (zone 4), 20 minutes in all, ending on a 10 s zone 4 stretch.
        // Each 10 s move above zone 3 is tolerated and counted as HIGH time: high = 40 x 10 = 400 s.
        // Zone 2-3 time = 40 x 20 = 800 s; the bout spans 800 + 39 recovered excursions x 10 = 1190 s >= 600.
        val p = ArrayList<Pair<Int, Int>>()
        repeat(40) { p.add(20 to 143); p.add(10 to 144) }
        val split = ZoneMinutes.fromHr(pieces(*p.toTypedArray()), zones)
        assertEquals(800.0 / 60.0, split.low, 1e-9)
        assertEquals(400.0 / 60.0, split.high, 1e-9)
        assertEquals(20.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun aFourByFourSessionCountsHardIntervalsAndOnlyBoutLengthEasyTime() {
        // 600 s warm-up (zone 2), 4 x (240 s at 170 hard, 180 s at 130 recovery), 600 s cool-down (zone 2).
        // A 240 s move into zones 4-5 is over the 60 s tolerance, so it ends the bout. The warm-up (600 s) counts.
        // The first three 180 s recoveries are under a bout. The last recovery runs straight into the cool-down:
        // 180 + 600 = 780 s, one bout. Low = 600 + 780 = 1380 s = 23 min; high = 4 x 240 = 960 s = 16 min.
        val p = ArrayList<Pair<Int, Int>>()
        p.add(600 to 120)
        repeat(4) { p.add(240 to 170); p.add(180 to 130) }
        p.add(600 to 120)
        val split = ZoneMinutes.fromHr(pieces(*p.toTypedArray()), zones)
        assertEquals(23.0, split.low, 1e-9)
        assertEquals(16.0, split.high, 1e-9)
        assertEquals(48.0, split.creditedMinutes, 1e-9)
    }

    @Test
    fun aShortSprintInsideAnEasyBoutIsHighTimeAndTheBoutContinues() {
        // 400 s zone 2, 30 s at 170, 400 s zone 2: one bout spanning 830 s with 800 s of zone time; the sprint is high time.
        val split = ZoneMinutes.fromHr(pieces(400 to 115, 30 to 170, 400 to 115), zones)
        assertEquals(800.0 / 60.0, split.low, 1e-9)
        assertEquals(0.5, split.high, 1e-9)
    }
}
