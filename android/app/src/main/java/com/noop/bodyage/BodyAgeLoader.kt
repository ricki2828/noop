package com.noop.bodyage

import com.noop.data.DailyMetric
import com.noop.data.HrSample
import com.noop.data.WhoopRepository
import com.noop.analytics.HrZoneSet
import com.noop.data.MetricSeriesRow
import com.noop.data.WorkoutRow
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Body Age+ card and screen show, computed on demand (only the zone cache is stored). */
data class BodyAgeSnapshot(
    val result: BodyAgePlus.Result,
    /** Weekly points, oldest first, for the 12-month line. Gaps are weeks left out. */
    val trend: List<BodyAgeInputs.TrendPoint>,
    /** Change in body age over the last 30 days, or null when either end is missing, unresolved or inside the noise. */
    val thirtyDayChange: Double?,
    val thirtyDayFrom: LocalDate?,
    /** Days of the headline week whose zones came from heart rate, and days with any zone source. */
    val hrDays: Int,
    val zoneDays: Int,
    /** Days of heart rate still to be read into the cache; the trend is incomplete while this is above 0. */
    val backfillingDays: Int,
    /** Answer for [end] (the last completed day), or null when unanswered. */
    val drinksYesterday: Int?,
    /** The last completed day: the end of every window. */
    val end: LocalDate,
)

/** The exact repository reads [BodyAgeLoader] needs, so tests can supply a fake. */
interface BodyAgeData {
    /** Workouts from every source the Workouts screen merges, not yet deduped. */
    suspend fun workouts(fromSec: Long, toSec: Long): List<WorkoutRow>
    suspend fun vo2(from: String, to: String): List<MetricSeriesRow>
    suspend fun hrSamples(fromSec: Long, toSec: Long): List<HrSample>
}

/** What the loader needs from the profile. */
data class BodyAgeProfile(val age: Double, val sex: String, val zoneSet: HrZoneSet?)

/** [BodyAgeData] over the real repository and the active strap. */
class RepoBodyAgeData(private val repo: WhoopRepository, private val activeStrapId: String) : BodyAgeData {
    override suspend fun workouts(fromSec: Long, toSec: Long): List<WorkoutRow> {
        // Same sources the Workouts screen merges: WHOOP (incl. detected), Health Connect, Apple Health, lifting, activity files.
        val others = listOf(
            WhoopRepository.APPLE_HEALTH_SOURCE, WhoopRepository.HEALTH_CONNECT_SOURCE,
            com.noop.ingest.LiftingImporter.SOURCE_ID, com.noop.ingest.ActivityFileImporter.SOURCE_ID,
        ).flatMap { repo.workouts(it, fromSec, toSec) }
        return repo.workoutsUnion(activeStrapId, fromSec, toSec) + repo.detectedWorkoutsUnion(activeStrapId, fromSec, toSec) + others
    }

    override suspend fun vo2(from: String, to: String) = repo.metricSeriesComputedUnion(activeStrapId, "vo2max_est", from, to)
    override suspend fun hrSamples(fromSec: Long, toSec: Long) = repo.hrSamplesUnion(activeStrapId, fromSec, toSec)
}

/**
 * Reads rows through [BodyAgeData] and hands them to the pure layers. Database errors propagate (the UI shows
 * an error state); cancellation is never swallowed, and nothing is written before every read has finished, so a
 * cancelled load leaves the cache untouched.
 */
object BodyAgeLoader {

    /** 30-day changes smaller than this are noise and not shown (research doc). */
    const val NOISE_YEARS = 0.5

    private const val HISTORY_DAYS = 400L

    /** 52 weekly trend points reach this far back; each still needs its own VO2 lookback. */
    private const val TREND_DAYS = 7L * 52

    /**
     * Windows end on the last COMPLETED day (yesterday): today's row is partial (steps, workouts) and would
     * drag the 7-day averages. Reads at most [ZoneCache.BUDGET] uncached days of heart rate per call, the
     * headline week first, then the window 30 days back (so the delta is consistent), then newest first; the
     * caller reloads while [BodyAgeSnapshot.backfillingDays] is above 0.
     */
    suspend fun load(
        data: BodyAgeData,
        profile: BodyAgeProfile,
        days: List<DailyMetric>,
        drinks: DrinksStore,
        cache: ZoneCache,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BodyAgeSnapshot {
        val end = today.minusDays(1)
        val histStart = end.minusDays(HISTORY_DAYS)
        val fromSec = histStart.atStartOfDay(zone).toEpochSecond()
        val toSec = end.plusDays(1).atStartOfDay(zone).toEpochSecond()

        val workouts = ZoneMinutes.dedupeWorkouts(data.workouts(fromSec, toSec))
        val vo2 = data.vo2(histStart.minusDays(TREND_DAYS).toString(), end.toString())

        val zoneSet = profile.zoneSet?.takeIf { it.maxHR > 0 }
        val allDays = (0..HISTORY_DAYS.toInt()).map { histStart.plusDays(it.toLong()) }
        var cached = if (zoneSet != null) cache.read(histStart, end) else emptyMap()
        var remaining = 0
        if (zoneSet != null) {
            val windowOf = { last: LocalDate -> (0 until BodyAgeInputs.WINDOW_DAYS).map { last.minusDays(it.toLong()) } }
            val priority = windowOf(end) + windowOf(end.minusDays(30))
            val plan = ZoneCache.plan(allDays, cached, end, priority = priority)
            remaining = plan.remaining
            val fresh = plan.toCompute.map { d ->
                val s = d.atStartOfDay(zone).toEpochSecond()
                val e = d.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
                val z = ZoneMinutes.hrDay(data.hrSamples(s, e), zoneSet)
                if (z != null) ZoneCache.Entry(d, ZoneMinutes.Source.HR, z.low, z.high)
                else ZoneCache.Entry(d, ZoneMinutes.Source.NONE, 0.0, 0.0)
            }
            cache.write(fresh) // only after every read above succeeded
            cached = cached + fresh.associateBy { it.day }
        }
        val resolution = ZoneMinutes.resolve(allDays, cached, workouts, zone)

        val answers = drinks.series(histStart.toString(), end.toString())
        val raw = BodyAgeInputs.Raw(
            chronoAge = profile.age,
            sex = profile.sex,
            days = days,
            vo2Series = vo2,
            workouts = workouts,
            drinks = answers,
            zonesByDay = resolution.zonesByDay,
            hasZoneThresholds = zoneSet != null,
            zone = zone,
            unresolved = resolution.unresolved,
        )
        return assemble(raw, end, remaining, answers)
    }

    /** Pure: builds the snapshot from already-loaded rows (so the orchestration is testable without a database). */
    internal fun assemble(raw: BodyAgeInputs.Raw, end: LocalDate, backfillingDays: Int, answers: List<DrinkEntry>): BodyAgeSnapshot {
        val result = BodyAgePlus.compute(BodyAgeInputs.build(raw, end))
        val trend = BodyAgeInputs.trend(raw, end)

        val from30 = end.minusDays(30)
        val change = if (BodyAgeInputs.windowResolved(raw, end) && BodyAgeInputs.windowResolved(raw, from30)) {
            val before = BodyAgePlus.compute(BodyAgeInputs.build(raw, from30)).bodyAge
            result.bodyAge?.let { now -> before?.let { now - it } }?.takeIf { kotlin.math.abs(it) >= NOISE_YEARS }
        } else null

        val week = (0 until BodyAgeInputs.WINDOW_DAYS).map { end.minusDays(it.toLong()) }
        return BodyAgeSnapshot(
            result = result,
            trend = trend,
            thirtyDayChange = change,
            thirtyDayFrom = if (change != null) from30 else null,
            hrDays = week.count { raw.zonesByDay[it]?.source == ZoneMinutes.Source.HR },
            zoneDays = week.count { (raw.zonesByDay[it]?.source ?: ZoneMinutes.Source.NONE) != ZoneMinutes.Source.NONE },
            backfillingDays = backfillingDays,
            drinksYesterday = answers.firstOrNull { it.day == end.toString() }?.count,
            end = end,
        )
    }
}
