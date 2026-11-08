package com.noop.bodyage

import com.noop.analytics.HrZones
import com.noop.data.MetricSeriesRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ZoneCacheTest {

    private class Fake {
        val rows = LinkedHashMap<Triple<String, String, String>, MetricSeriesRow>()
        fun upsert(r: List<MetricSeriesRow>) { for (x in r) rows[Triple(x.deviceId, x.day, x.key)] = x }
        fun query(d: String, k: String, a: String, b: String) = rows.values.filter { it.deviceId == d && it.key == k && it.day >= a && it.day <= b }
    }

    private val last = LocalDate.of(2026, 11, 7)
    private fun entry(d: LocalDate, src: ZoneMinutes.Source = ZoneMinutes.Source.HR) = ZoneCache.Entry(d, src, 1.0, 1.0, 130.0, 7800L, 99L)

    @Test
    fun writeThenReadRoundTripsUnderTheForkSource() = runBlocking {
        val f = Fake()
        val c = ZoneCache({ f.upsert(it) }, { d, k, a, b -> f.query(d, k, a, b) })
        val hr = ZoneCache.Entry(last, ZoneMinutes.Source.HR, 62.5, 8.0, 130.0, 7800L, 12345L)
        c.write(listOf(hr, entry(last.minusDays(1), ZoneMinutes.Source.NONE)))
        assertTrue(f.rows.keys.all { it.first == "noop-fork-bodyage" })
        assertEquals(
            setOf("zone23_min", "zone45_min", "zone_src", "credited_min", "hr_count", "zone_sig"),
            f.rows.keys.map { it.third }.toSet(),
        )
        val back = c.read(last.minusDays(5), last)
        assertEquals(hr, back[last])
        assertEquals(ZoneMinutes.Source.NONE, back[last.minusDays(1)]!!.source)
        assertEquals(2, back.size)
    }

    @Test
    fun planReadsNewestUncachedDaysFirstWithinTheBudget() {
        val days = (29 downTo 0).map { last.minusDays(it.toLong()) } // 30 days, oldest first
        val cached = days.take(10).associateWith { entry(it) }
        val plan = ZoneCache.plan(days, cached, last, budget = 14)
        assertEquals(14, plan.toRead.size)
        assertEquals(last, plan.toRead.first())
        assertEquals(last.minusDays(13), plan.toRead.last())
        assertEquals(6, plan.remaining) // 20 uncached - 14 taken
    }

    @Test
    fun priorityDaysAreReadBeforeNewerOnes() {
        val days = (29 downTo 0).map { last.minusDays(it.toLong()) }
        val old = last.minusDays(25)
        val plan = ZoneCache.plan(days, emptyMap(), last, budget = 3, priority = listOf(old))
        assertEquals(old, plan.toRead.first())
    }

    @Test
    fun recentCachedDaysOfEitherKindAreRecheckedOlderOnesAreNot() {
        val days = (29 downTo 0).map { last.minusDays(it.toLong()) }
        val cached = days.associateWith { entry(it, if (it.dayOfMonth % 2 == 0) ZoneMinutes.Source.HR else ZoneMinutes.Source.NONE) }
        val plan = ZoneCache.plan(days, cached, last)
        assertEquals(emptyList<LocalDate>(), plan.toRead)
        assertEquals(0, plan.remaining)
        assertEquals(14, plan.toRecheck.size)
        assertTrue(plan.toRecheck.all { !it.isBefore(last.minusDays(13)) })
    }

    @Test
    fun signatureChangesWithTheZoneBoundsAndIsStable() {
        val a = HrZones.zones(maxHR = 180.0)
        val b = HrZones.zones(maxHR = 181.0)
        assertEquals(ZoneCache.signature(a), ZoneCache.signature(HrZones.zones(maxHR = 180.0)))
        assertNotEquals(ZoneCache.signature(a), ZoneCache.signature(b))
        assertNotEquals(ZoneCache.signature(a), ZoneCache.signature(HrZones.zones(maxHR = 180.0, customLowerBounds = listOf(100.0, 115.0, 130.0, 145.0, 160.0))))
    }

    @Test
    fun fnv1aMatchesTheKnownVectors() {
        assertEquals(0x811C9DC5L, ZoneCache.fnv1a(""))
        assertEquals(0xE40C292CL, ZoneCache.fnv1a("a"))
        assertEquals(0xBF9CF968L, ZoneCache.fnv1a("foobar"))
    }

    @Test
    fun fingerprintCountsAreSummedAcrossSources() {
        assertEquals(0L, ZoneCache.countFromFingerprint(""))
        assertEquals(7800L + 120L, ZoneCache.countFromFingerprint("my-whoop=7800:1731000000,whoop-abc=120:1731000100"))
    }
}
