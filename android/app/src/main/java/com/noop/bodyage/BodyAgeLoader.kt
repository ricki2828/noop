package com.noop.bodyage

import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.ui.ProfileStore
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Body Age+ card and screen show, computed on demand (nothing is stored). */
data class BodyAgeSnapshot(
    val result: BodyAgePlus.Result,
    /** Weekly points, oldest first, for the 12-month line. Gaps are weeks left out. */
    val trend: List<BodyAgeInputs.TrendPoint>,
    /** Change in body age over the last 30 days, or null when either end is missing or inside the noise. */
    val thirtyDayChange: Double?,
    val thirtyDayFrom: LocalDate?,
    /** Where today's zone minutes came from, for the "N of 7 days from heart rate" footnote. */
    val hrDays: Int,
    val zoneDays: Int,
    val drinksYesterday: Int?,
    val yesterday: LocalDate,
    val end: LocalDate,
)

/** Reads rows from the repository and hands them to the pure layers. The only database-touching class here. */
object BodyAgeLoader {

    /** 30-day changes smaller than this are noise and not shown (research doc). */
    const val NOISE_YEARS = 0.5

    private const val HISTORY_DAYS = 400L

    /** 52 weekly trend points reach this far back; each still needs its own VO2 lookback. */
    private const val TREND_DAYS = 7L * 52

    suspend fun load(
        repo: WhoopRepository,
        activeStrapId: String,
        profile: ProfileStore,
        days: List<DailyMetric>,
        drinks: DrinksStore,
        end: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): BodyAgeSnapshot {
        val histStart = end.minusDays(HISTORY_DAYS)
        val fromSec = histStart.atStartOfDay(zone).toEpochSecond()
        val toSec = end.plusDays(1).atStartOfDay(zone).toEpochSecond()

        val workouts = ZoneMinutes.dedupeWorkouts(
            runCatching {
                // Same sources the Workouts screen merges: WHOOP, Health Connect, Apple Health, lifting and activity files.
                val others = listOf(
                    WhoopRepository.APPLE_HEALTH_SOURCE, WhoopRepository.HEALTH_CONNECT_SOURCE,
                    com.noop.ingest.LiftingImporter.SOURCE_ID, com.noop.ingest.ActivityFileImporter.SOURCE_ID,
                ).flatMap { repo.workouts(it, fromSec, toSec) }
                repo.workoutsUnion(activeStrapId, fromSec, toSec) + repo.detectedWorkoutsUnion(activeStrapId, fromSec, toSec) + others
            }.getOrDefault(emptyList()),
        )
        val vo2 = runCatching {
            repo.metricSeriesComputedUnion(activeStrapId, "vo2max_est", histStart.minusDays(TREND_DAYS).toString(), end.toString())
        }.getOrDefault(emptyList())

        // Heart rate only for the headline week; older weeks (trend) fall back to workout zones.
        val zoneSet = profile.hrZoneSet.takeIf { it.maxHR > 0 }
        val weekDays = (0 until BodyAgeInputs.WINDOW_DAYS).map { end.minusDays((BodyAgeInputs.WINDOW_DAYS - 1 - it).toLong()) }
        val hrByDay = HashMap<LocalDate, List<com.noop.data.HrSample>>()
        if (zoneSet != null) {
            for (d in weekDays) {
                val s = d.atStartOfDay(zone).toEpochSecond()
                val e = d.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
                hrByDay[d] = runCatching { repo.hrSamplesUnion(activeStrapId, s, e) }.getOrDefault(emptyList())
            }
        }
        val olderDays = (0..HISTORY_DAYS.toInt()).map { histStart.plusDays(it.toLong()) }
        val zonesByDay = ZoneMinutes.byDay(olderDays, hrByDay, workouts, zoneSet, zone)

        val answers = runCatching { drinks.series(histStart.toString(), end.toString()) }.getOrDefault(emptyList())

        val raw = BodyAgeInputs.Raw(
            chronoAge = profile.age.toDouble(),
            sex = profile.sex,
            days = days,
            vo2Series = vo2,
            workouts = workouts,
            hasAnyWorkout = workouts.isNotEmpty(),
            drinks = answers,
            zonesByDay = zonesByDay,
            hasZoneThresholds = zoneSet != null,
            zone = zone,
        )
        val result = BodyAgePlus.compute(BodyAgeInputs.build(raw, end))
        val trend = BodyAgeInputs.trend(raw, end)

        val from30 = end.minusDays(30)
        val before = BodyAgePlus.compute(BodyAgeInputs.build(raw, from30)).bodyAge
        val change = result.bodyAge?.let { now -> before?.let { now - it } }?.takeIf { kotlin.math.abs(it) >= NOISE_YEARS }

        val yesterday = end.minusDays(1)
        return BodyAgeSnapshot(
            result = result,
            trend = trend,
            thirtyDayChange = change,
            thirtyDayFrom = if (change != null) from30 else null,
            hrDays = weekDays.count { zonesByDay[it]?.source == ZoneMinutes.Source.HR },
            zoneDays = weekDays.count { (zonesByDay[it]?.source ?: ZoneMinutes.Source.NONE) != ZoneMinutes.Source.NONE },
            drinksYesterday = answers.firstOrNull { it.day == yesterday.toString() }?.count,
            yesterday = yesterday,
            end = end,
        )
    }
}
