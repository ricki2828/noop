package com.noop.bodyage

import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopRepository
import java.time.LocalDate

/**
 * Per-day cache of HR-derived zone minutes, in the generic metric series (no migration), so completed days are
 * never recomputed from raw HR. Source id [DEVICE_ID], keys [KEY_LOW] (zone 2-3 bout minutes), [KEY_HIGH]
 * (zone 4-5 minutes) and [KEY_SRC] (1 = HR, 0 = checked and not enough HR). Only completed days are written.
 * A "not enough HR" verdict is re-checked for the last [RECHECK_DAYS] days, since a strap history offload can
 * add HR to recent days after the fact.
 */
class ZoneCache(
    private val upsertRows: suspend (List<MetricSeriesRow>) -> Unit,
    private val queryRows: suspend (deviceId: String, key: String, from: String, to: String) -> List<MetricSeriesRow>,
) {
    constructor(repo: WhoopRepository) : this(
        { rows -> repo.upsertMetricSeries(rows) },
        { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
    )

    data class Entry(val day: LocalDate, val source: ZoneMinutes.Source, val low: Double, val high: Double)

    suspend fun read(from: LocalDate, to: LocalDate): Map<LocalDate, Entry> {
        val f = from.toString(); val t = to.toString()
        val src = queryRows(DEVICE_ID, KEY_SRC, f, t).associate { it.day to it.value }
        val low = queryRows(DEVICE_ID, KEY_LOW, f, t).associate { it.day to it.value }
        val high = queryRows(DEVICE_ID, KEY_HIGH, f, t).associate { it.day to it.value }
        val out = HashMap<LocalDate, Entry>()
        for ((day, code) in src) {
            val d = LocalDate.parse(day)
            out[d] = Entry(d, if (code >= 1.0) ZoneMinutes.Source.HR else ZoneMinutes.Source.NONE, low[day] ?: 0.0, high[day] ?: 0.0)
        }
        return out
    }

    suspend fun write(entries: List<Entry>) {
        if (entries.isEmpty()) return
        upsertRows(entries.flatMap {
            val day = it.day.toString()
            listOf(
                MetricSeriesRow(DEVICE_ID, day, KEY_SRC, if (it.source == ZoneMinutes.Source.HR) 1.0 else 0.0),
                MetricSeriesRow(DEVICE_ID, day, KEY_LOW, it.low),
                MetricSeriesRow(DEVICE_ID, day, KEY_HIGH, it.high),
            )
        })
    }

    /** Days to read this load (newest first, at most the budget) and how many never-checked days remain after. */
    data class Plan(val toCompute: List<LocalDate>, val remaining: Int)

    companion object {
        const val DEVICE_ID = "noop-fork-bodyage"
        const val KEY_LOW = "zone23_min"
        const val KEY_HIGH = "zone45_min"
        const val KEY_SRC = "zone_src"
        const val BUDGET = 14
        const val RECHECK_DAYS = 14L

        /**
         * Never-checked days come first (the caller's [priority] days, then newest first),
         * then recent "not enough HR" days to re-check with whatever budget is left. [remaining] counts only
         * never-checked days left over, so a caller that reloads until it reaches 0 always terminates.
         */
        fun plan(
            days: List<LocalDate>,
            cached: Map<LocalDate, Entry>,
            lastDay: LocalDate,
            budget: Int = BUDGET,
            priority: List<LocalDate> = emptyList(),
        ): Plan {
            val prio = priority.toSet()
            // Priority days first (newest first among them), then everything else newest first.
            val unchecked = days.filter { cached[it] == null }
                .sortedWith(compareByDescending<LocalDate> { it in prio }.thenByDescending { it })
            val recheck = days.filter { d ->
                cached[d]?.source == ZoneMinutes.Source.NONE && !d.isBefore(lastDay.minusDays(RECHECK_DAYS - 1))
            }.sortedDescending()
            val take = (unchecked + recheck).take(budget)
            return Plan(take, (unchecked.size - take.count { cached[it] == null }).coerceAtLeast(0))
        }
    }
}
