package com.noop.bodyage

import com.noop.bodyage.BodyAgePlus.FactorKey
import com.noop.bodyage.BodyAgePlus.MissingReason
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every user-facing string of the Body Age+ add-on lives here and nowhere else. This is an English-only fork
 * feature, so the strings are plain Kotlin values rather than Android string resources; the UI passes them to
 * Compose as variables, which the repository's i18n gate (Tools/i18n_audit.py) does not treat as hardcoded
 * literals. If the fork ever needs translations, this file is the single place to convert.
 */
object BodyAgeCopy {

    const val NAME = "Body Age+"
    const val SCREEN_SUBTITLE = "Lifestyle estimate from your last 7 days"
    const val FOOTER = "A lifestyle estimate, not a medical age."
    const val SECTION_SHORT = "Falling short"
    const val SECTION_HELPING = "Helping you"
    const val SECTION_TREND = "Last 12 months"
    const val SECTION_NOT_COUNTED = "Not counted today"
    const val SECTION_DRINKS = "Drinks yesterday"
    const val NOTHING_SHORT = "Nothing holding you back this week"
    const val TREND_EMPTY = "Your line starts after 2 weeks"
    const val HERO_EMPTY = "Wear the strap 3 nights to get your first Body Age+"
    const val CARD_EMPTY = "Needs 3 of: sleep, HR, HRV, VO2, workouts"
    const val LOADING = "Reading your last 7 days"
    const val DRINKS_PROMPT = "Drinks yesterday?"
    const val DRINKS_NOT_SAVED = "Not saved, tap again"
    const val CLOSE = "Close"
    const val OPEN_DESCRIPTION = "Open Body Age+"

    val DRINK_CHIPS = listOf("0", "1", "2", "3", "4+")

    fun drinkChipDescription(count: Int): String = when (count) {
        0 -> "Zero drinks yesterday"
        1 -> "One drink yesterday"
        2 -> "Two drinks yesterday"
        3 -> "Three drinks yesterday"
        else -> "Four or more drinks yesterday"
    }

    fun years(n: Int): String = if (n == 1) "1 year" else "$n years"
    private fun yrs(n: Int): String = "$n yrs"
    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** "3 years older than your age" / "2 years younger than your age" / "About your age". */
    fun heroDelta(deltaYears: Double): String {
        val n = abs(deltaYears).roundToInt()
        return when {
            n == 0 -> "About your age"
            deltaYears > 0 -> "${years(n)} older than your age"
            else -> "${years(n)} younger than your age"
        }
    }

    /** Today card line: "3 yrs older than 46". */
    fun cardDelta(deltaYears: Double, chronoAge: Double): String {
        val n = abs(deltaYears).roundToInt()
        val age = chronoAge.roundToInt()
        return when {
            n == 0 -> "About your age, $age"
            deltaYears > 0 -> "${yrs(n)} older than $age"
            else -> "${yrs(n)} younger than $age"
        }
    }

    fun cardPartial(notCounted: Int): String = " · $notCounted not counted"

    fun band(bandYears: Double): String = "±${bandYears.roundToInt()} yrs"

    /** "↓0.4 since Oct" (down = younger) / "↑0.6 since Oct". */
    fun thirtyDay(change: Double, from: LocalDate): String {
        val arrow = if (change < 0) "↓" else "↑"
        val month = from.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$arrow${oneDecimal(abs(change))} since $month"
    }

    fun chronoReference(age: Double): String = "Your age: ${age.roundToInt()}"

    fun yearsBack(years: Double): String = "~${oneDecimal(years)} yrs back"
    fun helpingLine(key: FactorKey, actual: Double, years: Double): String =
        "${label(key)} ${value(key, actual)}: −${oneDecimal(abs(years))} yrs"

    fun zoneSource(hrDays: Int, zoneDays: Int): String =
        if (zoneDays == 0) "" else "Zone minutes: heart rate on $hrDays of 7 days, workouts on the rest"

    fun label(key: FactorKey): String = when (key) {
        FactorKey.SLEEP -> "Sleep"
        FactorKey.CONSISTENCY -> "Sleep timing"
        FactorKey.RHR -> "Resting HR"
        FactorKey.VO2MAX -> "Cardio fitness"
        FactorKey.HRV -> "HRV"
        FactorKey.STEPS -> "Steps"
        FactorKey.ZONE_LOW -> "Easy and steady minutes"
        FactorKey.ZONE_HIGH -> "Hard minutes"
        FactorKey.STRENGTH -> "Strength"
        FactorKey.ALCOHOL -> "Alcohol"
    }

    /** One short fixed action per factor (no generated text). */
    fun action(key: FactorKey): String = when (key) {
        FactorKey.SLEEP -> "Lights out 30 minutes earlier on weeknights"
        FactorKey.CONSISTENCY -> "Same wake time on weekends"
        FactorKey.RHR -> "Two easy 30-minute zone 2 sessions this week"
        FactorKey.VO2MAX -> "One interval session a week: 4 x 4 minutes hard"
        FactorKey.HRV -> "No alcohol in the 3 hours before bed"
        FactorKey.STEPS -> "A 20-minute walk after lunch"
        FactorKey.ZONE_LOW -> "Add 30 easy minutes on two days"
        FactorKey.ZONE_HIGH -> "One session with 4 x 4 minutes hard"
        FactorKey.STRENGTH -> "Two 30-minute sessions a week"
        FactorKey.ALCOHOL -> "Keep it to one drinking day a week"
    }

    /** The measured value with its unit, e.g. "6.3 h". */
    fun value(key: FactorKey, v: Double): String = when (key) {
        FactorKey.SLEEP -> "${oneDecimal(v)} h"
        FactorKey.CONSISTENCY -> "${(v * 100).roundToInt()}%"
        FactorKey.RHR -> "${v.roundToInt()} bpm"
        FactorKey.VO2MAX -> "${v.roundToInt()} ml/kg/min"
        FactorKey.HRV -> "${v.roundToInt()} ms"
        FactorKey.STEPS -> "${v.roundToInt()} steps"
        FactorKey.ZONE_LOW, FactorKey.ZONE_HIGH -> "${v.roundToInt()} min"
        FactorKey.STRENGTH -> if (v.roundToInt() == 1) "1 session" else "${v.roundToInt()} sessions"
        FactorKey.ALCOHOL -> "${oneDecimal(v)} days"
    }

    private fun targetValue(key: FactorKey, v: Double): String = when (key) {
        FactorKey.CONSISTENCY -> "${(v * 100).roundToInt()}% consistent"
        FactorKey.STRENGTH -> "${v.roundToInt()} / week"
        FactorKey.ZONE_LOW, FactorKey.ZONE_HIGH -> "${v.roundToInt()} min / week"
        FactorKey.ALCOHOL -> "${v.roundToInt()} / week"
        FactorKey.STEPS -> "${v.roundToInt()} / day"
        else -> value(key, v)
    }

    /** "6.3 h vs 7.5 h". */
    fun gap(key: FactorKey, actual: Double, target: Double): String =
        "${value(key, actual)} vs ${targetValue(key, target)}"

    /** "HRV: no overnight data". */
    fun notCounted(key: FactorKey, reason: MissingReason): String {
        val why = when (reason) {
            MissingReason.NO_THRESHOLDS -> "no heart-rate zone thresholds"
            MissingReason.TOO_FEW_LOGS -> "log drinks on 4 of the last 7 days"
            MissingReason.NO_DATA -> when (key) {
                FactorKey.SLEEP, FactorKey.CONSISTENCY, FactorKey.RHR, FactorKey.HRV -> "no overnight data"
                FactorKey.VO2MAX -> "no VO2 estimate yet"
                FactorKey.STEPS -> "no step data"
                FactorKey.ZONE_LOW, FactorKey.ZONE_HIGH -> "no heart rate or workout zones"
                FactorKey.STRENGTH -> "no workouts recorded"
                FactorKey.ALCOHOL -> "no drinks logged"
            }
        }
        return "${label(key)}: $why"
    }

    /** Merged accessibility description for a shortfall row. */
    fun shortfallDescription(key: FactorKey, actual: Double, target: Double, years: Double): String =
        "${label(key)}, ${gap(key, actual, target)}, ${yearsBack(years)}. ${action(key)}"
}
