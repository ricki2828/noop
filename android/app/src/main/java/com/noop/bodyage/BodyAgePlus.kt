package com.noop.bodyage

import com.noop.analytics.VitalityEngine

/**
 * Body Age+ (fork-only add-on): NOOP's own [VitalityEngine] factors plus VO2 max (which upstream defines
 * but does not pass) and four factors from the fork research: Z1-3 minutes/week, Z4-5 minutes/week,
 * strength sessions/week and alcohol days/week (a penalty only).
 *
 * Reuses upstream's maths unchanged: per-factor log-hazards from [VitalityEngine.contributions], the
 * ln(2)/8 hazard-per-year slope and the 0.75 overlap shrink. The new factors are expressed in "years"
 * first (piecewise-linear curves below, clamped per factor) and converted to a log-hazard with the
 * same slope, so every factor sums in one unit. Pure: no Android, no database, no strings.
 *
 * A lifestyle estimate, not a medical age. The number differs from NOOP's Health tab Vitality body age
 * on purpose (VO2 and the extra factors are included here).
 */
object BodyAgePlus {

    /** Same slope as [VitalityEngine] (mortality-rate doubling about every 8 years). */
    const val LN_HAZARD_PER_YEAR = 0.6931471805599453 / 8.0

    /** Same overlap shrink as [VitalityEngine]. */
    const val OVERLAP_SHRINK = 0.75

    /** Needs at least this many counted factors before a number is shown (as upstream). */
    const val MIN_FACTORS = 3

    /** Total offset from the chronological age is capped here, in years. */
    const val TOTAL_CAP_YEARS = 10.0

    /** Displayed uncertainty band; widens when fewer than [WIDE_BAND_BELOW] factors are counted. */
    const val BAND_YEARS = 5.0
    const val BAND_YEARS_WIDE = 6.0
    const val WIDE_BAND_BELOW = 5

    /** Below this many years a factor is neither a shortfall nor a help. */
    const val SIGNIFICANT_YEARS = 0.05

    const val MAX_SHORTFALLS = 3
    const val MAX_HELPING = 2

    /** Every factor Body Age+ can use, in fixed display/tie-break order. */
    enum class FactorKey { SLEEP, CONSISTENCY, RHR, VO2MAX, HRV, STEPS, ZONE_LOW, ZONE_HIGH, STRENGTH, ALCOHOL }

    /** Why a factor is not counted. The UI maps this to copy. */
    enum class MissingReason { NO_DATA, NO_THRESHOLDS, TOO_FEW_LOGS }

    data class Inputs(
        val chronoAge: Double,
        val restingHR: Double? = null,
        val vo2max: Double? = null,
        val expectedVO2max: Double? = null,
        val sleepHours: Double? = null,
        val sleepConsistency: Double? = null,
        val rmssd: Double? = null,
        val rmssdNorm: Double? = null,
        val steps: Double? = null,
        val zoneLowMinPerWeek: Double? = null,
        val zoneHighMinPerWeek: Double? = null,
        val strengthSessionsPerWeek: Double? = null,
        val alcoholDaysPerWeek: Double? = null,
        /** Reason to show for a factor that has no input; absent keys mean [MissingReason.NO_DATA]. */
        val missing: Map<FactorKey, MissingReason> = emptyMap(),
    )

    /**
     * One counted factor. [years] is its contribution to the body age after the overlap shrink:
     * positive ages you (a shortfall, "years recoverable"), negative helps. [actual]/[target] are the
     * raw measurement and the neutral point it is judged against, for display.
     */
    data class Factor(
        val key: FactorKey,
        val lnHazard: Double,
        val years: Double,
        val actual: Double,
        val target: Double,
    )

    data class NotCounted(val key: FactorKey, val reason: MissingReason)

    data class Result(
        val chronoAge: Double,
        /** Null until [MIN_FACTORS] factors are counted. */
        val bodyAge: Double?,
        /** Body age minus chronological age (positive = older), after the total cap. Null with [bodyAge]. */
        val deltaYears: Double?,
        val bandYears: Double,
        val factors: List<Factor>,
        val shortfalls: List<Factor>,
        val helping: List<Factor>,
        val notCounted: List<NotCounted>,
        val capped: Boolean,
    ) {
        val factorsUsed: Int get() = factors.size
    }

    // ── Curves for the new factors (research v1). Years before the overlap shrink; + = older. ──

    private val ZONE_LOW_CURVE = listOf(0.0 to 4.0, 100.0 to 0.0, 300.0 to -2.5, 600.0 to -4.0)
    private val ZONE_HIGH_CURVE = listOf(0.0 to 1.0, 10.0 to 0.0, 60.0 to -1.5, 150.0 to -2.0)
    private val STRENGTH_CURVE = listOf(0.0 to 1.0, 2.0 to 0.0, 3.0 to -1.0, 5.0 to -1.5)

    private const val ZONE_LOW_CLAMP = 4.0
    private const val ZONE_HIGH_CLAMP = 2.0
    private const val STRENGTH_CLAMP = 2.5
    private const val ALCOHOL_CLAMP = 3.0

    /** Neutral points shown as the target on each factor. */
    const val TARGET_RHR = 65.0
    const val TARGET_SLEEP_HOURS = 7.5
    const val TARGET_CONSISTENCY = 0.75
    const val TARGET_STEPS = 7000.0
    const val TARGET_ZONE_LOW = 100.0
    const val TARGET_ZONE_HIGH = 10.0
    const val TARGET_STRENGTH = 2.0
    const val TARGET_ALCOHOL_DAYS = 1.0

    /** Piecewise-linear interpolation, flat beyond the first and last point. [points] ascend in x. */
    internal fun piecewise(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (x <= x1) {
                val (x0, y0) = points[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }

    internal fun zoneLowYears(minPerWeek: Double): Double =
        piecewise(minPerWeek.coerceAtLeast(0.0), ZONE_LOW_CURVE).coerceIn(-ZONE_LOW_CLAMP, ZONE_LOW_CLAMP)

    internal fun zoneHighYears(minPerWeek: Double): Double =
        piecewise(minPerWeek.coerceAtLeast(0.0), ZONE_HIGH_CURVE).coerceIn(-ZONE_HIGH_CLAMP, ZONE_HIGH_CLAMP)

    internal fun strengthYears(sessionsPerWeek: Double): Double =
        piecewise(sessionsPerWeek.coerceAtLeast(0.0), STRENGTH_CURVE).coerceIn(-STRENGTH_CLAMP, STRENGTH_CLAMP)

    /** Penalty only: no credit for abstaining, +0.5 y per drinking day past the first, capped at +3. */
    internal fun alcoholYears(daysPerWeek: Double): Double =
        ((daysPerWeek - TARGET_ALCOHOL_DAYS) * 0.5).coerceIn(0.0, ALCOHOL_CLAMP)

    private fun yearsToLn(years: Double) = years * LN_HAZARD_PER_YEAR

    /** Raw contribution list before shrink: upstream's six (plus VO2 when both inputs exist) and the new four. */
    private fun rawFactors(i: Inputs): List<Triple<FactorKey, Double, Pair<Double, Double>>> {
        val out = ArrayList<Triple<FactorKey, Double, Pair<Double, Double>>>()
        val upstream = VitalityEngine.contributions(
            VitalityEngine.Inputs(
                chronoAge = i.chronoAge,
                restingHR = i.restingHR,
                vo2max = i.vo2max,
                expectedVO2max = i.expectedVO2max,
                sleepHours = i.sleepHours,
                sleepConsistency = i.sleepConsistency,
                rmssd = i.rmssd,
                rmssdNorm = i.rmssdNorm,
                steps = i.steps,
            ),
        ).associateBy { it.key }
        fun up(key: String, fk: FactorKey, actual: Double?, target: Double?) {
            val c = upstream[key] ?: return
            if (actual == null || target == null) return
            out.add(Triple(fk, c.lnHazard, actual to target))
        }
        up("sleep", FactorKey.SLEEP, i.sleepHours, TARGET_SLEEP_HOURS)
        up("consistency", FactorKey.CONSISTENCY, i.sleepConsistency, TARGET_CONSISTENCY)
        up("rhr", FactorKey.RHR, i.restingHR, TARGET_RHR)
        up("vo2max", FactorKey.VO2MAX, i.vo2max, i.expectedVO2max)
        up("hrv", FactorKey.HRV, i.rmssd, i.rmssdNorm)
        up("steps", FactorKey.STEPS, i.steps, TARGET_STEPS)
        i.zoneLowMinPerWeek?.let {
            out.add(Triple(FactorKey.ZONE_LOW, yearsToLn(zoneLowYears(it)), it to TARGET_ZONE_LOW))
        }
        i.zoneHighMinPerWeek?.let {
            out.add(Triple(FactorKey.ZONE_HIGH, yearsToLn(zoneHighYears(it)), it to TARGET_ZONE_HIGH))
        }
        i.strengthSessionsPerWeek?.let {
            out.add(Triple(FactorKey.STRENGTH, yearsToLn(strengthYears(it)), it to TARGET_STRENGTH))
        }
        i.alcoholDaysPerWeek?.let {
            out.add(Triple(FactorKey.ALCOHOL, yearsToLn(alcoholYears(it)), it to TARGET_ALCOHOL_DAYS))
        }
        return out.sortedBy { it.first.ordinal }
    }

    /** Biggest shortfalls first (most years recoverable). Ties keep the fixed factor order (stable sort). */
    fun rankShortfalls(factors: List<Factor>, max: Int = MAX_SHORTFALLS): List<Factor> =
        factors.filter { it.years > SIGNIFICANT_YEARS }.sortedByDescending { it.years }.take(max)

    fun rankHelping(factors: List<Factor>, max: Int = MAX_HELPING): List<Factor> =
        factors.filter { it.years < -SIGNIFICANT_YEARS }.sortedBy { it.years }.take(max)

    fun compute(inputs: Inputs): Result {
        val raw = rawFactors(inputs)
        val factors = raw.map { (key, ln, at) ->
            Factor(
                key = key,
                lnHazard = ln,
                years = ln * OVERLAP_SHRINK / LN_HAZARD_PER_YEAR,
                actual = at.first,
                target = at.second,
            )
        }
        val counted = factors.map { it.key }.toSet()
        val notCounted = FactorKey.entries.filter { it !in counted }
            .map { NotCounted(it, inputs.missing[it] ?: MissingReason.NO_DATA) }
        val band = if (factors.size < WIDE_BAND_BELOW) BAND_YEARS_WIDE else BAND_YEARS
        val ready = inputs.chronoAge > 0 && factors.size >= MIN_FACTORS
        if (!ready) {
            return Result(inputs.chronoAge, null, null, band, factors, emptyList(), emptyList(), notCounted, false)
        }
        val sumLn = factors.sumOf { it.lnHazard } * OVERLAP_SHRINK
        val uncapped = sumLn / LN_HAZARD_PER_YEAR
        val capped = kotlin.math.abs(uncapped) > TOTAL_CAP_YEARS
        val delta = uncapped.coerceIn(-TOTAL_CAP_YEARS, TOTAL_CAP_YEARS)
        val bodyAge = (inputs.chronoAge + delta).coerceIn(VitalityEngine.minBodyAge, VitalityEngine.maxBodyAge)
        return Result(
            chronoAge = inputs.chronoAge,
            bodyAge = bodyAge,
            deltaYears = bodyAge - inputs.chronoAge,
            bandYears = band,
            factors = factors,
            shortfalls = rankShortfalls(factors),
            helping = rankHelping(factors),
            notCounted = notCounted,
            capped = capped,
        )
    }
}
