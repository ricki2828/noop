package com.noop.bodyage

import com.noop.data.MetricSeriesRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DrinksStoreTest {

    private class FakeSeries {
        val rows = LinkedHashMap<Triple<String, String, String>, MetricSeriesRow>()
        fun upsert(newRows: List<MetricSeriesRow>) { for (r in newRows) rows[Triple(r.deviceId, r.day, r.key)] = r }
        fun query(deviceId: String, key: String, from: String, to: String) =
            rows.values.filter { it.deviceId == deviceId && it.key == key && it.day >= from && it.day <= to }.sortedBy { it.day }
    }

    private fun storeOf(f: FakeSeries) = DrinksStore({ f.upsert(it) }, { d, k, a, b -> f.query(d, k, a, b) })

    @Test
    fun tapIsStoredUnderTheForkSourceAndReadBack() = runBlocking {
        val f = FakeSeries()
        val s = storeOf(f)
        s.setDrinks("2026-11-07", 2)
        val row = f.rows.values.single()
        assertEquals("noop-fork-drinks", row.deviceId)
        assertEquals("drinks", row.key)
        assertEquals(2.0, row.value, 0.0)
        assertEquals(2, s.drinks("2026-11-07"))
        assertNull(s.drinks("2026-11-06"))
    }

    @Test
    fun doubleTapIsOneEntryPerDay() = runBlocking {
        val f = FakeSeries()
        val s = storeOf(f)
        s.setDrinks("2026-11-07", 1)
        s.setDrinks("2026-11-07", 3)
        assertEquals(1, f.rows.size)
        assertEquals(3, s.drinks("2026-11-07"))
    }

    @Test
    fun countClampsToZeroThroughFour() = runBlocking {
        val f = FakeSeries()
        val s = storeOf(f)
        s.setDrinks("2026-11-01", -3)
        s.setDrinks("2026-11-02", 9)
        assertEquals(listOf(DrinkEntry("2026-11-01", 0), DrinkEntry("2026-11-02", 4)), s.series())
    }

    @Test
    fun yesterdayKeyFollowsTheLocalDayBoundary() {
        assertEquals("2026-11-07", DrinksStore.yesterdayKey(LocalDate.of(2026, 11, 8)))
        assertEquals("2026-12-31", DrinksStore.yesterdayKey(LocalDate.of(2027, 1, 1)))
        assertEquals("2026-02-28", DrinksStore.yesterdayKey(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun seriesReadsOnlyTheRequestedRangeOldestFirst() = runBlocking {
        val f = FakeSeries()
        val s = storeOf(f)
        s.setDrinks("2026-11-05", 1)
        s.setDrinks("2026-11-03", 0)
        s.setDrinks("2026-10-01", 2)
        assertEquals(listOf("2026-11-03", "2026-11-05"), s.series("2026-11-01", "2026-11-30").map { it.day })
    }
}
