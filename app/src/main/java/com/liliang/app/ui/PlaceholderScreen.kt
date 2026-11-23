package com.liliang.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liliang.app.design.GhostButton
import com.liliang.app.design.HanziWatermark
import com.liliang.app.design.InkColors
import com.liliang.app.design.InkSpacing
import com.liliang.app.design.InkText
import com.liliang.app.design.InkType

/**
 * Calm holding screen for rooms and overlays not yet built — a sunk hanzi
 * watermark, a title, and a line on what's coming. Replaced phase by phase.
 */
@Composable
fun PlaceholderScreen(
    hanzi: String,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(InkSpacing.l),
            ) {
                GhostButton("‹ Back", onClick = onBack)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HanziWatermark(hanzi, fontSize = 200.dp, color = InkColors.Bone.copy(alpha = 0.05f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(InkSpacing.s),
                modifier = Modifier.padding(horizontal = InkSpacing.xxl),
            ) {
                InkText(title, style = InkType.Title)
                InkText(
                    subtitle,
                    style = InkType.BodyDim,
                    color = InkColors.BoneFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
