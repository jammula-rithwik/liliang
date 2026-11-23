package com.liliang.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liliang.app.LiliangViewModel
import com.liliang.app.Overlay
import com.liliang.app.data.ImportTarget
import com.liliang.app.design.GhostButton
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkShapes
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType
import com.liliang.app.design.SealButton
import com.liliang.app.design.Stamp

private const val EMPTY_SLOT = "\u2014 none \u2014"

@Composable
fun SettingsScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val config = viewModel.configSnapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader("Settings", subtitle = "设 · the workshop behind the hall", onBack = onBack)
        }
        item("coach") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.card)
                    .border(1.dp, InkColors.Line, InkShapes.card)
                    .padding(InkSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stamp("师", tint = InkColors.InkWash, size = 36.dp)
                Spacer(Modifier.width(InkSpacing.m))
                Column(Modifier.weight(1f)) {
                    InkText("The coach", style = InkType.Heading)
                    InkText(
                        if (config.coachEnabled) "Engine prescribes, annotates, suggests." else "Off — raw template targets only.",
                        style = InkType.BodyDim,
                        color = InkColors.BoneFaint,
                    )
                }
                InkToggle(
                    checked = config.coachEnabled,
                    onCheckedChange = { viewModel.setCoachEnabled(it) },
                )
            }
        }
        item("restChime") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.card)
                    .border(1.dp, InkColors.Line, InkShapes.card)
                    .padding(InkSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stamp("息", tint = InkColors.Gold, size = 36.dp)
                Spacer(Modifier.width(InkSpacing.m))
                Column(Modifier.weight(1f)) {
                    InkText("Rest-over chime", style = InkType.Heading)
                    InkText(
                        if (config.restChimeEnabled) "One tick + buzz when planned rest is up." else "Off — the count-up just turns vermillion.",
                        style = InkType.BodyDim,
                        color = InkColors.BoneFaint,
                    )
                }
                InkToggle(
                    checked = config.restChimeEnabled,
                    onCheckedChange = { viewModel.setRestChimeEnabled(it) },
                )
            }
        }
        item("links") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                SettingRow("Goals", "志", "What you're training toward") { viewModel.openGoalsFromDashboard() }
                SettingRow("Workouts", "套", "Saved routines") { viewModel.pushOverlay(Overlay.Workouts) }
                SettingRow("Plans", "历", "Weekday-anchored programmes") { viewModel.pushOverlay(Overlay.Plans) }
                SettingRow("Library", "库", "Exercises & combos") { viewModel.pushOverlay(Overlay.Library) }
                SettingRow("Exercise roles", "役", "Primary / accessory defaults") { viewModel.pushOverlay(Overlay.ExerciseRoles) }
                SettingRow("Dashboard curation", "卷", "Featured lifts & forms") { viewModel.pushOverlay(Overlay.Curation) }
                SettingRow("Data & backup", "藏", "Export, import, auto-backup") { viewModel.pushOverlay(Overlay.DataBackup) }
            }
        }
        item("about") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(InkShapes.card)
                    .border(1.dp, InkColors.Line, InkShapes.card)
                    .padding(InkSpacing.l),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkSpacing.m)) {
                    Stamp("力", filled = true, size = 44.dp)
                    Column {
                        InkText("Lìliàng", style = InkType.Title, color = InkColors.Bone)
                        InkText("力量 · strength, in ink", style = InkType.BodyDim, color = InkColors.BoneDim)
                    }
                }
                Spacer(Modifier.height(InkSpacing.s))
                InkText("TYPEFACES", style = InkType.Label, color = InkColors.BoneFaint)
                InkText("Fraunces · Ma Shan Zheng — both SIL Open Font License.", style = InkType.Label.copy(letterSpacing = 0.2.sp), color = InkColors.BoneDim)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, hanzi: String, subtitle: String, onClick: () -> Unit) {
    InkListRow(title, subtitle = subtitle, hanzi = hanzi, prominent = true, onClick = onClick)
}

@Composable
fun DataBackupScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val config = viewModel.configSnapshot
    var importTarget by rememberSaveable { mutableStateOf(ImportTarget.FULL_DATABASE) }
    // Imports are confirmed before they run — a full import replaces everything.
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::exportDatabase)
    }
    val importLauncher = rememberLauncherForActivityResult(GetContent()) { uri ->
        uri?.let { pendingImportUri = it.toString() }
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.setAutoBackupFolder(uri)
        }
    }
    val ingestFolderLauncher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.setIngestFolder(uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.l),
    ) {
        item("top") {
            EditorHeader("Data & backup", subtitle = "藏 · your history, in your hands", onBack = onBack)
        }
        item("export") {
            Column(
                modifier = Modifier.fillMaxWidth().clip(InkShapes.card).border(1.dp, InkColors.Line, InkShapes.card).padding(InkSpacing.l),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                InkText("Export", style = InkType.Heading)
                InkText("Write the whole database to a file you choose — your portable backup.", style = InkType.BodyDim, color = InkColors.BoneDim)
                Spacer(Modifier.height(InkSpacing.xs))
                SealButton("Export database", hanzi = "出", onClick = { exportLauncher.launch("liliang-backup.db") })
            }
        }
        item("import") {
            Column(
                modifier = Modifier.fillMaxWidth().clip(InkShapes.card).border(1.dp, InkColors.Line, InkShapes.card).padding(InkSpacing.l),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                InkText("Import", style = InkType.Heading)
                InkText("Bring a database (or a slice of one) in. A workout2 export imports here unchanged — same schema.", style = InkType.BodyDim, color = InkColors.BoneDim)
                Spacer(Modifier.height(InkSpacing.xs))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(InkSpacing.s),
                ) {
                    ImportTarget.entries.forEach { target ->
                        InkChip(text = target.label, selected = importTarget == target, onClick = { importTarget = target })
                    }
                }
                Spacer(Modifier.height(InkSpacing.xs))
                SealButton("Choose file & import", hanzi = "入", onClick = { importLauncher.launch("*/*") })
            }
        }
        item("autobackup") {
            Column(
                modifier = Modifier.fillMaxWidth().clip(InkShapes.card).border(1.dp, InkColors.Line, InkShapes.card).padding(InkSpacing.l),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                InkText("Auto-backup", style = InkType.Heading)
                // Behavior + retention stay visible even once configured — the user
                // should always be able to confirm what the app promises to do.
                InkText(
                    "Every logged workout writes a timestamped snapshot of the whole database to the folder below (5 newest kept). Point it at a synced folder and your history follows you.",
                    style = InkType.BodyDim,
                    color = InkColors.BoneFaint,
                )
                if (config.autoBackupFolderLabel.isNotBlank()) {
                    InkText("Folder · ${config.autoBackupFolderLabel}", style = InkType.Body, color = InkColors.Moss)
                } else {
                    InkText("Off — no folder chosen.", style = InkType.Body, color = InkColors.BoneDim)
                }
                config.lastBackup?.let { status ->
                    InkText(
                        if (status.ok) {
                            "Last backup · ${formatBackupTime(status.timeMillis)} · ${status.detail}"
                        } else {
                            "Last backup FAILED · ${formatBackupTime(status.timeMillis)} · ${status.detail}"
                        },
                        style = InkType.Label,
                        color = if (status.ok) InkColors.BoneDim else InkColors.Vermillion,
                    )
                }
                Spacer(Modifier.height(InkSpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    SealButton(if (config.autoBackupFolderLabel.isBlank()) "Choose folder" else "Change folder", hanzi = "藏", compact = true, onClick = { backupFolderLauncher.launch(null) })
                    if (config.autoBackupFolderLabel.isNotBlank()) {
                        GhostButton("Back up now", onClick = { viewModel.backupNow() })
                        GhostButton("Clear", onClick = { viewModel.setAutoBackupFolder(null) }, tint = InkColors.Vermillion)
                    }
                }
                if (config.autoBackupFolderLabel.isNotBlank()) {
                    InkText("Restore: Import above, picking a .bak snapshot from the folder.", style = InkType.Label, color = InkColors.BoneFaint)
                }
            }
        }
        item("ingest") {
            Column(
                modifier = Modifier.fillMaxWidth().clip(InkShapes.card).border(1.dp, InkColors.Line, InkShapes.card).padding(InkSpacing.l),
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
            ) {
                InkText("Folder ingest", style = InkType.Heading)
                InkText(
                    "Reads exercises, combos, forms, workouts, and schedules written as JSON into a folder you choose (a synced one works well) and applies them on every launch. Definitions flow in; only ingest-report.json flows back.",
                    style = InkType.BodyDim,
                    color = InkColors.BoneFaint,
                )
                if (config.ingestFolderLabel.isNotBlank()) {
                    InkText("Folder · ${config.ingestFolderLabel}", style = InkType.Body, color = InkColors.Moss)
                } else {
                    InkText("Off — no folder chosen.", style = InkType.Body, color = InkColors.BoneDim)
                }
                config.lastIngest?.let { status ->
                    InkText(
                        if (status.ok) {
                            "Last ingest · ${formatBackupTime(status.timeMillis)} · ${status.detail}"
                        } else {
                            "Last ingest FAILED · ${formatBackupTime(status.timeMillis)} · ${status.detail}"
                        },
                        style = InkType.Label,
                        color = if (status.ok) InkColors.BoneDim else InkColors.Vermillion,
                    )
                }
                Spacer(Modifier.height(InkSpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(InkSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    SealButton(if (config.ingestFolderLabel.isBlank()) "Choose folder" else "Change folder", hanzi = "丹", compact = true, onClick = { ingestFolderLauncher.launch(null) })
                    if (config.ingestFolderLabel.isNotBlank()) {
                        GhostButton("Ingest now", onClick = { viewModel.runIngestNow() })
                        GhostButton("Clear", onClick = { viewModel.setIngestFolder(null) }, tint = InkColors.Vermillion)
                    }
                }
            }
        }
    }

    pendingImportUri?.let { rawUri ->
        val isFull = importTarget == ImportTarget.FULL_DATABASE
        ConfirmationDialog(
            title = if (isFull) "Replace everything?" else "Import ${importTarget.label.lowercase()}?",
            message = if (isFull) {
                "The chosen file replaces your entire current database — workouts, forms, goals, plans. A recovery snapshot of the current data is kept on-device, but check the file is right."
            } else {
                "Rows from the chosen file are merged into your current data. This can't be un-merged automatically."
            },
            confirmLabel = if (isFull) "Replace" else "Import",
            onDismiss = { pendingImportUri = null },
            onConfirm = {
                viewModel.importDatabase(android.net.Uri.parse(rawUri), importTarget)
                pendingImportUri = null
            },
        )
    }
}

private fun formatBackupTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))

@Composable
fun CurationScreen(viewModel: LiliangViewModel, onBack: () -> Unit) {
    val config = viewModel.configSnapshot
    val forms = viewModel.martialArtsForms
    // The forms list loads lazily; this was the one screen that read it
    // without the guard — fresh process → Settings → Curation offered nothing.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.martialArtsForms.isEmpty()) viewModel.refreshMartialArtsForms()
    }
    val featuredLifts = config.dashboardFeaturedExercises.ifEmpty { listOf("", "", "") }.let {
        (it + listOf("", "", "")).take(3)
    }
    val featuredFormIds = config.dashboardFeaturedFormIds ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = InkSpacing.l, end = InkSpacing.l, top = InkSpacing.s, bottom = overlayClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(InkSpacing.m),
    ) {
        item("top") {
            EditorHeader("Curation", subtitle = "卷 · what the scroll shows", onBack = onBack)
        }
        item("lifts") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("FEATURED LIFTS", style = InkType.Label, color = InkColors.BoneFaint)
                featuredLifts.forEachIndexed { index, name ->
                    InkDropdownField(
                        label = "Slot ${index + 1}",
                        selectedValue = name.takeIf { it.isNotBlank() },
                        options = listOf(EMPTY_SLOT) + config.exercises.map { it.name },
                        onSelect = { chosen ->
                            viewModel.saveDashboardFeaturedExercise(index, if (chosen == EMPTY_SLOT) "" else chosen)
                        },
                    )
                }
            }
        }
        item("forms") {
            Column(verticalArrangement = Arrangement.spacedBy(InkSpacing.s)) {
                InkText("FEATURED FORMS", style = InkType.Label, color = InkColors.BoneFaint)
                InkText(
                    if (featuredFormIds.isEmpty()) "Automatic — forms in progress." else "${featuredFormIds.size} chosen.",
                    style = InkType.BodyDim,
                    color = InkColors.BoneFaint,
                )
                forms.forEach { form ->
                    val selected = form.id in featuredFormIds
                    InkChip(
                        text = form.displayShortTitle,
                        selected = selected,
                        onClick = {
                            val next = if (selected) featuredFormIds - form.id else featuredFormIds + form.id
                            viewModel.setDashboardFeaturedForms(next)
                        },
                    )
                }
                if (featuredFormIds.isNotEmpty()) {
                    GhostButton("Reset to automatic", onClick = { viewModel.setDashboardFeaturedForms(emptyList()) })
                }
            }
        }
    }
}
