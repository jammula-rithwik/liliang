package com.liliang.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.LiliangSprings
import com.liliang.app.design.LocalMotionEnabled
import com.liliang.app.design.PaperBackground
import com.liliang.app.design.Stamp
import com.liliang.app.design.drawXiangyun
import com.liliang.app.design.orSnap
import com.liliang.app.platform.Haptics
import com.liliang.app.ui.CalendarScreen
import com.liliang.app.ui.ChamberPage
import com.liliang.app.ui.ComboEditorScreen
import com.liliang.app.ui.CurationScreen
import com.liliang.app.ui.DataBackupScreen
import com.liliang.app.ui.DayDetailScreen
import com.liliang.app.ui.DisciplineEditorScreen
import com.liliang.app.ui.EngineSettingsScreen
import com.liliang.app.ui.ExerciseEditorScreen
import com.liliang.app.ui.FormEditorScreen
import com.liliang.app.ui.FormTrackerScreen
import com.liliang.app.ui.GoalEditorScreen
import com.liliang.app.ui.GoalsScreen
import com.liliang.app.ui.HallArtPage
import com.liliang.app.ui.HallScreen
import com.liliang.app.ui.LibraryScreen
import com.liliang.app.ui.PlanEditorScreen
import com.liliang.app.ui.PlansScreen
import com.liliang.app.ui.ScrollScreen
import com.liliang.app.ui.SessionScreen
import com.liliang.app.ui.SettingsScreen
import com.liliang.app.ui.TodayScreen
import com.liliang.app.ui.ExerciseDetailScreen
import com.liliang.app.ui.ExerciseRolesScreen
import com.liliang.app.ui.MartialLogScreen
import com.liliang.app.ui.RoundTimerScreen
import com.liliang.app.ui.RunLogScreen
import com.liliang.app.ui.WorkoutEditorScreen
import com.liliang.app.ui.WorkoutsScreen
import kotlin.math.max

/**
 * The whole app under one roof: four rooms reached from the ink dock, an
 * overlay back stack above them, and a single BackHandler. The dock retreats
 * while a session (or, later, the round timer) holds the screen.
 */
@Composable
fun LiliangApp(viewModel: LiliangViewModel) {
    val topOverlay = viewModel.overlays.lastOrNull()
    // Overlays are focused tasks with their own Back affordance — the dock
    // retreats whenever one is on top, giving each the whole screen.
    val immersive = topOverlay != null
    val ambient = topOverlay == null && (viewModel.tab == LiliangTab.Today || viewModel.tab == LiliangTab.Hall)

    BackHandler(enabled = viewModel.overlays.isNotEmpty()) { viewModel.popOverlay() }

    // ⚖ D4: the session and timer rooms lock to portrait — the phone lies flat
    // on the floor where sensor rotation flips easily, rotation buys nothing in
    // these rooms, and it was replaying one-shot ticks/callouts.
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    val lockPortrait = viewModel.overlays.any { it is Overlay.Session || it is Overlay.RoundTimer }
    androidx.compose.runtime.DisposableEffect(lockPortrait) {
        val activity = activityContext as? android.app.Activity
        activity?.requestedOrientation = if (lockPortrait) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            if (lockPortrait) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // App-scope midnight watcher. The only day-tick used to live on the Today
    // tab (and only while it was composed) — leave the app open on Calendar or
    // Scroll across midnight and the vermillion today-ring, scheduled states
    // and streak all kept yesterday's "now" until a mutation.
    LaunchedEffect(Unit) {
        var day = java.time.LocalDate.now()
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val now = java.time.LocalDate.now()
            if (now != day) {
                day = now
                viewModel.onDayChanged()
            }
        }
    }

    PaperBackground(ambient = ambient) {
        Box(Modifier.fillMaxSize()) {
            // Room content — only the active tab composes.
            // Rooms ink in on a tab change: a short fade with a drift from
            // the side the dock seal travelled from.
            val tabMotion = LocalMotionEnabled.current
            val roomIn = remember(viewModel.tab) { Animatable(if (tabMotion) 0f else 1f) }
            var lastTab by remember { mutableStateOf(viewModel.tab) }
            val fromRight = remember(viewModel.tab) { viewModel.tab.ordinal > lastTab.ordinal }
            LaunchedEffect(viewModel.tab) {
                lastTab = viewModel.tab
                if (tabMotion) roomIn.animateTo(1f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = roomIn.value
                        alpha = 0.2f + 0.8f * t
                        translationX = (1f - t) * 18.dp.toPx() * (if (fromRight) 1f else -1f)
                    },
            ) {
                when (viewModel.tab) {
                    LiliangTab.Today -> TodayScreen(viewModel)
                    LiliangTab.Calendar -> CalendarScreen(viewModel)
                    LiliangTab.Scroll -> ScrollScreen(viewModel)
                    LiliangTab.Hall -> HallScreen(viewModel)
                }
            }

            // Overlay stack — the topmost overlay is rendered opaque over the room.
            if (topOverlay != null) {
                // Page transition: the new page inks in — a quick fade with a
                // short rise when pushed, a gentle settle from slightly larger
                // when returned to. Enter-only by design: the page being left
                // is never recomposed against state that may already be gone.
                val depth = viewModel.overlays.size
                val motion = LocalMotionEnabled.current
                val enter = remember(topOverlay, depth) { Animatable(if (motion) 0f else 1f) }
                var lastDepth by remember { mutableStateOf(depth) }
                val pushed = remember(topOverlay, depth) { depth >= lastDepth }
                LaunchedEffect(topOverlay, depth) {
                    lastDepth = depth
                    if (motion) enter.animateTo(1f, androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(InkColors.Paper)
                        .pointerInput(Unit) {}, // swallow taps to the room beneath
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val t = enter.value
                                alpha = 0.25f + 0.75f * t
                                if (pushed) {
                                    translationY = (1f - t) * 28.dp.toPx()
                                } else {
                                    val sc = 1f + (1f - t) * 0.03f
                                    scaleX = sc; scaleY = sc
                                }
                            },
                    ) {
                        OverlayHost(overlay = topOverlay, viewModel = viewModel)
                    }
                }
            }

            // The dock — hidden during immersive overlays.
            if (!immersive) {
                InkDock(
                    selected = viewModel.tab,
                    onSelect = { viewModel.selectTab(it) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Settings is reachable from every room, not just Today — a 设 seal
            // in the top-right corner of any tab.
            if (topOverlay == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = InkSpacing.s, end = InkSpacing.m)
                        .size(48.dp)
                        .clip(InkShapes.sealAlt)
                        .semantics { contentDescription = "Settings" }
                        .clickable { viewModel.pushOverlay(Overlay.Settings) },
                    contentAlignment = Alignment.Center,
                ) {
                    Stamp("设", tint = InkColors.BoneDim, size = 40.dp)
                }
            }

            // 印 — a newly earned seal stamps down over the page. Held while a
            // session or the round timer has the screen: never interrupt a set.
            LaunchedEffect(viewModel.dashboardSnapshot) { viewModel.checkSeals() }
            val celebrating = viewModel.sealsToCelebrate.firstOrNull()
                ?.let { com.liliang.app.data.SealCatalog.byId[it] }
            if (celebrating != null && topOverlay !is Overlay.Session && topOverlay !is Overlay.RoundTimer) {
                com.liliang.app.ui.SealCelebration(celebrating, onDone = { viewModel.consumeSealCelebration() })
            }

            // Transient status line.
            viewModel.statusMessage?.let { message ->
                StatusToast(
                    message = message,
                    actionLabel = viewModel.statusActionLabel,
                    onAction = viewModel.statusAction,
                    onDismiss = { viewModel.consumeStatus() },
                )
            }

            // Quiet work indicator: a hairline of wet ink along the top edge
            // while a load is in flight — no spinners on the paper.
            if (viewModel.isBusy) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(InkColors.Vermillion.copy(alpha = 0.5f)),
                )
            }
        }

        // One-time launch reveal — the 馆 seal bleeds into Today.
        LaunchReveal()
    }
}

@Composable
private fun OverlayHost(overlay: Overlay, viewModel: LiliangViewModel) {
    val back = { viewModel.popOverlay() }
    when (overlay) {
        is Overlay.Session -> SessionScreen(viewModel = viewModel, onBack = back)
        is Overlay.Settings -> SettingsScreen(viewModel = viewModel, onBack = back)
        is Overlay.DataBackup -> DataBackupScreen(viewModel = viewModel, onBack = back)
        is Overlay.Curation -> CurationScreen(viewModel = viewModel, onBack = back)
        is Overlay.Plans -> PlansScreen(viewModel = viewModel, onBack = back)
        is Overlay.PlanEditor -> PlanEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.EngineSettings -> EngineSettingsScreen(viewModel = viewModel, onBack = back)
        is Overlay.DayDetail -> DayDetailScreen(viewModel = viewModel, onBack = back)
        is Overlay.Goals -> GoalsScreen(viewModel = viewModel, onBack = back)
        is Overlay.GoalEditor -> GoalEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.ArtPage -> HallArtPage(viewModel = viewModel, discipline = overlay.discipline, onBack = back)
        is Overlay.FormTracker -> FormTrackerScreen(viewModel = viewModel, formId = overlay.formId, onBack = back)
        is Overlay.Chamber -> ChamberPage(viewModel = viewModel, number = overlay.number, onBack = back)
        is Overlay.FormEditor -> FormEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.DisciplineEditor -> DisciplineEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.Library -> LibraryScreen(viewModel = viewModel, onBack = back)
        is Overlay.ExerciseEditor -> ExerciseEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.ComboEditor -> ComboEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.Workouts -> WorkoutsScreen(viewModel = viewModel, onBack = back)
        is Overlay.WorkoutEditor -> WorkoutEditorScreen(viewModel = viewModel, onBack = back)
        is Overlay.ExerciseRoles -> ExerciseRolesScreen(viewModel = viewModel, onBack = back)
        is Overlay.RoundTimer -> RoundTimerScreen(viewModel = viewModel, onBack = back)
        is Overlay.RunLog -> RunLogScreen(viewModel = viewModel, date = overlay.date, onBack = back)
        is Overlay.MartialLog -> MartialLogScreen(viewModel = viewModel, date = overlay.date, onBack = back)
        is Overlay.ExerciseDetail -> ExerciseDetailScreen(viewModel = viewModel, name = overlay.name, onBack = back)
        is Overlay.SealAlbum -> com.liliang.app.ui.SealAlbumScreen(viewModel = viewModel, onBack = back)
    }
}

/**
 * The ink dock: four hanzi rooms with the selected glyph riding a springing
 * vermillion seal. Built on equal-width cells so the seal's target is just
 * cell-centre arithmetic.
 */
@Composable
private fun InkDock(
    selected: LiliangTab,
    onSelect: (LiliangTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = LiliangTab.entries
    val selectedIndex = tabs.indexOf(selected)
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = InkSpacing.xl, vertical = InkSpacing.m),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(com.liliang.app.ui.DockBarHeight)
                .clip(InkShapes.card)
                .background(InkColors.PaperHigh)
                .drawBehind {
                    // hairline top edge
                    drawLine(
                        InkColors.Line,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f,
                    )
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cell = maxWidth / tabs.size
                val sealSize = 46.dp
                val targetX = cell * selectedIndex + (cell - sealSize) / 2
                val sealX by animateDpAsState(
                    targetValue = targetX,
                    animationSpec = LiliangSprings.bouncy<androidx.compose.ui.unit.Dp>().orSnap(),
                    label = "dockSeal",
                )
                // The riding seal.
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(sealX.roundToPx(), 0) }
                        .align(Alignment.CenterStart)
                        .size(sealSize)
                        .clip(InkShapes.seal)
                        .background(InkColors.Vermillion),
                )
                // The glyph row.
                Row(Modifier.fillMaxSize()) {
                    tabs.forEach { tab ->
                        val isSel = tab == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = tab.label
                                    role = Role.Tab
                                    this.selected = isSel
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!isSel) {
                                        Haptics.tick(view)
                                        onSelect(tab)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            InkText(
                                tab.hanzi,
                                style = InkType.Seal.copy(fontSize = 22.sp),
                                color = if (isSel) InkColors.Bone else InkColors.BoneFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusToast(
    message: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // A gated rise-and-fade in — the toast used to blink into existence.
    val motion = LocalMotionEnabled.current
    val appear = remember(message) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(message) {
        if (motion) appear.animateTo(1f, androidx.compose.animation.core.tween(220))
        // Toasts carrying an action (UNDO) linger; long messages earn reading time.
        val base = if (actionLabel != null) 4200L else 2200L
        kotlinx.coroutines.delay(base + (message.length * 12L).coerceAtMost(1600L))
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = com.liliang.app.ui.dockClearance() + InkSpacing.s, start = InkSpacing.xl, end = InkSpacing.xl),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 8.dp.toPx()
                }
                .clip(InkShapes.chip)
                .background(InkColors.PaperHigh)
                .padding(horizontal = InkSpacing.l, vertical = InkSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(InkShapes.seal)
                    .background(InkColors.Vermillion),
            )
            InkText(message, style = InkType.BodyDim, color = InkColors.Bone)
            if (actionLabel != null && onAction != null) {
                GhostButton(actionLabel, onClick = {
                    onAction()
                })
            }
        }
    }
}

/**
 * The first thing you see after the system splash: a vermillion blot blooms
 * from centre, the 力量 seal stamps down over it with a gold ring and the
 * name beneath, then it lifts to reveal Today. Once per process.
 */
@Composable
private fun LaunchReveal() {
    // rememberSaveable: plain remember replayed the full-screen bloom on every
    // rotation — mid-session included. Once per PROCESS means once.
    var done by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val bloom = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    if (done) return

    LaunchedEffect(Unit) {
        bloom.animateTo(1f, LiliangSprings.gentle())
        kotlinx.coroutines.delay(360)
        lift.animateTo(1f, LiliangSprings.gentle())
        done = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { done = true } }
            .drawBehind {
                val coverAlpha = 1f - lift.value
                if (coverAlpha <= 0f) return@drawBehind
                val r = max(size.width, size.height) * 1.1f * bloom.value.coerceAtMost(1f)
                drawCircle(
                    color = InkColors.Vermillion.copy(alpha = 0.97f * coverAlpha),
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bloom.value > 0.5f && lift.value < 0.6f) {
            // The stamp lands: starts large and light, presses down to size.
            val press = ((bloom.value - 0.5f) / 0.5f).coerceIn(0f, 1f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    val s = 1.35f - 0.35f * press
                    scaleX = s; scaleY = s
                    alpha = press * (1f - lift.value / 0.6f)
                },
            ) {
                Box(
                    Modifier
                        .size(132.dp)
                        .drawBehind {
                            drawCircle(InkColors.Gold, radius = size.minDimension / 2f, alpha = 0.9f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            drawCircle(InkColors.Gold, radius = size.minDimension / 2f - 6.dp.toPx(), alpha = 0.45f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                            drawXiangyun(Offset(size.width * 0.16f, size.height * 0.86f), 7.dp.toPx(), InkColors.Gold, 0.5f)
                            drawXiangyun(Offset(size.width * 0.84f, size.height * 0.14f), 7.dp.toPx(), InkColors.Gold, 0.5f, flip = true)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InkText("力", style = InkType.SealHero.copy(fontSize = 46.sp, lineHeight = 48.sp), color = InkColors.Bone)
                        InkText("量", style = InkType.SealHero.copy(fontSize = 46.sp, lineHeight = 48.sp), color = InkColors.Bone)
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(InkSpacing.l))
                InkText(
                    "LÌLIÀNG",
                    style = InkType.Label.copy(fontSize = 13.sp, letterSpacing = 6.sp),
                    color = InkColors.Bone.copy(alpha = 0.9f),
                )
            }
        }
    }
}
