@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neopal.pet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.neopal.pet.R
import com.neopal.pet.data.UpdateStatus
import com.neopal.pet.domain.UpdateTone
import com.neopal.pet.ui.UpdateViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelDivider
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelShine
import com.neopal.pet.ui.components.pixelUnits

/**
 * The in-app updater, made visible.
 *
 * The machinery behind this screen has been complete and unreachable: `UpdateService` can ask the
 * release page what is published, fetch it, checksum it, look inside it and hand it to the system
 * installer, and every way each of those can fail already carries a sentence written where the
 * failure happened. This screen's whole job is to *show* those sentences and offer the one or two
 * things worth pressing about each.
 *
 * Two rules it keeps:
 *
 * 1. **Nothing is swallowed.** Every state renders `UpdateStatus.summary` verbatim, whatever it
 *    is. There is no "something went wrong" anywhere in this file, and there is no branch that
 *    can drop a message on the floor: the summary is drawn before any of the state-specific
 *    trimmings, from one call site.
 * 2. **It decides nothing.** Which buttons exist, what colour the state reads as, and whose fault
 *    an inconclusive check is are all settled in `com.neopal.pet.domain.UpdatePlanner`, where
 *    they are tested. What is left here is layout.
 */
@Composable
fun UpdateScreen(viewModel: UpdateViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val plan = state.plan
    val status = state.status

    // Coming back from the system settings screen is the one moment the install permission can
    // change under this screen. The result itself carries nothing useful — Android reports no
    // answer for this screen — so the callback re-reads the permission from the package manager
    // rather than reading the result code.
    val permissionScreen = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshInstallPermission() }

    // One check on arrival, as an automatic one: throttled, and refused outright on a metered
    // connection. Opening a screen should never spend somebody's mobile data.
    LaunchedEffect(Unit) {
        viewModel.refreshInstallPermission()
        viewModel.checkOnOpen()
    }

    val accent = accentFor(plan.tone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.update_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(pixelUnits(2)))

        PixelPanel(
            modifier = Modifier
                .fillMaxWidth()
                // The sweep is the only "working" affordance on the screen: a check and a
                // download both have moments with nothing else moving, and a still screen after
                // a tap reads as a tap that did not land.
                .pixelShine(enabled = plan.busy, color = accent),
            accent = accent,
            // No title strip: the kit's strip is one uppercase line that ellipsises, and these
            // headlines are sentences — "Android needs your permission first" is exactly the one
            // that would be cut off, on exactly the phone where it matters most. The tone still
            // shows, in the panel's own bevelled edge.
            contentPadding = PaddingValues(pixelUnits(3)),
        ) {
            Text(
                plan.headline,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(pixelUnits(2)))
            // Always, for every state, and never conditional. The service wrote this sentence at
            // the point the outcome was decided and it is the one thing on this screen that is
            // never guessed.
            Text(
                status.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (plan.showsProgress) {
                Spacer(Modifier.height(pixelUnits(3)))
                // The bar carries no semantics of its own — the kit clears them — which is right
                // here: the summary above already reads "12.4 MB of 31.0 MB", and the meter is
                // that same fact drawn.
                PixelBar(
                    fraction = (status as? UpdateStatus.Downloading)?.fraction ?: 0f,
                    color = accent,
                )
            }

            plan.note?.let { note ->
                Spacer(Modifier.height(pixelUnits(3)))
                PixelDivider()
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.installNote?.let { note ->
                Spacer(Modifier.height(pixelUnits(3)))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (plan.actions.isNotEmpty()) {
                Spacer(Modifier.height(pixelUnits(3)))
                // Flow, not a row: "Open Android settings" beside "Discard" does not fit on one
                // line on a narrow phone, and the permission gate is exactly where a clipped
                // button would do the most harm.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                    verticalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    plan.actions.forEach { action ->
                        PixelButton(
                            // No branch on which action this is: the pure layer already decided
                            // that this button belongs on this screen, and the view model decides
                            // what it calls. The launcher is handed down because only a
                            // composable can own one.
                            onClick = { viewModel.perform(action) { permissionScreen.launch(it) } },
                            accent = if (action == plan.primary) accent else NeoAccents.cyan,
                        ) {
                            Text(
                                action.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        PixelPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = NeoAccents.cyan,
            title = stringResource(R.string.update_how_title),
            contentPadding = PaddingValues(pixelUnits(3)),
        ) {
            Text(
                stringResource(R.string.update_how_source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                stringResource(R.string.update_how_verified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                stringResource(R.string.update_how_cached),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(pixelUnits(6)))
    }
}

/**
 * The kit's colour for a tone.
 *
 * A lookup, not a judgement: which states are reassuring and which are alarming was settled in
 * the domain, and this only says what the console's palette calls each of those.
 */
@Composable
private fun accentFor(tone: UpdateTone): Color = when (tone) {
    UpdateTone.NEUTRAL -> NeoAccents.cyan
    UpdateTone.GOOD -> NeoAccents.green
    UpdateTone.WARN -> NeoAccents.gold
    UpdateTone.BAD -> MaterialTheme.colorScheme.error
}
