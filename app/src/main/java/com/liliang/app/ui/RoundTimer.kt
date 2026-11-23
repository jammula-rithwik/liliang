package com.liliang.app.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.RoundTimerSetup
import com.liliang.app.data.formatDuration
import com.liliang.app.design.BrushDivider
import com.liliang.app.design.GhostButton
import com.liliang.app.design.GoldBurst
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp
import com.liliang.app.design.drawInkBlot
import com.liliang.app.platform.TtsEngine
import com.liliang.app.platform.WorkoutBell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private enum class RoundPhase { READY, GET_READY, WORK, REST, COMPLETE }

private const val GET_READY_SECONDS = 30
private const val INTRO_PAUSE_SECONDS = 4
private const val MOVE_INTERVAL_SECONDS = 2
private const val MOVE_CALLOUT_RATE = 0.85f

/**
 * The forge clock, as a full page in the overlay stack. Plan → do → confirm:
 * the setup (one combo per round) comes from [LiliangViewModel.roundTimerSetup];
 * every exit path settles through the ViewModel, which logs exactly the
 * rounds completed.
 *
 * The clock runs on wall-clock deadlines ([SystemClock.elapsedRealtime]), not
 * counted loops — pause freezes the remaining time and resume continues from
 * it, and every piece of phase state is saveable, so rotation mid-round
 * changes nothing.
 *
 * Sound policy: each round is announced once ("Round 2. Double jab, cross")
 * with the bell; rest announces what's next. Per-move callouts only when the
 * user opted in ("Call moves") — the visual move cycle always runs.
 */
@Composable
fun RoundTimerScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val setup = viewModel.roundTimerSetup
    if (setup == null) {
        // Process death restored the overlay without its setup — nothing to run.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val tts = remember { TtsEngine(context) }
    val bell = remember { WorkoutBell() }
    DisposableEffect(Unit) {
        onDispose {
            tts.shutdown()
            bell.release()
        }
    }
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    val total = setup.rounds.size
    var phaseName by rememberSaveable { mutableStateOf(RoundPhase.READY.name) }
    val phase = RoundPhase.valueOf(phaseName)
    var roundIndex by rememberSaveable { mutableIntStateOf(0) }
    var running by rememberSaveable { mutableStateOf(false) }
    var deadlineMs by rememberSaveable { mutableLongStateOf(0L) }
    var remainingMs by rememberSaveable { mutableLongStateOf(GET_READY_SECONDS * 1000L) }
    // Bumped on every phase entry so back-to-back WORK phases (zero rest)
    // still restart the tick loop.
    var phaseEpoch by rememberSaveable { mutableIntStateOf(0) }
    var currentMove by rememberSaveable { mutableStateOf("") }
    var lastTickSecond by remember { mutableIntStateOf(-1) }

    // Any exit path (buttons, system back, tab switch) settles through the
    // ViewModel — keep it told how many rounds are actually done.
    LaunchedEffect(roundIndex) { viewModel.reportRoundTimerProgress(roundIndex) }

    fun beginPhase(newPhase: RoundPhase, durationSeconds: Int) {
        phaseName = newPhase.name
        remainingMs = durationSeconds * 1000L
        deadlineMs = SystemClock.elapsedRealtime() + remainingMs
        lastTickSecond = -1
        phaseEpoch++
    }

    fun comboFor(index: Int) = setup.rounds.getOrNull(index)

    fun enterWork(index: Int) {
        bell.ringStart()
        tts.speak("Round ${index + 1}. ${comboFor(index)?.name.orEmpty()}")
        currentMove = ""
        beginPhase(RoundPhase.WORK, setup.workSeconds)
    }

    fun advancePhase() {
        when (RoundPhase.valueOf(phaseName)) {
            RoundPhase.GET_READY -> enterWork(roundIndex)
            RoundPhase.WORK -> {
                bell.ringEnd()
                val done = roundIndex + 1
                roundIndex = done
                when {
                    done >= total -> {
                        phaseName = RoundPhase.COMPLETE.name
                        remainingMs = 0L
                        running = false
                        tts.speak("All rounds complete")
                    }
                    setup.restSeconds > 0 -> {
                        tts.speak("Rest. Next: ${comboFor(done)?.name.orEmpty()}")
                        currentMove = ""
                        beginPhase(RoundPhase.REST, setup.restSeconds)
                    }
                    else -> enterWork(done)
                }
            }
            RoundPhase.REST -> enterWork(roundIndex)
            else -> Unit
        }
    }

    fun onSecond(current: RoundPhase, secondsLeft: Int) {
        when (current) {
            RoundPhase.GET_READY -> if (secondsLeft in 1..5) tts.speak("$secondsLeft")
            RoundPhase.WORK -> {
                val moves = comboFor(roundIndex)?.moves.orEmpty()
                if (moves.isEmpty()) return
                val elapsed = setup.workSeconds - secondsLeft
                if (elapsed < INTRO_PAUSE_SECONDS) return
                val moveElapsed = elapsed - INTRO_PAUSE_SECONDS
                if (moveElapsed % MOVE_INTERVAL_SECONDS == 0) {
                    val move = moves[(moveElapsed / MOVE_INTERVAL_SECONDS) % moves.size]
                    currentMove = move.displayName
                    if (setup.callMoves) tts.speak(move.callout, rate = MOVE_CALLOUT_RATE)
                }
            }
            else -> Unit
        }
    }

    // The single clock driver. Restarts on pause/resume and on every phase
    // entry; survives recomposition and rotation because the deadline is
    // wall-clock, not loop progress.
    LaunchedEffect(running, phaseEpoch) {
        val current = RoundPhase.valueOf(phaseName)
        if (!running || current == RoundPhase.READY || current == RoundPhase.COMPLETE) return@LaunchedEffect
        while (isActive) {
            val left = deadlineMs - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                remainingMs = 0L
                advancePhase()
                return@LaunchedEffect
            }
            remainingMs = left
            val secondsLeft = ((left + 999) / 1000).toInt()
            if (secondsLeft != lastTickSecond) {
                lastTickSecond = secondsLeft
                onSecond(RoundPhase.valueOf(phaseName), secondsLeft)
            }
            delay(200)
        }
    }

    fun start() {
        tts.speak("Get ready. First round: ${setup.rounds.first().name}")
        beginPhase(RoundPhase.GET_READY, GET_READY_SECONDS)
        running = true
    }

    fun pause() {
        remainingMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        running = false
        tts.stop()
    }

    fun resume() {
        deadlineMs = SystemClock.elapsedRealtime() + remainingMs
        phaseEpoch++
        running = true
    }

    val leave: () -> Unit = {
        tts.stop()
        viewModel.finishRoundTimer()
    }

    val accent = when (phase) {
        RoundPhase.READY -> InkColors.Mist
        RoundPhase.GET_READY -> InkColors.GoldBright
        RoundPhase.WORK -> InkColors.Vermillion
        RoundPhase.REST -> InkColors.Mist
        RoundPhase.COMPLETE -> InkColors.Gold
    }
    val animatedAccent by animateColorAsState(accent, tween(200), label = "phaseColor")

    val secondsRemaining = ((remainingMs + 999) / 1000).toInt()
    val phaseLength = when (phase) {
        RoundPhase.GET_READY -> GET_READY_SECONDS
        RoundPhase.WORK -> setup.workSeconds
        RoundPhase.REST -> setup.restSeconds
        else -> 1
    }.coerceAtLeast(1)
    val drainedTarget = when (phase) {
        RoundPhase.READY -> 0f
        RoundPhase.COMPLETE -> 1f
        else -> 1f - (remainingMs.toFloat() / (phaseLength * 1000f))
    }.coerceIn(0f, 1f)
    val drained by animateFloatAsState(drainedTarget, tween(240, easing = LinearEasing), label = "drain")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = InkSpacing.xl, vertical = InkSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Masthead — the hall's voice, not a utility header.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Stamp("武", filled = phase == RoundPhase.WORK, tint = animatedAccent, size = 40.dp)
            Spacer(Modifier.width(InkSpacing.m))
            Column(Modifier.weight(1f)) {
                InkText("ROUND TIMER", style = InkType.Label, color = InkColors.BoneFaint)
                InkText(
                    when (phase) {
                        RoundPhase.READY -> "The plan"
                        RoundPhase.GET_READY -> "Gloves on"
                        RoundPhase.WORK -> "Round ${roundIndex + 1}"
                        RoundPhase.REST -> "Rest"
                        RoundPhase.COMPLETE -> "Complete"
                    },
                    style = InkType.Heading,
                    color = animatedAccent,
                )
            }
            InkText(
                "${roundIndex.coerceAtMost(total)} / $total",
                style = InkType.NumberSmall.copy(fontSize = 18.sp),
                color = InkColors.BoneDim,
            )
        }
        Spacer(Modifier.height(InkSpacing.s))
        RoundDots(total = total, completed = roundIndex, active = phase == RoundPhase.WORK, accent = animatedAccent)
        Spacer(Modifier.height(InkSpacing.s))
        BrushDivider(seed = 47)
        Spacer(Modifier.height(InkSpacing.m))

        if (phase == RoundPhase.COMPLETE) {
            CompleteCelebration(
                total = total,
                workSeconds = setup.workSeconds,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                TimerRing(drained = drained, accent = animatedAccent)
                PulsingClock(
                    seconds = secondsRemaining,
                    accent = if (phase == RoundPhase.READY) InkColors.BoneFaint else animatedAccent,
                    pulse = phase == RoundPhase.WORK && secondsRemaining in 1..5,
                )
            }
            Spacer(Modifier.height(InkSpacing.m))

            // The reading matter: during WORK the live move; before a round,
            // the upcoming combo — big enough to rehearse from across the bag.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (phase) {
                    RoundPhase.READY -> PlanPreview(setup)
                    RoundPhase.WORK -> {
                        val combo = comboFor(roundIndex)
                        InkText(combo?.name.orEmpty(), style = InkType.Label, color = InkColors.BoneFaint)
                        Spacer(Modifier.height(InkSpacing.xs))
                        InkText(
                            currentMove.ifBlank { combo?.name ?: "Work" },
                            style = InkType.NumberHuge.copy(fontSize = 52.sp, lineHeight = 58.sp),
                            color = InkColors.Bone,
                            textAlign = TextAlign.Center,
                        )
                    }
                    RoundPhase.GET_READY, RoundPhase.REST -> {
                        val next = comboFor(roundIndex)
                        InkText(
                            "UP NEXT · ROUND ${(roundIndex + 1).coerceAtMost(total)}",
                            style = InkType.Label,
                            color = animatedAccent,
                        )
                        Spacer(Modifier.height(InkSpacing.xs))
                        InkText(
                            next?.name ?: "Get ready",
                            style = InkType.NumberHuge.copy(fontSize = 44.sp, lineHeight = 50.sp),
                            color = InkColors.Bone,
                            textAlign = TextAlign.Center,
                        )
                        val preview = next?.moves.orEmpty().joinToString(" · ") { it.displayName }
                        if (preview.isNotBlank()) {
                            Spacer(Modifier.height(InkSpacing.s))
                            InkText(preview, style = InkType.Body, color = InkColors.BoneDim, textAlign = TextAlign.Center, maxLines = 3)
                        }
                    }
                    else -> Unit
                }
            }
        }

        Spacer(Modifier.height(InkSpacing.m))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
            when {
                phase == RoundPhase.COMPLETE -> {
                    SealButton(
                        if (total == 1) "Done · log 1 round" else "Done · log $total rounds",
                        hanzi = "成",
                        tint = InkColors.Gold,
                        onClick = leave,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                phase == RoundPhase.READY -> {
                    SealButton("Let's go", hanzi = "武", onClick = { start() }, modifier = Modifier.weight(1f))
                    GhostButton("Cancel", onClick = leave, modifier = Modifier.weight(1f))
                }
                running -> {
                    SealButton("Pause", onClick = { pause() }, tint = animatedAccent, modifier = Modifier.weight(1f))
                    GhostButton(
                        if (roundIndex > 0) "Stop · log $roundIndex" else "Stop",
                        onClick = leave,
                        tint = InkColors.Vermillion,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> {
                    SealButton("Resume", onClick = { resume() }, tint = animatedAccent, modifier = Modifier.weight(1f))
                    GhostButton(
                        if (roundIndex > 0) "Stop · log $roundIndex" else "Stop",
                        onClick = leave,
                        tint = InkColors.Vermillion,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The plan, readable before committing: each round with its combo. */
@Composable
private fun PlanPreview(setup: RoundTimerSetup) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        setup.rounds.forEachIndexed { i, combo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkText(
                    "${i + 1}",
                    style = InkType.NumberSmall.copy(fontSize = 15.sp),
                    color = InkColors.Vermillion,
                )
                Spacer(Modifier.width(InkSpacing.m))
                InkText(combo.name, style = InkType.Body.copy(fontSize = 16.sp), maxLines = 1)
            }
        }
        Spacer(Modifier.height(InkSpacing.s))
        InkText(
            "${setup.rounds.size} rounds · ${formatDuration(setup.workSeconds)} work · ${formatDuration(setup.restSeconds)} rest",
            style = InkType.Label,
            color = InkColors.BoneFaint,
        )
    }
}

/** One seal per round — pressed when done, ringed while waiting. */
@Composable
private fun RoundDots(total: Int, completed: Int, active: Boolean, accent: Color) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        if (total <= 0) return@Canvas
        val r = 4.dp.toPx()
        val gap = 10.dp.toPx()
        val totalWidth = total * (r * 2) + (total - 1) * gap
        var x = (size.width - totalWidth) / 2f + r
        val cy = size.height / 2f
        for (i in 0 until total) {
            when {
                i < completed -> drawInkBlot(Offset(x, cy), r * 1.15f, InkColors.Gold, seed = i * 17 + 3)
                i == completed && active -> drawCircle(accent, radius = r, center = Offset(x, cy))
                else -> drawCircle(InkColors.BoneFaint, radius = r * 0.8f, center = Offset(x, cy), style = Stroke(1.5.dp.toPx()))
            }
            x += r * 2 + gap
        }
    }
}

/** Gold seal + burst — the session is in the book. */
@Composable
private fun CompleteCelebration(total: Int, workSeconds: Int, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            GoldBurst(trigger = total, modifier = Modifier.size(220.dp))
            Stamp("成", tint = InkColors.Gold, filled = true, size = 96.dp)
        }
        Spacer(Modifier.height(InkSpacing.l))
        InkText("All rounds complete", style = InkType.Title.copy(fontSize = 24.sp))
        Spacer(Modifier.height(InkSpacing.xs))
        InkText(
            "$total × ${formatDuration(workSeconds)} on the bag",
            style = InkType.Body,
            color = InkColors.BoneDim,
        )
    }
}

/**
 * The draining ink ring. Functional information — it IS the clock — so it is
 * never gated on the motion setting. The drain head carries a small ink pool,
 * so the remaining arc reads as wet ink being drawn down, not a progress bar.
 */
@Composable
private fun TimerRing(drained: Float, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val sw = 13.dp.toPx()
        val inset = sw / 2 + 3.dp.toPx()
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        // paper track
        drawArc(
            color = InkColors.Bone.copy(alpha = 0.07f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        val remainingSweep = 360f * (1f - drained.coerceIn(0f, 1f))
        if (remainingSweep > 0.5f) {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = remainingSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            // dry-brush streak inside the stroke
            drawArc(
                color = InkColors.Paper.copy(alpha = 0.30f),
                startAngle = -90f,
                sweepAngle = (remainingSweep - 3f).coerceAtLeast(0f),
                useCenter = false,
                topLeft = Offset(topLeft.x, topLeft.y + sw * 0.22f),
                size = arcSize,
                style = Stroke(sw * 0.18f, cap = StrokeCap.Round),
            )
            // the wet pool at the drain head
            val headAngleRad = Math.toRadians((-90f + remainingSweep).toDouble())
            val radius = arcSize.width / 2f
            val center = Offset(topLeft.x + radius, topLeft.y + arcSize.height / 2f)
            val head = Offset(
                center.x + radius * cos(headAngleRad).toFloat(),
                center.y + radius * sin(headAngleRad).toFloat(),
            )
            drawInkBlot(center = head, radius = sw * 0.95f, color = accent, seed = 11)
        }
    }
}

@Composable
private fun PulsingClock(seconds: Int, accent: Color, pulse: Boolean) {
    val scale = remember { Animatable(1f) }
    val motion = com.liliang.app.design.LocalMotionEnabled.current
    LaunchedEffect(seconds, pulse) {
        if (pulse && motion) {
            scale.animateTo(1.12f, tween(120))
            scale.animateTo(1f, tween(220))
        }
    }
    InkText(
        formatRoundClock(seconds),
        style = InkType.NumberHuge.copy(fontSize = 88.sp, lineHeight = 92.sp),
        color = accent,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

private fun formatRoundClock(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
