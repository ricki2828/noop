package com.noop.bodyage

import com.noop.analytics.HrZoneSet
import com.noop.analytics.HrZones
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import com.noop.ui.parseZonePercents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Zone minutes and strength sessions for Body Age+. Pure.
 *
 * Source order per local day: the day's HR samples against the profile's zone set when they credit at least
 * [MIN_HR_MINUTES]; otherwise the day's workouts' imported zone percentages times duration. HR gaps are never
 * credited as zone time (see [fromHr]).
 */
object ZoneMinutes {

    /** A gap between consecutive samples longer than this is a dropout, not time in a zone. */
    const val MAX_CREDIT_GAP_S = 60L

    /** A day needs at least this many credited HR minutes before HR is trusted over workout zones. */
    const val MIN_HR_MINUTES = 120.0

    enum class Source { HR, WORKOUT, NONE }

    /** Z1-3 and Z4-5 minutes for one local day, and where they came from. */
    data class DayZones(val low: Double, val high: Double, val source: Source)

    val NO_ZONES = DayZones(0.0, 0.0, Source.NONE)

    /** Minutes in zones 1-3 and 4-5, plus all credited minutes (any zone, including below zone 1). */
    data class HrSplit(val low: Double, val high: Double, val creditedMinutes: Double)

    /**
     * Time in zones from a day's HR samples. Each sample is credited the gap to the next sample only when that
     * gap is at most [MAX_CREDIT_GAP_S]. After a longer gap (strap off, link dropped) the sample is credited a
     * nominal single interval, never the gap. Samples with the same timestamp are collapsed.
     */
    fun fromHr(samples: List<HrSample>, zones: HrZoneSet): HrSplit {
        val sorted = samples.sortedBy { it.ts }.distinctBy { it.ts }
        if (sorted.isEmpty()) return HrSplit(0.0, 0.0, 0.0)
        val nominal = minOf(HrZones.medianInterval(sorted), MAX_CREDIT_GAP_S.toDouble())
        var low = 0.0
        var high = 0.0
        var credited = 0.0
        for (i in sorted.indices) {
            val gap = if (i < sorted.size - 1) (sorted[i + 1].ts - sorted[i].ts).toDouble() else Double.NaN
            val dur = if (!gap.isNaN() && gap > 0 && gap <= MAX_CREDIT_GAP_S) gap else nominal
            credited += dur
            when (zones.zoneNumber(sorted[i].bpm.toDouble())) {
                1, 2, 3 -> low += dur
                4, 5 -> high += dur
            }
        }
        return HrSplit(low / 60.0, high / 60.0, credited / 60.0)
    }

    /** Z1-3 / Z4-5 minutes from imported per-workout zone percentages. Null when the row carries no zones. */
    fun fromWorkout(row: WorkoutRow): Pair<Double, Double>? {
        val pct = parseZonePercents(row.zonesJSON) ?: return null
        val durS = row.durationS ?: (row.endTs - row.startTs).toDouble()
        if (durS <= 0) return null
        val total = pct.sum()
        if (total <= 0) return null
        val minutes = durS / 60.0
        // Percentages are taken as given: they need not sum to 100 (time below zone 1 is omitted).
        val low = (pct[0] + pct[1] + pct[2]) / 100.0 * minutes
        val high = (pct[3] + pct[4]) / 100.0 * minutes
        return low to high
    }

    fun localDay(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

    /**
     * Zones for each day in [days]. [hrByDay] holds HR samples only for the days the caller loaded them for;
     * every other day falls back to [workouts] (deduped by the caller). A day with neither is [NO_ZONES].
     */
    fun byDay(
        days: List<LocalDate>,
        hrByDay: Map<LocalDate, List<HrSample>>,
        workouts: List<WorkoutRow>,
        zones: HrZoneSet?,
        zone: ZoneId,
    ): Map<LocalDate, DayZones> {
        val workoutsByDay = workouts.groupBy { localDay(it.startTs, zone) }
        val out = LinkedHashMap<LocalDate, DayZones>()
        for (d in days) {
            val hr = if (zones != null) hrByDay[d].orEmpty() else emptyList()
            val split = if (hr.isNotEmpty() && zones != null) fromHr(hr, zones) else null
            if (split != null && split.creditedMinutes >= MIN_HR_MINUTES) {
                out[d] = DayZones(split.low, split.high, Source.HR)
                continue
            }
            var low = 0.0
            var high = 0.0
            var any = false
            for (w in workoutsByDay[d].orEmpty()) {
                val z = fromWorkout(w) ?: continue
                low += z.first; high += z.second; any = true
            }
            out[d] = if (any) DayZones(low, high, Source.WORKOUT) else NO_ZONES
        }
        return out
    }

    // ── Strength ──

    private val STRENGTH_WORDS = listOf("strength", "weightlift", "weight lift", "powerlift", "bodybuild", "functional", "calisthenic")

    /** Strength / weightlifting / functional training, by sport label (Health Connect and WHOOP CSV spellings). */
    fun isStrength(sport: String): Boolean {
        val s = sport.lowercase()
        return STRENGTH_WORDS.any { s.contains(it) }
    }

    private fun sportClass(sport: String) = if (isStrength(sport)) "strength" else sport.lowercase()

    /**
     * Drops duplicate rows of one session (imported + live-detected, or two imports): same sport class and the
     * overlap covers at least half of the shorter session. Rows carrying zones win, then the longer row.
     */
    fun dedupeWorkouts(rows: List<WorkoutRow>): List<WorkoutRow> {
        val ordered = rows.sortedWith(
            compareByDescending<WorkoutRow> { it.zonesJSON != null }
                .thenByDescending { it.endTs - it.startTs }
                .thenBy { it.startTs },
        )
        val kept = ArrayList<WorkoutRow>()
        for (r in ordered) {
            val dup = kept.any { k ->
                if (sportClass(k.sport) != sportClass(r.sport)) return@any false
                val overlap = minOf(k.endTs, r.endTs) - maxOf(k.startTs, r.startTs)
                val shorter = minOf(k.endTs - k.startTs, r.endTs - r.startTs).coerceAtLeast(1)
                overlap > 0 && overlap * 2 >= shorter
            }
            if (!dup) kept.add(r)
        }
        return kept.sortedBy { it.startTs }
    }
}
