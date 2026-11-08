package com.noop.bodyage

import com.noop.analytics.VitalityEngine
import com.noop.bodyage.BodyAgePlus.FactorKey
import com.noop.bodyage.BodyAgePlus.MissingReason
import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.WorkoutRow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns already-loaded rows into [BodyAgePlus.Inputs] for a given end day. Pure and deterministic: the
 * database reads live in [BodyAgeLoader]. Windows: 7 days for sleep, RHR, HRV, steps, zones, strength and
 * drinks; 28 days of nights for sleep consistency; latest VO2 estimate on or before the end day.
 */
object BodyAgeInputs {

    const val WINDOW_DAYS = 7
    const val CONSISTENCY_DAYS = 28
    const val VO2_LOOKBACK_DAYS = 400

    /** Alcohol needs this many answered days in the window before it is counted. */
    const val MIN_DRINK_ANSWERS = 4

    data class Raw(
        val chronoAge: Double,
        val sex: String,
        /** Merged daily rows. May hold several rows for one day (imported + live); see [dedupeDays]. */
        val days: List<DailyMetric>,
        val vo2Series: List<MetricSeriesRow>,
        /** Already deduped with [ZoneMinutes.dedupeWorkouts]. */
        val workouts: List<WorkoutRow>,
        val drinks: List<DrinkEntry>,
        val zonesByDay: Map<LocalDate, ZoneMinutes.DayZones>,
        val hasZoneThresholds: Boolean,
        val zone: ZoneId,
        /** Days whose HR has not been read into the zone cache yet (see [ZoneMinutes.Resolution]). */
        val unresolved: Set<LocalDate> = emptySet(),
    )

    /**
     * Expected VO2 max (ml/kg/min) by age, from the WHOOP male MINIMUM-target table (research doc, Fig. 4).
     * The research file gives no 50th-percentile table (the FRIEND 2022 table is an open GAP), so this is the
     * best sourced anchor. These are minimum targets, below the population median, so expected VO2 is low and
     * the VO2 factor biases YOUNGER than a median reference would. Replace with FRIEND once pulled.
     */
    private val VO2_MALE = listOf(30.0 to 44.0, 35.0 to 42.0, 40.0 to 40.0, 45.0 to 37.0, 50.0 to 36.0, 55.0 to 34.0, 60.0 to 32.0, 65.0 to 30.0)

    /** Women and other profiles use a flat fraction of the male table. Unverified approximation, labelled as such. */
    private const val VO2_FEMALE_FACTOR = 0.85
    private const val VO2_OTHER_FACTOR = 0.925

    fun expectedVo2(age: Double, sex: String): Double {
        val male = BodyAgePlus.piecewise(age, VO2_MALE)
        return when (sex.lowercase()) {
            "female" -> male * VO2_FEMALE_FACTOR
            "male" -> male
            else -> male * VO2_OTHER_FACTOR
        }
    }

    /** One row per day. When rows disagree on coverage, the one with the most populated signals wins; ties keep the first. */
    fun dedupeDays(rows: List<DailyMetric>): List<DailyMetric> {
        fun score(d: DailyMetric) = listOf(d.totalSleepMin, d.restingHr, d.avgHrv, d.steps).count { it != null }
        val best = LinkedHashMap<String, DailyMetric>()
        for (r in rows) {
            val cur = best[r.day]
            if (cur == null || score(r) > score(cur)) best[r.day] = r
        }
        return best.values.sortedBy { it.day }
    }

    private fun median(a: List<Double>): Double? {
        if (a.isEmpty()) return null
        val s = a.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun build(raw: Raw, end: LocalDate): BodyAgePlus.Inputs {
        val start = end.minusDays((WINDOW_DAYS - 1).toLong())
        val consistencyStart = end.minusDays((CONSISTENCY_DAYS - 1).toLong())
        val all = dedupeDays(raw.days)
        val win = all.filter { it.day >= start.toString() && it.day <= end.toString() }
        val longWin = all.filter { it.day >= consistencyStart.toString() && it.day <= end.toString() }

        val nights = win.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val longNights = longWin.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val rhr = win.mapNotNull { it.restingHr }.map { it.toDouble() }
        val hrv = win.mapNotNull { it.avgHrv }
        val steps = win.mapNotNull { it.steps }.map { it.toDouble() }

        val vo2Floor = end.minusDays(VO2_LOOKBACK_DAYS.toLong()).toString()
        val vo2 = raw.vo2Series.filter { it.day <= end.toString() && it.day >= vo2Floor }.maxByOrNull { it.day }?.value

        val missing = HashMap<FactorKey, MissingReason>()

        // Zones: need thresholds, then evidence that at least one day in the window had a zone source.
        val windowDays = (0 until WINDOW_DAYS).map { start.plusDays(it.toLong()) }
        val zoneDays = windowDays.map { raw.zonesByDay[it] ?: ZoneMinutes.NO_ZONES }
        val zoneEvidence = zoneDays.any { it.source != ZoneMinutes.Source.NONE }
        var zoneLow: Double? = null
        var zoneHigh: Double? = null
        if (!raw.hasZoneThresholds) {
            missing[FactorKey.ZONE_LOW] = MissingReason.NO_THRESHOLDS
            missing[FactorKey.ZONE_HIGH] = MissingReason.NO_THRESHOLDS
        } else if (zoneEvidence) {
            zoneLow = zoneDays.sumOf { it.low }
            zoneHigh = zoneDays.sumOf { it.high }
        }

        // Strength: workouts from any source in the window. No workout of any kind in the window means we cannot
        // tell "did none" from "nothing recorded", so it is not counted rather than counted as zero.
        val windowWorkouts = raw.workouts.filter { ZoneMinutes.localDay(it.startTs, raw.zone) in start..end }
        val strength: Double? = if (windowWorkouts.isEmpty()) null else windowWorkouts.count { ZoneMinutes.isStrength(it.sport) }.toDouble()

        // Alcohol: days drinking per week, scaled from the answered days; too few answers means not counted.
        val answered = raw.drinks.filter { it.day >= start.toString() && it.day <= end.toString() }
        val alcohol: Double? = if (answered.size < MIN_DRINK_ANSWERS) {
            missing[FactorKey.ALCOHOL] = MissingReason.TOO_FEW_LOGS
            null
        } else {
            answered.count { it.count > 0 } * WINDOW_DAYS.toDouble() / answered.size
        }

        return BodyAgePlus.Inputs(
            chronoAge = raw.chronoAge,
            restingHR = median(rhr),
            vo2max = vo2,
            expectedVO2max = if (vo2 != null) expectedVo2(raw.chronoAge, raw.sex) else null,
            sleepHours = if (nights.isEmpty()) null else nights.average(),
            sleepConsistency = VitalityEngine.sleepConsistency(longNights),
            rmssd = median(hrv),
            rmssdNorm = VitalityEngine.rmssdNorm(raw.chronoAge),
            steps = if (steps.isEmpty()) null else steps.average(),
            zoneLowMinPerWeek = zoneLow,
            zoneHighMinPerWeek = zoneHigh,
            strengthSessionsPerWeek = strength,
            alcoholDaysPerWeek = alcohol,
            missing = missing,
        )
    }

    data class TrendPoint(val day: LocalDate, val bodyAge: Double)

    /** True when every day of the 7-day window ending [end] has had its HR read (or zones do not use HR). */
    fun windowResolved(raw: Raw, end: LocalDate): Boolean =
        !raw.hasZoneThresholds || (0 until WINDOW_DAYS).none { end.minusDays(it.toLong()) in raw.unresolved }

    /**
     * Weekly points ending at [end] (oldest first), one every 7 days. A week with fewer than the minimum
     * counted factors, or whose zone data is still being read, is left out, so the chart draws a break
     * rather than an interpolated or mixed-source value.
     */
    fun trend(raw: Raw, end: LocalDate, weeks: Int = 52): List<TrendPoint> =
        (weeks - 1 downTo 0).mapNotNull { w ->
            val day = end.minusDays(7L * w)
            if (!windowResolved(raw, day)) return@mapNotNull null
            BodyAgePlus.compute(build(raw, day)).bodyAge?.let { TrendPoint(day, it) }
        }
}
