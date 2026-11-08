package com.noop.bodyage

import com.noop.analytics.HrZoneSet
import com.noop.data.HrSample
import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopRepository
import java.time.LocalDate
import java.util.Locale

/**
 * Per-day cache of HR-derived zone minutes, in the generic metric series (no migration), so completed days are
 * not re-read from raw HR on every load. Source id [DEVICE_ID]; keys: [KEY_SRC] (1 = HR, 0 = not enough HR),
 * [KEY_LOW] (zone 2-3 bout minutes), [KEY_HIGH] (zone 4-5 minutes), [KEY_CREDITED] (all credited HR minutes),
 * [KEY_COUNT] (HR sample count when read) and [KEY_SIG] (rule version + zone bounds, see [signature]).
 *
 * Staleness: a row whose signature differs from the current one is ignored (the day goes back through the
 * bounded backfill). Days in the last [RECHECK_DAYS] are cheaply re-checked by sample count every load, because
 * a strap history offload can add HR to recent days after the fact; when the count grew the day is re-read and
 * the row is replaced if the fresh read credits more minutes.
 */
class ZoneCache(
    private val upsertRows: suspend (List<MetricSeriesRow>) -> Unit,
    private val queryRows: suspend (deviceId: String, key: String, from: String, to: String) -> List<MetricSeriesRow>,
) {
    constructor(repo: WhoopRepository) : this(
        { rows -> repo.upsertMetricSeries(rows) },
        { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
    )

    data class Entry(
        val day: LocalDate,
        val source: ZoneMinutes.Source,
        val low: Double,
        val high: Double,
        val credited: Double,
        val hrCount: Long,
        val signature: Long,
    )

    suspend fun read(from: LocalDate, to: LocalDate): Map<LocalDate, Entry> {
        val f = from.toString(); val t = to.toString()
        fun rows(key: String) = queryRows(DEVICE_ID, key, f, t).associate { it.day to it.value }
        val src = rows(KEY_SRC); val low = rows(KEY_LOW); val high = rows(KEY_HIGH)
        val credited = rows(KEY_CREDITED); val count = rows(KEY_COUNT); val sig = rows(KEY_SIG)
        val out = HashMap<LocalDate, Entry>()
        for ((day, code) in src) {
            val d = LocalDate.parse(day)
            out[d] = Entry(
                d, if (code >= 1.0) ZoneMinutes.Source.HR else ZoneMinutes.Source.NONE,
                low[day] ?: 0.0, high[day] ?: 0.0, credited[day] ?: 0.0,
                (count[day] ?: 0.0).toLong(), (sig[day] ?: 0.0).toLong(),
            )
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
                MetricSeriesRow(DEVICE_ID, day, KEY_CREDITED, it.credited),
                MetricSeriesRow(DEVICE_ID, day, KEY_COUNT, it.hrCount.toDouble()),
                MetricSeriesRow(DEVICE_ID, day, KEY_SIG, it.signature.toDouble()),
            )
        })
    }

    /**
     * [toRead]: never-cached (or stale-signature) days to read now, within the budget. [remaining]: such days
     * left over. [toRecheck]: cached days in the recent window, to compare by sample count.
     */
    data class Plan(val toRead: List<LocalDate>, val remaining: Int, val toRecheck: List<LocalDate>)

    companion object {
        const val DEVICE_ID = "noop-fork-bodyage"
        const val KEY_LOW = "zone23_min"
        const val KEY_HIGH = "zone45_min"
        const val KEY_SRC = "zone_src"
        const val KEY_CREDITED = "credited_min"
        const val KEY_COUNT = "hr_count"
        const val KEY_SIG = "zone_sig"
        const val BUDGET = 14
        const val RECHECK_DAYS = 14L

        /** Bump when the zone rule changes (bout length, dip tolerance, zone numbers): every cached day is then redone. */
        const val RULE_VERSION = 2

        /** FNV-1a (32-bit) over the UTF-16 code units of [s]: platform-neutral, stable across runs. */
        fun fnv1a(s: String): Long {
            var h = 0x811C9DC5L
            for (c in s) { h = h xor c.code.toLong(); h = (h * 0x01000193L) and 0xFFFFFFFFL }
            return h
        }

        /** Signature of the rule version and the zone bounds in effect (millibpm precision). */
        fun signature(zones: HrZoneSet): Long =
            fnv1a("r$RULE_VERSION|" + zones.zones.joinToString(",") { String.format(Locale.ROOT, "%.3f", it.lower) })

        /** Builds a cache row from one day's HR samples. */
        fun entryFor(day: LocalDate, samples: List<HrSample>, zones: HrZoneSet, hrCount: Long): Entry {
            val split = ZoneMinutes.fromHr(samples, zones)
            val hr = split.creditedMinutes >= ZoneMinutes.MIN_HR_MINUTES
            return Entry(
                day, if (hr) ZoneMinutes.Source.HR else ZoneMinutes.Source.NONE,
                if (hr) split.low else 0.0, if (hr) split.high else 0.0, split.creditedMinutes, hrCount, signature(zones),
            )
        }

        /** Sum of the per-source sample counts in a `WhoopRepository.hrUnionFingerprint` string ("id=count:maxTs,..."). */
        fun countFromFingerprint(fp: String): Long =
            fp.split(",").filter { it.contains("=") }.sumOf { it.substringAfter("=").substringBefore(":").toLongOrNull() ?: 0L }

        /**
         * Never-cached days first (the caller's [priority] days, then newest first), within [budget].
         * [remaining] counts only never-cached days left over, so a caller reloading until it reaches 0 terminates.
         * [cached] must already be filtered to the current signature.
         */
        fun plan(
            days: List<LocalDate>,
            cached: Map<LocalDate, Entry>,
            lastDay: LocalDate,
            budget: Int = BUDGET,
            priority: List<LocalDate> = emptyList(),
        ): Plan {
            val prio = priority.toSet()
            val unchecked = days.filter { cached[it] == null }
                .sortedWith(compareByDescending<LocalDate> { it in prio }.thenByDescending { it })
            val take = unchecked.take(budget)
            val recheck = days.filter { it in cached && !it.isBefore(lastDay.minusDays(RECHECK_DAYS - 1)) }.sortedDescending()
            return Plan(take, unchecked.size - take.size, recheck)
        }
    }
}
