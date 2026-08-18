package com.neopal.pet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neopal.pet.ui.theme.NeoColors

/** The four face buttons on the right rail. */
enum class FaceButton { A, B, X, Y }

/** Directions on the left rail's pad. */
enum class PadDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Wraps content in a handheld-console chassis: a dark body, two colored side rails with
 * working controls, and a bezelled screen in the middle. The rails are real input, not
 * decoration — every button is focusable and labelled for TalkBack.
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
    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(listOf(NeoColors.ChassisGrey, NeoColors.ChassisBlack)),
            )
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRails) {
            LeftRail(color = leftRail, onPad = onPad, onMenu = onMenu)
            Spacer(Modifier.width(6.dp))
        }

        // The screen: black bezel, rounded glass, subtle scanlines.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(NeoColors.ScreenBezel)
                .padding(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NeoColors.SurfaceDark),
            ) {
                screen()
                ScanlineOverlay(Modifier.fillMaxSize(), alpha = 0.035f)
            }
        }

        if (showRails) {
            Spacer(Modifier.width(6.dp))
            RightRail(color = rightRail, onFace = onFace, onHome = onHome)
        }
    }
}

@Composable
private fun LeftRail(color: Color, onPad: (PadDirection) -> Unit, onMenu: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 8.dp, bottomEnd = 8.dp))
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.72f))))
            .padding(vertical = 12.dp),
    ) {
        // Shoulder button.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.28f)),
        )

        // Analog stick.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(NeoColors.ChassisBlack)
                .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.12f)), CircleShape)
                .semantics { contentDescription = "Analog stick" },
        )

        // Directional pad.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadKey("▲", "Up") { onPad(PadDirection.UP) }
            Row {
                PadKey("◀", "Left") { onPad(PadDirection.LEFT) }
                Spacer(Modifier.width(18.dp))
                PadKey("▶", "Right") { onPad(PadDirection.RIGHT) }
            }
            PadKey("▼", "Down") { onPad(PadDirection.DOWN) }
        }

        // Minus / menu.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable { onMenu() }
                .semantics { contentDescription = "Menu" },
            contentAlignment = Alignment.Center,
        ) {
            Text("–", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RightRail(color: Color, onFace: (FaceButton) -> Unit, onHome: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp, topStart = 8.dp, bottomStart = 8.dp))
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.72f))))
            .padding(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.28f)),
        )

        // Plus button.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable { onHome() }
                .semantics { contentDescription = "Home" },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }

        // Face buttons, diamond layout.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FaceKey("X", "Play") { onFace(FaceButton.X) }
            Row {
                FaceKey("Y", "Feed") { onFace(FaceButton.Y) }
                Spacer(Modifier.width(14.dp))
                FaceKey("A", "Confirm") { onFace(FaceButton.A) }
            }
            FaceKey("B", "Back") { onFace(FaceButton.B) }
        }

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(NeoColors.ChassisBlack)
                .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.12f)), CircleShape)
                .semantics { contentDescription = "Analog stick" },
        )
    }
}

@Composable
private fun PadKey(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(NeoColors.ChassisBlack.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FaceKey(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(NeoColors.ChassisBlack.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
