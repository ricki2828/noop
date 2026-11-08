package com.noop.bodyage

import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopRepository
import java.time.LocalDate

/**
 * DrinksStore (fork-only): the one-tap "drinks yesterday" answer, stored in the generic metric series the way
 * MoodStore stores moods, so no Room migration is needed.
 *
 *  - source id (the deviceId column) is always [DRINKS_DEVICE_ID]; an import can never overwrite it
 *  - key is always [DRINKS_KEY]
 *  - value is the count 0..4 where 4 means "4 or more" (clamped, whole numbers)
 *  - one row per local day ("YYYY-MM-DD") of the drinking; answering again overwrites that day
 *
 * The constructor takes the two storage functions so the contract is unit-testable with an in-memory map.
 */
class DrinksStore(
    private val upsertRows: suspend (List<MetricSeriesRow>) -> Unit,
    private val queryRows: suspend (deviceId: String, key: String, from: String, to: String) -> List<MetricSeriesRow>,
) {
    constructor(repo: WhoopRepository) : this(
        { rows -> repo.upsertMetricSeries(rows) },
        { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
    )

    /** Save (or overwrite) the drink count for [day]. Rounded to a whole number and clamped to 0..4. */
    suspend fun setDrinks(day: String, count: Int) {
        upsertRows(
            listOf(MetricSeriesRow(deviceId = DRINKS_DEVICE_ID, day = day, key = DRINKS_KEY, value = count.coerceIn(0, MAX_COUNT).toDouble())),
        )
    }

    /** The count answered for [day], or null when unanswered. */
    suspend fun drinks(day: String): Int? =
        queryRows(DRINKS_DEVICE_ID, DRINKS_KEY, day, day).firstOrNull()?.value?.toInt()

    /** Answers in [from, to] (inclusive day keys), oldest first. One entry per day by the storage contract. */
    suspend fun series(from: String = DAY_MIN, to: String = DAY_MAX): List<DrinkEntry> =
        queryRows(DRINKS_DEVICE_ID, DRINKS_KEY, from, to)
            .sortedBy { it.day }
            .map { DrinkEntry(it.day, it.value.toInt().coerceIn(0, MAX_COUNT)) }

    companion object {
        const val DRINKS_DEVICE_ID = "noop-fork-drinks"
        const val DRINKS_KEY = "drinks"
        const val MAX_COUNT = 4
        private const val DAY_MIN = "0000-01-01"
        private const val DAY_MAX = "9999-12-31"

        /** The day the morning question is about: the local calendar day before [today]. */
        fun yesterdayKey(today: LocalDate = LocalDate.now()): String = today.minusDays(1).toString()
    }
}

/** One answered day: [day] is "YYYY-MM-DD", [count] is 0..4 (4 = four or more). */
data class DrinkEntry(val day: String, val count: Int)
