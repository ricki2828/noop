package com.noop.bodyage

import com.noop.data.MetricSeriesRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun writeThenReadRoundTripsUnderTheForkSource() = runBlocking {
        val f = Fake()
        val c = ZoneCache({ f.upsert(it) }, { d, k, a, b -> f.query(d, k, a, b) })
        c.write(listOf(
            ZoneCache.Entry(last, ZoneMinutes.Source.HR, 62.5, 8.0),
            ZoneCache.Entry(last.minusDays(1), ZoneMinutes.Source.NONE, 0.0, 0.0),
        ))
        assertTrue(f.rows.keys.all { it.first == "noop-fork-bodyage" })
        assertEquals(setOf("zone23_min", "zone45_min", "zone_src"), f.rows.keys.map { it.third }.toSet())
        val back = c.read(last.minusDays(5), last)
        assertEquals(ZoneCache.Entry(last, ZoneMinutes.Source.HR, 62.5, 8.0), back[last])
        assertEquals(ZoneMinutes.Source.NONE, back[last.minusDays(1)]!!.source)
        assertEquals(2, back.size)
    }

    @Test
    fun planReadsNewestUncachedDaysFirstWithinTheBudget() {
        val days = (29 downTo 0).map { last.minusDays(it.toLong()) } // 30 days, oldest first
        val cached = days.take(10).associateWith { ZoneCache.Entry(it, ZoneMinutes.Source.HR, 1.0, 1.0) }
        val plan = ZoneCache.plan(days, cached, last, budget = 14)
        assertEquals(14, plan.toCompute.size)
        assertEquals(last, plan.toCompute.first())
        assertEquals(last.minusDays(13), plan.toCompute.last())
        assertEquals(6, plan.remaining) // 20 uncached - 14 taken
    }

    @Test
    fun recentNotEnoughHrDaysAreRecheckedButNeverCountAsRemaining() {
        val days = (29 downTo 0).map { last.minusDays(it.toLong()) }
        val cached = days.associateWith { ZoneCache.Entry(it, ZoneMinutes.Source.NONE, 0.0, 0.0) }
        val plan = ZoneCache.plan(days, cached, last)
        assertEquals(14, plan.toCompute.size) // only the last 14 days, not the older ones
        assertTrue(plan.toCompute.all { !it.isBefore(last.minusDays(13)) })
        assertEquals(0, plan.remaining)
    }

    @Test
    fun cachedHrDaysAreNeverRecomputed() {
        val days = (6 downTo 0).map { last.minusDays(it.toLong()) }
        val cached = days.associateWith { ZoneCache.Entry(it, ZoneMinutes.Source.HR, 1.0, 1.0) }
        assertEquals(emptyList<LocalDate>(), ZoneCache.plan(days, cached, last).toCompute)
    }
}
