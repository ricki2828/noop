package com.noop.bodyage

import com.noop.analytics.HrZones
import com.noop.bodyage.BodyAgePlus.FactorKey
import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.MetricSeriesRow
import com.noop.data.WorkoutRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Loader orchestration against a fake [BodyAgeData] and in-memory stores (no database). */
class BodyAgeLoaderDataTest {

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 8)
    private val end = today.minusDays(1)
    private val zoneSet = HrZones.zones(maxHR = 180.0)
    private var profile = BodyAgeProfile(46.0, "male", zoneSet)

    private class Series {
        val rows = LinkedHashMap<Triple<String, String, String>, MetricSeriesRow>()
        var writes = 0
        fun upsert(r: List<MetricSeriesRow>) { writes++; for (x in r) rows[Triple(x.deviceId, x.day, x.key)] = x }
        fun query(d: String, k: String, a: String, b: String) = rows.values.filter { it.deviceId == d && it.key == k && it.day >= a && it.day <= b }
    }

    private class Fake(val workoutRows: List<WorkoutRow> = emptyList(), hrDays: Map<LocalDate, Int> = emptyMap(), val zone: ZoneId) : BodyAgeData {
        val hr = hrDays.toMutableMap()
        val hrQueried = ArrayList<LocalDate>()
        override suspend fun hrCount(fromSec: Long, toSec: Long): Long =
            (hr[java.time.Instant.ofEpochSecond(fromSec).atZone(zone).toLocalDate()] ?: 0) * 60L
        var failHrAfter = Int.MAX_VALUE
        override suspend fun workouts(fromSec: Long, toSec: Long) = workoutRows
        override suspend fun vo2(from: String, to: String) = emptyList<MetricSeriesRow>()
        override suspend fun hrSamples(fromSec: Long, toSec: Long): List<HrSample> {
            if (hrQueried.size >= failHrAfter) throw CancellationException("cancelled")
            val day = java.time.Instant.ofEpochSecond(fromSec).atZone(zone).toLocalDate()
            hrQueried.add(day)
            val minutes = hr[day] ?: return emptyList()
            return (0 until minutes * 60).map { HrSample("d", fromSec + it, 130) } // zone 3 for maxHR 180
        }
    }

    private fun dayRows(n: Int, steps: Int = 8000) = (1..n).map {
        DailyMetric("d", end.minusDays((it - 1).toLong()).toString(), totalSleepMin = 450.0, restingHr = 56, avgHrv = 40.0, steps = steps)
    }

    private fun stores(): Triple<Series, DrinksStore, ZoneCache> {
        val s = Series()
        return Triple(
            s,
            DrinksStore({ s.upsert(it) }, { d, k, a, b -> s.query(d, k, a, b) }),
            ZoneCache({ s.upsert(it) }, { d, k, a, b -> s.query(d, k, a, b) }),
        )
    }

    private fun workout(day: LocalDate, sport: String, zonesJson: String? = null, device: String = "my-whoop", mins: Int = 60): WorkoutRow {
        val start = day.atStartOfDay(utc).toEpochSecond() + 3600
        return WorkoutRow(device, start, start + mins * 60L, sport, "test", durationS = mins * 60.0, zonesJSON = zonesJson)
    }

    private fun load(data: BodyAgeData, days: List<DailyMetric>, st: Triple<Series, DrinksStore, ZoneCache>) =
        runBlocking { BodyAgeLoader.load(data, profile, days, st.second, st.third, today, utc) }

    @Test
    fun todaysPartialRowIsExcluded() {
        val rows = dayRows(7, steps = 8000) + DailyMetric("d", today.toString(), totalSleepMin = 450.0, restingHr = 56, steps = 300)
        val s = load(Fake(zone = utc), rows, stores())
        assertEquals(end, s.end)
        assertEquals(8000.0, s.result.factors.single { it.key == FactorKey.STEPS }.actual, 1e-9)
    }

    @Test
    fun aCancelledLoadWritesNothing() {
        val st = stores()
        val data = Fake(zone = utc).also { it.failHrAfter = 3 }
        try {
            load(data, dayRows(7), st)
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected: propagated, not swallowed
        }
        assertEquals(0, st.first.writes)
        assertTrue(st.first.rows.isEmpty())
    }

    @Test
    fun cachedDaysAreNotReadAgain() {
        val st = stores()
        val data = Fake(hrDays = mapOf(end to 130), zone = utc)
        load(data, dayRows(7), st)
        assertTrue(end in data.hrQueried)
        data.hrQueried.clear()
        load(data, dayRows(7), st)
        assertFalse(end in data.hrQueried) // an HR day in the cache is never recomputed
    }

    @Test
    fun hrAndWorkoutDaysFollowOneRuleAcrossWeeks() {
        val oldWeekDay = end.minusDays(25) // inside the window that ends 21 days before `end`
        val run = """{"zone1":0,"zone2":0,"zone3":50,"zone4":50,"zone5":0}"""
        val data = Fake(
            workoutRows = listOf(workout(end.minusDays(2), "Running", run), workout(oldWeekDay, "Running", run)),
            hrDays = mapOf(end to 130),
            zone = utc,
        )
        val st = stores()
        val rows = dayRows(60)
        val first = load(data, rows, st)
        // Headline week: HR day (130 min zone 2-3 bout) + workout day (30 + 30): the same rule everywhere.
        assertEquals(160.0, first.result.factors.single { it.key == FactorKey.ZONE_LOW }.actual, 1e-6)
        assertEquals(30.0, first.result.factors.single { it.key == FactorKey.ZONE_HIGH }.actual, 1e-6)
        assertTrue(first.backfillingDays > 0)
        assertTrue(first.trend.none { it.day == end.minusDays(21) }) // still being read: left out, not mixed

        var last = first
        var guard = 0
        while (last.backfillingDays > 0 && guard++ < 60) last = load(data, rows, st)
        assertEquals(0, last.backfillingDays)
        assertTrue(last.trend.any { it.day == end.minusDays(21) })
        assertEquals(160.0, last.result.factors.single { it.key == FactorKey.ZONE_LOW }.actual, 1e-6)
    }

    @Test
    fun priorityWindowsAreReadFirst() {
        val data = Fake(zone = utc)
        load(data, dayRows(7), stores())
        val first14 = data.hrQueried.toSet()
        val expected = ((0..6).map { end.minusDays(it.toLong()) } + (0..6).map { end.minusDays(30).minusDays(it.toLong()) }).toSet()
        assertEquals(expected, first14)
    }

    @Test
    fun workoutsFromSeveralSourcesAreDedupedAndCountedOnce() {
        val day = end.minusDays(1)
        val data = Fake(
            workoutRows = listOf(
                workout(day, "Weightlifting", device = "my-whoop"),
                workout(day, "Strength", device = "health-connect"),
                workout(end.minusDays(3), "Running", device = "apple-health"),
            ),
            zone = utc,
        )
        val s = load(data, dayRows(7), stores())
        assertEquals(1.0, s.result.factors.single { it.key == FactorKey.STRENGTH }.actual, 0.0)
    }

    @Test
    fun noWorkoutsAnywhereLeavesStrengthNotCounted() {
        val s = load(Fake(zone = utc), dayRows(7), stores())
        assertNull(s.result.factors.firstOrNull { it.key == FactorKey.STRENGTH })
        assertTrue(s.result.notCounted.any { it.key == FactorKey.STRENGTH })
    }

    private fun lowOf(s: BodyAgeSnapshot) = s.result.factors.single { it.key == FactorKey.ZONE_LOW }.actual

    @Test
    fun aLateOffloadOntoACachedHrDayIsPickedUp() {
        val st = stores()
        val data = Fake(hrDays = mapOf(end to 130), zone = utc)
        val rows = dayRows(7)
        assertEquals(130.0, lowOf(load(data, rows, st)), 1e-6)
        data.hr[end] = 200 // the strap's history offload lands more heart rate for that day
        assertEquals(200.0, lowOf(load(data, rows, st)), 1e-6)
        assertTrue(st.third.let { runBlocking { it.read(end, end)[end]!!.credited } } >= 200.0)
    }

    @Test
    fun aLateOffloadTurnsANotEnoughHrDayIntoAnHrDay() {
        val st = stores()
        val data = Fake(hrDays = mapOf(end to 30), zone = utc) // 30 min: under the 120 min coverage floor
        val rows = dayRows(7)
        assertNull(load(data, rows, st).result.factors.firstOrNull { it.key == FactorKey.ZONE_LOW })
        data.hr[end] = 150
        assertEquals(150.0, lowOf(load(data, rows, st)), 1e-6)
    }

    @Test
    fun anUnchangedCountDoesNotReReadTheDay() {
        val st = stores()
        val data = Fake(hrDays = mapOf(end to 130), zone = utc)
        load(data, dayRows(7), st)
        data.hrQueried.clear()
        load(data, dayRows(7), st)
        assertFalse(end in data.hrQueried)
    }

    @Test
    fun changedZoneBoundsRecomputeTheCachedDays() {
        val st = stores()
        // Zone set A (max 180): 130 bpm is zone 3. Zone set B (max 220): zone 1 spans 110-131, so 130 bpm is zone 1: no low minutes.
        val data = Fake(hrDays = mapOf(end to 130), zone = utc)
        val rows = dayRows(7)
        assertEquals(130.0, lowOf(load(data, rows, st)), 1e-6)
        profile = BodyAgeProfile(46.0, "male", HrZones.zones(maxHR = 220.0))
        data.hrQueried.clear()
        val after = load(data, rows, st)
        assertTrue(end in data.hrQueried) // the stale-signature row was ignored and the day read again
        assertEquals(0.0, lowOf(after), 1e-9)
    }
}
