package com.noop.bodyage

import com.noop.bodyage.BodyAgePlus.FactorKey
import com.noop.bodyage.BodyAgePlus.MissingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyAgePlusTest {

    private val ln = BodyAgePlus.LN_HAZARD_PER_YEAR

    // ── Hand calculation (age 46) ──
    // RHR 56.5:  (56.5-65)/10*0.100            = -0.085
    // sleep 6.3: max(0,|6.3-7.5|-0.5)=0.7 *0.110 =  0.077
    // regularity 0.67: (0.75-0.67)*0.450        =  0.036
    // strength 0 sessions: +1.0 y -> 1.0*ln(2)/8 =  0.0866434
    // sum 0.1146434 * 0.75 / 0.0866434 = 0.99237 years older -> 46.99
    @Test
    fun rickiLikeInputsMatchHandCalculation() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(
                chronoAge = 46.0, restingHR = 56.5, sleepHours = 6.3, sleepConsistency = 0.67,
                strengthSessionsPerWeek = 0.0,
            ),
        )
        assertEquals(4, r.factorsUsed)
        assertEquals(0.99237, r.deltaYears!!, 0.01)
        assertEquals(46.99237, r.bodyAge!!, 0.01)
        assertFalse(r.capped)
    }

    @Test
    fun lessThanThreeFactorsGivesNoNumberAndListsWhatIsMissing() {
        val r = BodyAgePlus.compute(BodyAgePlus.Inputs(chronoAge = 46.0, restingHR = 56.0, sleepHours = 7.0))
        assertNull(r.bodyAge)
        assertNull(r.deltaYears)
        assertEquals(2, r.factorsUsed)
        assertEquals(8, r.notCounted.size)
        assertTrue(r.notCounted.all { it.reason == MissingReason.NO_DATA })
        assertTrue(r.shortfalls.isEmpty())
    }

    @Test
    fun emptyInputsAreNotReady() {
        val r = BodyAgePlus.compute(BodyAgePlus.Inputs(chronoAge = 46.0))
        assertNull(r.bodyAge)
        assertEquals(FactorKey.entries.size, r.notCounted.size)
    }

    @Test
    fun missingReasonIsCarriedToNotCounted() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(
                chronoAge = 46.0, restingHR = 56.0, sleepHours = 7.0, sleepConsistency = 0.8,
                missing = mapOf(
                    FactorKey.ZONE_LOW to MissingReason.NO_THRESHOLDS,
                    FactorKey.ALCOHOL to MissingReason.TOO_FEW_LOGS,
                ),
            ),
        )
        val byKey = r.notCounted.associateBy { it.key }
        assertEquals(MissingReason.NO_THRESHOLDS, byKey[FactorKey.ZONE_LOW]!!.reason)
        assertEquals(MissingReason.TOO_FEW_LOGS, byKey[FactorKey.ALCOHOL]!!.reason)
        assertEquals(MissingReason.NO_DATA, byKey[FactorKey.HRV]!!.reason)
    }

    @Test
    fun allClampsHitCapsTheTotalAtPlusTenYears() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(
                chronoAge = 46.0, restingHR = 120.0, vo2max = 20.0, expectedVO2max = 50.0, sleepHours = 3.0,
                sleepConsistency = 0.0, rmssd = 0.0, rmssdNorm = 30.0, steps = 0.0,
                zoneLowMinPerWeek = 0.0, zoneHighMinPerWeek = 0.0, strengthSessionsPerWeek = 0.0,
                alcoholDaysPerWeek = 7.0,
            ),
        )
        assertEquals(10, r.factorsUsed)
        assertTrue(r.capped)
        assertEquals(10.0, r.deltaYears!!, 1e-9)
        assertEquals(56.0, r.bodyAge!!, 1e-9)
        assertEquals(5.0, r.bandYears, 0.0)
    }

    @Test
    fun allBestCapsTheTotalAtMinusTenYears() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(
                chronoAge = 46.0, restingHR = 40.0, vo2max = 60.0, expectedVO2max = 40.0, sleepHours = 7.5,
                sleepConsistency = 1.0, rmssd = 80.0, rmssdNorm = 30.0, steps = 11000.0,
                zoneLowMinPerWeek = 600.0, zoneHighMinPerWeek = 150.0, strengthSessionsPerWeek = 5.0,
                alcoholDaysPerWeek = 0.0,
            ),
        )
        assertTrue(r.capped)
        assertEquals(-10.0, r.deltaYears!!, 1e-9)
        assertEquals(36.0, r.bodyAge!!, 1e-9)
        assertTrue(r.shortfalls.isEmpty())
    }

    @Test
    fun bandWidensBelowFiveFactors() {
        val four = BodyAgePlus.compute(
            BodyAgePlus.Inputs(chronoAge = 46.0, restingHR = 56.0, sleepHours = 7.0, sleepConsistency = 0.8, strengthSessionsPerWeek = 2.0),
        )
        assertEquals(6.0, four.bandYears, 0.0)
        val five = BodyAgePlus.compute(
            BodyAgePlus.Inputs(chronoAge = 46.0, restingHR = 56.0, sleepHours = 7.0, sleepConsistency = 0.8, strengthSessionsPerWeek = 2.0, steps = 8000.0),
        )
        assertEquals(5.0, five.bandYears, 0.0)
    }

    // ── Curves ──

    @Test
    fun zoneAndStrengthCurvesHitTheirAnchors() {
        assertEquals(4.0, BodyAgePlus.zoneLowYears(0.0), 1e-9)
        assertEquals(0.0, BodyAgePlus.zoneLowYears(100.0), 1e-9)
        assertEquals(-1.25, BodyAgePlus.zoneLowYears(200.0), 1e-9)
        assertEquals(-4.0, BodyAgePlus.zoneLowYears(900.0), 1e-9)
        assertEquals(1.0, BodyAgePlus.zoneHighYears(0.0), 1e-9)
        assertEquals(0.0, BodyAgePlus.zoneHighYears(10.0), 1e-9)
        assertEquals(-0.75, BodyAgePlus.zoneHighYears(35.0), 1e-9)
        assertEquals(-2.0, BodyAgePlus.zoneHighYears(500.0), 1e-9)
        assertEquals(1.0, BodyAgePlus.strengthYears(0.0), 1e-9)
        assertEquals(0.0, BodyAgePlus.strengthYears(2.0), 1e-9)
        assertEquals(-0.5, BodyAgePlus.strengthYears(2.5), 1e-9)
        assertEquals(-1.5, BodyAgePlus.strengthYears(9.0), 1e-9)
    }

    @Test
    fun alcoholIsAPenaltyOnly() {
        assertEquals(0.0, BodyAgePlus.alcoholYears(0.0), 1e-9)
        assertEquals(0.0, BodyAgePlus.alcoholYears(1.0), 1e-9)
        assertEquals(1.0, BodyAgePlus.alcoholYears(3.0), 1e-9)
        assertEquals(3.0, BodyAgePlus.alcoholYears(7.0), 1e-9)
        assertEquals(3.0, BodyAgePlus.alcoholYears(12.0), 1e-9)
    }

    @Test
    fun newFactorLogHazardUsesTheUpstreamSlope() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(chronoAge = 46.0, restingHR = 65.0, sleepHours = 7.5, sleepConsistency = 0.75, strengthSessionsPerWeek = 0.0),
        )
        val strength = r.factors.single { it.key == FactorKey.STRENGTH }
        assertEquals(1.0 * ln, strength.lnHazard, 1e-12)
        assertEquals(0.75, strength.years, 1e-9) // 1.0 y shrunk by the 0.75 overlap
    }

    // ── Shortfall ranking ──

    @Test
    fun shortfallsRankSleepAndStrengthFirstForRickisPattern() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(
                chronoAge = 46.0, restingHR = 56.5, sleepHours = 5.0, sleepConsistency = 0.7,
                strengthSessionsPerWeek = 0.0, steps = 8000.0,
            ),
        )
        // sleep: dev 2.0*0.11 = 0.22 ln -> 1.9 y shrunk; strength 0.75 y; consistency 0.05*0.45 = 0.0225 ln -> 0.19 y
        assertEquals(listOf(FactorKey.SLEEP, FactorKey.STRENGTH, FactorKey.CONSISTENCY), r.shortfalls.map { it.key })
        assertTrue(r.helping.any { it.key == FactorKey.RHR })
    }

    @Test
    fun noShortfallsGivesEmptyList() {
        assertTrue(BodyAgePlus.rankShortfalls(emptyList()).isEmpty())
        val f = BodyAgePlus.Factor(FactorKey.SLEEP, -0.1, -0.9, 8.0, 7.5)
        assertTrue(BodyAgePlus.rankShortfalls(listOf(f)).isEmpty())
    }

    @Test
    fun tiesKeepFixedFactorOrderAndAreCappedAtThree() {
        fun f(k: FactorKey, y: Double) = BodyAgePlus.Factor(k, y * ln, y, 0.0, 0.0)
        val ranked = BodyAgePlus.rankShortfalls(
            listOf(f(FactorKey.SLEEP, 1.0), f(FactorKey.RHR, 1.0), f(FactorKey.STEPS, 1.0), f(FactorKey.STRENGTH, 1.0), f(FactorKey.HRV, 2.0)),
        )
        assertEquals(listOf(FactorKey.HRV, FactorKey.SLEEP, FactorKey.RHR), ranked.map { it.key })
    }

    @Test
    fun upstreamVo2IsIncludedWhenBothInputsExist() {
        val r = BodyAgePlus.compute(
            BodyAgePlus.Inputs(chronoAge = 46.0, restingHR = 65.0, sleepHours = 7.5, vo2max = 33.0, expectedVO2max = 37.0),
        )
        assertNotNull(r.factors.singleOrNull { it.key == FactorKey.VO2MAX })
        assertTrue(r.factors.single { it.key == FactorKey.VO2MAX }.years > 0)
    }
}
