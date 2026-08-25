package com.neopal.pet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.ui.theme.NeoColors

/** The four face buttons on the right rail. */
enum class FaceButton { A, B, X, Y }

/** Directions on the left rail's pad. */
enum class PadDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Under this the rails would eat a third of the width. The rails are chassis decoration plus a
 * duplicate of controls the dock already offers; the pet is the thing worth the pixels, so on a
 * very narrow phone the screen takes the whole window instead.
 */
private const val RAILS_MIN_WIDTH_DP = 340

/**
 * Wraps content in a handheld-console chassis: a dark body, two colored side rails with
 * working controls, and a bezelled screen in the middle. The rails are real input, not
 * decoration — every button is focusable and labelled for TalkBack.
 *
 * The chassis is sized from the window: slim rails on a phone, generous ones on a tablet, and in
 * a short landscape window the rails hug the screen edges so the play area keeps its aspect.
 *
 * The design is a generic modern handheld; it carries no third-party branding or logos.
 */
@Composable
fun ConsoleFrame(
    modifier: Modifier = Modifier,
    leftRail: Color = NeoColors.NeonCyan,
    rightRail: Color = NeoColors.NeonRed,
    showRails: Boolean = true,
    onPad: (PadDirection) -> Unit = {},
    onFace: (FaceButton) -> Unit = {},
    onMenu: () -> Unit = {},
    onHome: () -> Unit = {},
    screen: @Composable BoxScope.() -> Unit,
) {
    val window = rememberWindowSize()
    val railsVisible = showRails && window.widthDp >= RAILS_MIN_WIDTH_DP
    val shortLandscape = window.isLandscape && window.isShort

    val baseRailWidth = when (window.width) {
        WidthClass.COMPACT -> 56.dp
        WidthClass.MEDIUM -> 78.dp
        WidthClass.EXPANDED -> 112.dp
    }
    val railWidth = if (shortLandscape) baseRailWidth * 0.78f else baseRailWidth
    // Short landscape: every dp of chassis is a dp the scene does not get. Pull it to the edges.
    val chassisPadding = if (shortLandscape) 2.dp else 6.dp
    val railGap = if (shortLandscape) 3.dp else 6.dp
    val bezelPadding = if (shortLandscape) 3.dp else 5.dp
    // The stick and shoulder pads are pure ornament; they are the first thing to go when the rail
    // is too short to hold ornament and 48dp controls at the same time.
    val ornaments = !window.isShort

    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(listOf(NeoColors.ChassisGrey, NeoColors.ChassisBlack)),
            )
            .padding(chassisPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (railsVisible) {
            LeftRail(color = leftRail, railWidth = railWidth, ornaments = ornaments, onPad = onPad, onMenu = onMenu)
            Spacer(Modifier.width(railGap))
        }

        // The screen: black bezel, rounded glass, subtle scanlines.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(if (shortLandscape) 14.dp else 20.dp))
                .background(NeoColors.ScreenBezel)
                .padding(bezelPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(if (shortLandscape) 11.dp else 16.dp))
                    .background(NeoColors.SurfaceDark),
            ) {
                screen()
                ScanlineOverlay(Modifier.fillMaxSize(), alpha = 0.035f)
            }
        }

        if (railsVisible) {
            Spacer(Modifier.width(railGap))
            RightRail(color = rightRail, railWidth = railWidth, ornaments = ornaments, onFace = onFace, onHome = onHome)
        }
    }
}

@Composable
private fun LeftRail(
    color: Color,
    railWidth: Dp,
    ornaments: Boolean,
    onPad: (PadDirection) -> Unit,
    onMenu: () -> Unit,
) {
    RailColumn(color = color, railWidth = railWidth, edge = RailEdge.LEFT) {
        if (ornaments) {
            ShoulderPad(railWidth)
            AnalogStick(railWidth)
        }

        // Directional pad. It stays a cross, but each key is a full-height touch row so the
        // target never drops below 48dp even when the rail itself is phone-narrow.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            RailKey("▲", stringResource(R.string.pad_up), railWidth, RoundedCornerShape(6.dp), Modifier.fillMaxWidth()) { onPad(PadDirection.UP) }
            Row(Modifier.fillMaxWidth()) {
                RailKey("◀", stringResource(R.string.pad_left), railWidth, RoundedCornerShape(6.dp), Modifier.weight(1f)) { onPad(PadDirection.LEFT) }
                RailKey("▶", stringResource(R.string.pad_right), railWidth, RoundedCornerShape(6.dp), Modifier.weight(1f)) { onPad(PadDirection.RIGHT) }
            }
            RailKey("▼", stringResource(R.string.pad_down), railWidth, RoundedCornerShape(6.dp), Modifier.fillMaxWidth()) { onPad(PadDirection.DOWN) }
        }

        RailKey("–", stringResource(R.string.pad_menu), railWidth, CircleShape, Modifier.fillMaxWidth(), glyphScale = 0.34f) { onMenu() }
    }
}

@Composable
private fun RightRail(
    color: Color,
    railWidth: Dp,
    ornaments: Boolean,
    onFace: (FaceButton) -> Unit,
    onHome: () -> Unit,
) {
    RailColumn(color = color, railWidth = railWidth, edge = RailEdge.RIGHT) {
        if (ornaments) {
            ShoulderPad(railWidth)
        }

        RailKey("+", stringResource(R.string.pad_home), railWidth, CircleShape, Modifier.fillMaxWidth(), glyphScale = 0.34f) { onHome() }

        // Face buttons, diamond layout.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            RailKey("X", stringResource(R.string.action_play), railWidth, CircleShape, Modifier.fillMaxWidth(), bold = true) { onFace(FaceButton.X) }
            Row(Modifier.fillMaxWidth()) {
                RailKey("Y", stringResource(R.string.action_feed), railWidth, CircleShape, Modifier.weight(1f), bold = true) { onFace(FaceButton.Y) }
                RailKey("A", stringResource(R.string.pad_confirm), railWidth, CircleShape, Modifier.weight(1f), bold = true) { onFace(FaceButton.A) }
            }
            RailKey("B", stringResource(R.string.nav_back), railWidth, CircleShape, Modifier.fillMaxWidth(), bold = true) { onFace(FaceButton.B) }
        }

        if (ornaments) {
            AnalogStick(railWidth)
        }
    }
}

private enum class RailEdge { LEFT, RIGHT }

@Composable
private fun RailColumn(
    color: Color,
    railWidth: Dp,
    edge: RailEdge,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (edge == RailEdge.LEFT) {
        RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp, topStart = 8.dp, bottomStart = 8.dp)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.72f))))
            .padding(vertical = 8.dp, horizontal = 2.dp),
        content = content,
    )
}

@Composable
private fun ShoulderPad(railWidth: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height((railWidth * 0.22f).coerceIn(10.dp, 18.dp))
            .clip(RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = 0.28f)),
    )
}

@Composable
private fun AnalogStick(railWidth: Dp) {
    // read outside `semantics {}`, which is not composition
    val stickReadOut = stringResource(R.string.cd_analog_stick)
    Box(
        modifier = Modifier
            .size((railWidth * 0.55f).coerceIn(30.dp, 54.dp))
            .clip(CircleShape)
            .background(NeoColors.ChassisBlack)
            .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.12f)), CircleShape)
            .semantics { contentDescription = stickReadOut },
    )
}

/**
 * One rail control. The hit area is the whole cell — at least 48dp tall and as wide as the rail
 * allows — while the visible cap stays small enough to read as a console key at any rail width.
 */
@Composable
private fun RailKey(
    glyph: String,
    label: String,
    railWidth: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    glyphScale: Float = 0.4f,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val cap = (railWidth * glyphScale).coerceIn(20.dp, 40.dp)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 24.dp, minHeight = MinTouchTarget)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(cap)
                .clip(shape)
                .background(NeoColors.ChassisBlack.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                fontWeight = if (bold) FontWeight.Bold else null,
            )
        }
    }
}
