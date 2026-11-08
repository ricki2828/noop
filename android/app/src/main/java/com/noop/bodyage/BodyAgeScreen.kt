package com.noop.bodyage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ui.AppViewModel
import com.noop.ui.CardAppearance
import com.noop.ui.CountUpText
import com.noop.ui.LineChart
import com.noop.ui.LiquidVessel
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.ProfileStore
import com.noop.ui.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Body Age+ UI (fork-only). Design tokens only (Palette / Metrics / NoopType) and existing Liquid components.
// Every string comes from BodyAgeCopy. Layout follows the design review: number, why, trend.

private const val ANSWERED_COLLAPSE_MS = 1500L

/** Colour for older / younger. Always shown next to text, never alone. */
private fun deltaColor(delta: Double): Color =
    if (delta > 0.5) Palette.statusWarning else if (delta < -0.5) Palette.statusPositive else Palette.textSecondary

/** 0..1 fill for the vessel: half full at your age, fuller the younger you read (Vitality's 2.5 points per year). */
private fun vesselFraction(delta: Double): Double = (0.5 - delta * 2.5 / 100.0).coerceIn(0.0, 1.0)

/** Loads the snapshot for the Today card and screen and owns the drinks answer. */
@Composable
fun BodyAgePlusToday(vm: AppViewModel) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val store = remember { DrinksStore(vm.repo) }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<BodyAgeSnapshot?>(null) }
    var seq by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var saveFailed by remember { mutableStateOf(false) }

    // Heavy (HR merge, 50+ builds): off the main thread, and re-run on a new day row or value, not every emission.
    LaunchedEffect(days.size, days.lastOrNull(), seq) {
        val loaded = withContext(Dispatchers.Default) {
            runCatching { BodyAgeLoader.load(vm.repo, vm.activeStrapId, profile, days, store) }.getOrNull()
        }
        if (loaded != null) snapshot = loaded
    }

    val snap = snapshot
    fun answer(count: Int) {
        val day = snap?.yesterday?.toString() ?: DrinksStore.yesterdayKey()
        picked = count
        saveFailed = false
        scope.launch {
            val ok = runCatching { store.setDrinks(day, count) }.isSuccess
            if (!ok) { picked = null; saveFailed = true; return@launch }
            delay(ANSWERED_COLLAPSE_MS)
            seq++
            picked = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        BodyAgeCard(snap, profile.age.toDouble(), onClick = { open = true })
        if (snap != null && (snap.drinksYesterday == null || picked != null)) {
            NoopCard {
                DrinksRow(selected = picked, failed = saveFailed, onPick = ::answer)
            }
        }
    }
    if (open) {
        BodyAgeDialog(snap, store, onDrinks = { seq++ }, onClose = { open = false })
    }
}

@Composable
private fun BodyAgeCard(snap: BodyAgeSnapshot?, chronoAge: Double, onClick: () -> Unit) {
    val result = snap?.result
    val age = result?.bodyAge
    val delta = result?.deltaYears
    val line = when {
        snap == null -> BodyAgeCopy.LOADING
        age == null || delta == null -> BodyAgeCopy.CARD_EMPTY
        else -> BodyAgeCopy.cardDelta(delta, chronoAge) +
            (if (result.notCounted.isNotEmpty()) BodyAgeCopy.cardPartial(result.notCounted.size) else "")
    }
    val description = if (age != null) "${BodyAgeCopy.NAME}, ${age.roundToInt()}, $line" else "${BodyAgeCopy.NAME}, $line"
    NoopCard(modifier = Modifier.clickable(onClick = onClick).semantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            LiquidVessel(
                value = if (delta != null) vesselFraction(delta) else null,
                tint = Palette.chargeColor,
                animated = false,
                modifier = Modifier.size(Metrics.space24 + Metrics.space6),
            )
            Column(modifier = Modifier.weight(1f)) {
                Overline(BodyAgeCopy.NAME)
                Text(
                    line,
                    style = NoopType.subhead,
                    color = if (delta != null) deltaColor(delta) else Palette.textTertiary,
                )
            }
            if (age != null) {
                Text(age.roundToInt().toString(), style = NoopType.number(26f), color = Palette.textPrimary)
            }
            Text("›", style = NoopType.title2, color = Palette.textTertiary)
        }
    }
}

@Composable
private fun DrinksRow(selected: Int?, failed: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(BodyAgeCopy.DRINKS_PROMPT, style = NoopType.headline, color = Palette.textPrimary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            BodyAgeCopy.DRINK_CHIPS.forEachIndexed { count, label ->
                DrinkChip(label, BodyAgeCopy.drinkChipDescription(count), selected == count, Modifier.weight(1f)) { onPick(count) }
            }
        }
        if (failed) Text(BodyAgeCopy.DRINKS_NOT_SAVED, style = NoopType.footnote, color = Palette.statusWarning)
    }
}

@Composable
private fun DrinkChip(label: String, description: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (selected) Palette.accentMuted else Palette.surfaceInset)
            .border(Metrics.divider, if (selected) Palette.hairlineStrong else Palette.hairline, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NoopType.number(18f), color = Palette.textPrimary, modifier = Modifier.padding(vertical = Metrics.space8))
    }
}

@Composable
private fun BodyAgeDialog(snap: BodyAgeSnapshot?, store: DrinksStore, onDrinks: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Palette.surfaceBase)) {
            ScreenScaffold(
                title = BodyAgeCopy.NAME,
                subtitle = BodyAgeCopy.SCREEN_SUBTITLE,
                trailing = { CloseChip(onClose) },
            ) {
                if (snap == null) {
                    Text(BodyAgeCopy.LOADING, style = NoopType.subhead, color = Palette.textTertiary)
                } else {
                    BodyAgeContent(snap, store, onDrinks)
                }
            }
        }
    }
}

@Composable
private fun CloseChip(onClose: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Text(
        BodyAgeCopy.CLOSE,
        style = NoopType.caption,
        color = Palette.textSecondary,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, shape)
            .clickable(onClick = onClose)
            .padding(horizontal = Metrics.space12, vertical = Metrics.space6),
    )
}

@Composable
private fun BodyAgeContent(snap: BodyAgeSnapshot, store: DrinksStore, onDrinks: () -> Unit) {
    val result = snap.result
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Int?>(snap.drinksYesterday) }
    var failed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Hero(snap)

        if (result.bodyAge != null) {
            SectionLabel(BodyAgeCopy.SECTION_SHORT)
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                    if (result.shortfalls.isEmpty()) {
                        Text(BodyAgeCopy.NOTHING_SHORT, style = NoopType.subhead, color = Palette.textSecondary)
                    }
                    result.shortfalls.forEach { f ->
                        Column(
                            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                contentDescription = BodyAgeCopy.shortfallDescription(f.key, f.actual, f.target, f.years)
                            },
                            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(BodyAgeCopy.label(f.key), style = NoopType.headline, color = Palette.textPrimary)
                                    Text(BodyAgeCopy.gap(f.key, f.actual, f.target), style = NoopType.subhead, color = Palette.textSecondary)
                                }
                                Text(BodyAgeCopy.yearsBack(f.years), style = NoopType.subhead, color = Palette.statusWarning)
                            }
                            Text(BodyAgeCopy.action(f.key), style = NoopType.footnote, color = Palette.textTertiary)
                        }
                    }
                }
            }
            if (result.helping.isNotEmpty()) {
                SectionLabel(BodyAgeCopy.SECTION_HELPING)
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        result.helping.forEach { f ->
                            Text(BodyAgeCopy.helpingLine(f.key, f.actual, f.years), style = NoopType.subhead, color = Palette.statusPositive)
                        }
                    }
                }
            }
        }

        SectionLabel(BodyAgeCopy.SECTION_TREND)
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                if (snap.trend.size < 2) {
                    Text(BodyAgeCopy.TREND_EMPTY, style = NoopType.subhead, color = Palette.textTertiary)
                } else {
                    // A new segment id after a missing week makes the chart draw a break, not an interpolated line.
                    var seg = 0
                    val ids = snap.trend.mapIndexed { i, p ->
                        if (i > 0 && p.day.toEpochDay() - snap.trend[i - 1].day.toEpochDay() > 7) seg++
                        "s$seg"
                    }
                    LineChart(
                        values = snap.trend.map { it.bodyAge },
                        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                        color = Palette.chargeColor,
                        segmentIds = ids,
                    )
                    Text(BodyAgeCopy.chronoReference(result.chronoAge), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }

        SectionLabel(BodyAgeCopy.SECTION_DRINKS)
        NoopCard {
            DrinksRow(selected = picked, failed = failed) { count ->
                picked = count
                failed = false
                scope.launch {
                    val ok = runCatching { store.setDrinks(snap.yesterday.toString(), count) }.isSuccess
                    if (ok) onDrinks() else { picked = snap.drinksYesterday; failed = true }
                }
            }
        }

        if (result.notCounted.isNotEmpty()) {
            SectionLabel(BodyAgeCopy.SECTION_NOT_COUNTED)
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    result.notCounted.forEach {
                        Text(BodyAgeCopy.notCounted(it.key, it.reason), style = NoopType.subhead, color = Palette.textSecondary)
                    }
                }
            }
        }

        val zoneNote = BodyAgeCopy.zoneSource(snap.hrDays, snap.zoneDays)
        if (zoneNote.isNotEmpty()) Text(zoneNote, style = NoopType.footnote, color = Palette.textTertiary)
        Text(BodyAgeCopy.FOOTER, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun SectionLabel(text: String) = Overline(text)

@Composable
private fun Hero(snap: BodyAgeSnapshot) {
    val result = snap.result
    val age = result.bodyAge
    val delta = result.deltaYears
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.heroFill.copy(alpha = Palette.heroFill.alpha * CardAppearance.opacity))
            .border(Metrics.divider, Palette.heroBorder.copy(alpha = Palette.heroBorder.alpha * CardAppearance.opacity), shape)
            .padding(Metrics.cardPadding),
    ) {
        if (age == null || delta == null) {
            Text(BodyAgeCopy.HERO_EMPTY, style = NoopType.headline, color = Palette.textPrimary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                val diameter = Metrics.space24 * 4
                Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
                    LiquidVessel(value = vesselFraction(delta), tint = Palette.chargeColor, animated = true, modifier = Modifier.size(diameter))
                    CountUpText(
                        value = age,
                        format = { it.roundToInt().toString() },
                        style = NoopType.number(30f, weight = FontWeight.Bold)
                            .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
                        color = Color.White,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(BodyAgeCopy.heroDelta(delta), style = NoopType.headline, color = deltaColor(delta))
                    Text(BodyAgeCopy.band(result.bandYears), style = NoopType.footnote, color = Palette.textTertiary)
                    snap.thirtyDayChange?.let { change ->
                        snap.thirtyDayFrom?.let { from ->
                            Text(BodyAgeCopy.thirtyDay(change, from), style = NoopType.subhead, color = if (change < 0) Palette.statusPositive else Palette.statusWarning)
                        }
                    }
                }
            }
        }
    }
}
