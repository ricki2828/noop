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
 * Two intensities, both counted per local day:
 *  - "low" = zones 2-3 only (zone 1 is ordinary walking), counted only inside continuous bouts of at least
 *    [MIN_BOUT_S]; a gap longer than [MAX_CREDIT_GAP_S] or a drop out of zones 2-3 ends a bout;
 *  - "high" = zones 4-5, every minute counts (intervals are shorter than a bout).
 *
 * One source rule for every window: a day uses its HR samples when they credit at least [MIN_HR_MINUTES]
 * ([hrDay]), otherwise the day's workouts' imported zone percentages times duration ([fromWorkout]). HR gaps
 * are never credited as zone time (see [fromHr]).
 */
object ZoneMinutes {

    /** A gap between consecutive samples longer than this is a dropout, not time in a zone. */
    const val MAX_CREDIT_GAP_S = 60L

    /** A day needs at least this many credited HR minutes before HR is trusted over workout zones. */
    const val MIN_HR_MINUTES = 120.0

    /** Zone 2-3 time only counts inside an unbroken bout at least this long (elapsed, tolerated dips included). */
    const val MIN_BOUT_S = 600.0

    /** Time away from zones 2-3 (a dip below, or a move into zones 4-5) up to this long does not end a bout. */
    const val MAX_DIP_S = 60.0

    enum class Source { HR, WORKOUT, NONE }

    /** Zone 2-3 (bouts) and zone 4-5 minutes for one local day, and where they came from. */
    data class DayZones(val low: Double, val high: Double, val source: Source)

    val NO_ZONES = DayZones(0.0, 0.0, Source.NONE)

    /** Minutes in zone 2-3 bouts and zones 4-5, plus all credited minutes (any zone, including below zone 2). */
    data class HrSplit(val low: Double, val high: Double, val creditedMinutes: Double)

    /**
     * Time in zones from a day's HR samples. Each sample is credited the gap to the next sample only when that
     * gap is at most [MAX_CREDIT_GAP_S]. After a longer gap (strap off, link dropped) the sample is credited a
     * nominal single interval, never the gap, and any bout in progress ends. Samples with the same timestamp
     * are collapsed.
     */
    fun fromHr(samples: List<HrSample>, zones: HrZoneSet): HrSplit {
        val sorted = samples.sortedBy { it.ts }.distinctBy { it.ts }
        if (sorted.isEmpty()) return HrSplit(0.0, 0.0, 0.0)
        val nominal = minOf(HrZones.medianInterval(sorted), MAX_CREDIT_GAP_S.toDouble())
        var lowS = 0.0
        var highS = 0.0
        var creditedS = 0.0
        var runCat = 0 // 0 = other, 1 = zones 2-3, 2 = zones 4-5
        var zoneS = 0.0 // seconds actually in the current run's zones
        var spanS = 0.0 // elapsed seconds of the run, including dips that were tolerated and then recovered from
        var awayS = 0.0 // seconds outside zones 2-3 (a dip below, or a move into zones 4-5) since the last zone 2-3 sample
        fun flush() {
            if (runCat == 1 && spanS >= MIN_BOUT_S) lowS += zoneS
            if (runCat == 2) highS += zoneS
            zoneS = 0.0; spanS = 0.0; awayS = 0.0
        }
        for (i in sorted.indices) {
            val gap = if (i < sorted.size - 1) (sorted[i + 1].ts - sorted[i].ts).toDouble() else Double.NaN
            val dur = if (!gap.isNaN() && gap > 0 && gap <= MAX_CREDIT_GAP_S) gap else nominal
            creditedS += dur
            val cat = when (zones.zoneNumber(sorted[i].bpm.toDouble())) {
                2, 3 -> 1
                4, 5 -> 2
                else -> 0
            }
            if (i > 0 && (sorted[i].ts - sorted[i - 1].ts) > MAX_CREDIT_GAP_S) { flush(); runCat = 0 }
            when {
                runCat == 1 && cat != 1 -> {
                    // Away from zones 2-3 inside a bout (a dip below zone 2, or a move into zones 4-5, such as a
                    // tempo run brushing the zone 3/4 line): tolerated up to MAX_DIP_S in total. Seconds spent in
                    // zones 4-5 are credited as high time straight away; dip seconds are credited to nothing.
                    if (cat == 2) highS += dur
                    awayS += dur
                    if (awayS > MAX_DIP_S) { flush(); runCat = cat } // the high seconds above are already counted
                }
                runCat == 1 && cat == 1 -> {
                    spanS += awayS; awayS = 0.0 // back in zones 2-3: the tolerated time now counts toward the bout's span
                    zoneS += dur; spanS += dur
                }
                cat != runCat -> {
                    flush(); runCat = cat
                    if (cat != 0) { zoneS = dur; spanS = dur }
                }
                else -> if (cat != 0) { zoneS += dur; spanS += dur }
            }
        }
        flush()
        return HrSplit(lowS / 60.0, highS / 60.0, creditedS / 60.0)
    }

    /** The day's zones from HR, or null when HR covers too little of the day to be trusted. */
    fun hrDay(samples: List<HrSample>, zones: HrZoneSet): DayZones? {
        val split = fromHr(samples, zones)
        return if (split.creditedMinutes >= MIN_HR_MINUTES) DayZones(split.low, split.high, Source.HR) else null
    }

    /**
     * Zone 2-3 and zone 4-5 minutes from imported per-workout zone percentages. Null when the row carries no
     * zones. A workout's zone 2-3 minutes only count when they reach one bout length; the zone split does not
     * say whether they were continuous, so this is the closest available approximation of the HR rule.
     */
    fun fromWorkout(row: WorkoutRow): Pair<Double, Double>? {
        val pct = parseZonePercents(row.zonesJSON) ?: return null
        val durS = row.durationS ?: (row.endTs - row.startTs).toDouble()
        if (durS <= 0) return null
        val minutes = durS / 60.0
        // Percentages are taken as given: they need not sum to 100 (time below zone 1 is omitted).
        val low = (pct[1] + pct[2]) / 100.0 * minutes
        val high = (pct[3] + pct[4]) / 100.0 * minutes
        return (if (low * 60.0 >= MIN_BOUT_S) low else 0.0) to high
    }

    fun localDay(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

    /** Zones per day plus the days whose HR has not been checked yet (those show workout zones for now). */
    data class Resolution(val zonesByDay: Map<LocalDate, DayZones>, val unresolved: Set<LocalDate>)

    /**
     * Applies the single source rule to [days]: a cached HR day uses its HR minutes; a day checked and found
     * without enough HR, or not yet checked, uses its workouts. [cached] holds only completed days.
     */
    fun resolve(
        days: List<LocalDate>,
        cached: Map<LocalDate, ZoneCache.Entry>,
        workouts: List<WorkoutRow>,
        zone: ZoneId,
    ): Resolution {
        val workoutsByDay = workouts.groupBy { localDay(it.startTs, zone) }
        val out = LinkedHashMap<LocalDate, DayZones>()
        val unresolved = HashSet<LocalDate>()
        for (d in days) {
            val c = cached[d]
            if (c == null) unresolved.add(d)
            if (c != null && c.source == Source.HR) { out[d] = DayZones(c.low, c.high, Source.HR); continue }
            var low = 0.0
            var high = 0.0
            var any = false
            for (w in workoutsByDay[d].orEmpty()) {
                val z = fromWorkout(w) ?: continue
                low += z.first; high += z.second; any = true
            }
            out[d] = if (any) DayZones(low, high, Source.WORKOUT) else NO_ZONES
        }
        return Resolution(out, unresolved)
    }

    // ── Strength ──

    private val STRENGTH_WORDS = listOf("strength", "weightlift", "weight lift", "weight train", "lifting", "powerlift", "bodybuild", "functional", "calisthenic")

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
