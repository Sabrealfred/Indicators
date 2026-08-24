@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neopal.pet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.neopal.pet.data.ModelDownloads
import com.neopal.pet.data.ModelFetchStatus
import com.neopal.pet.data.ModelStore
import com.neopal.pet.domain.FetchableModel
import com.neopal.pet.domain.ModelFetchAction
import com.neopal.pet.domain.ModelFetchPlanner
import com.neopal.pet.domain.ModelFetchRules
import com.neopal.pet.domain.ModelFetchTone
import com.neopal.pet.domain.ModelVerification
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.work.ModelDownloadWorker
import kotlinx.coroutines.launch

/**
 * The on-device brain's storage, in Settings: how big it is, and one press to be rid of it.
 *
 * Its own file, for the same reason [VoiceSettingsPanel] has one: the settings screen is the
 * busiest shared surface in the project, and this is the only section of it that talks to a
 * download that is running somewhere else. Everything the panel knows is here; the screen that
 * hosts it gains three lines.
 *
 * ## It decides nothing
 *
 * Which buttons exist, what the headline says, how loudly it reads, whether a licence has to be
 * shown first — all of that is [ModelFetchPlanner], which is pure and tested. This maps the
 * downloader's status onto a phase with one exhaustive `when` the compiler checks, draws whatever
 * comes back, and turns each button into exactly one call. There is no `if` here about what the
 * player should see, which is the rule this project holds every screen to.
 *
 * ## Why the progress survives leaving the screen
 *
 * It reads [ModelDownloads], which is process-wide, rather than holding a job of its own. Backing
 * out of Settings to look at the creature and coming back finds the same download at the same
 * place, because nothing about it was ever owned by this composition.
 */
@Composable
fun ModelStoragePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ModelDownloads.status.collectAsState()
    val store = remember(context) { ModelStore(context) }

    var storedBytes by remember { mutableStateOf(0L) }
    var termsAccepted by remember { mutableStateOf(false) }

    // Once, when the panel appears: what is actually on the disk. Never claims a download is
    // running -- see ModelDownloads.refresh.
    LaunchedEffect(Unit) {
        termsAccepted = store.termsAccepted()
        ModelDownloads.refresh(context)
    }
    // And again whenever the download's state changes, because a finished or deleted file is a
    // different number on the same line.
    LaunchedEffect(status) {
        storedBytes = ModelDownloads.storedBytes(context)
    }

    // Both of these are answered by the status itself. A `when` here would have to be kept
    // exhaustive by hand, and it is the one place in this project where that cannot be checked
    // locally: the status arrives through `collectAsState`, which has no androidx to resolve
    // against in the diagnostic harness, so its type is unknown there and exhaustiveness is
    // unprovable exactly where it would be verified. As members, the compiler enforces it.
    val model: FetchableModel? = status.model ?: ModelDownloads.offered()
    val onDisk = status.onDiskBytes

    val done = (status as? ModelFetchStatus.Running)?.doneBytes ?: 0L
    val plan = ModelFetchPlanner.plan(
        phase = ModelFetchPlanner.gate(status.phase, termsAccepted),
        sizeBytes = model?.sizeBytes ?: 0L,
        onDiskBytes = onDisk,
        doneBytes = done,
        fault = (status as? ModelFetchStatus.Unconfigured)?.fault,
        verification = (status as? ModelFetchStatus.Stored)?.verification ?: ModelVerification.SIZE_ONLY,
    )

    val accent = when (plan.tone) {
        ModelFetchTone.NEUTRAL -> NeoAccents.cyan
        ModelFetchTone.GOOD -> NeoAccents.green
        ModelFetchTone.WARN -> NeoAccents.gold
        ModelFetchTone.BAD -> MaterialTheme.colorScheme.error
    }

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        title = "The brain on this device",
        contentPadding = PaddingValues(pixelUnits(3)),
    ) {
        Text(
            plan.headline,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            status.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (plan.showsProgress && model != null) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelBar(
                fraction = ModelFetchRules.progressFraction(
                    if (done > 0L) done else onDisk,
                    model.sizeBytes,
                ),
                color = accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                ModelFetchPlanner.progressLine(if (done > 0L) done else onDisk, model.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        plan.note?.let { note ->
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (plan.actions.isNotEmpty()) {
            Spacer(Modifier.height(pixelUnits(2)))
            // Flow, not a row: three buttons with these labels do not fit on one line on a
            // narrow phone, and the same lesson is already written into the settings chips.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
                verticalArrangement = Arrangement.spacedBy(pixelUnits(1)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                plan.actions.forEach { action ->
                    PixelButton(
                        onClick = {
                            when (action) {
                                ModelFetchAction.DOWNLOAD ->
                                    model?.let { ModelDownloadWorker.enqueue(context, it, allowMetered = false) }
                                ModelFetchAction.RETRY ->
                                    model?.let { ModelDownloadWorker.enqueue(context, it, allowMetered = false) }
                                ModelFetchAction.DOWNLOAD_ON_METERED ->
                                    model?.let { ModelDownloadWorker.enqueue(context, it, allowMetered = true) }
                                ModelFetchAction.STOP -> {
                                    ModelDownloadWorker.cancel(context)
                                    scope.launch { ModelDownloads.refresh(context) }
                                }
                                ModelFetchAction.ACCEPT_TERMS -> {
                                    store.acceptTerms()
                                    termsAccepted = true
                                }
                                ModelFetchAction.OPEN_MODEL_PAGE -> model?.let { open(context, it.pageUrl) }
                                ModelFetchAction.DELETE -> {
                                    // One press, no confirmation dialog: it is a re-downloadable
                                    // file and nothing in the game depends on it. Stopping first
                                    // matters -- deleting a file out from under a writer is how
                                    // you get one that is neither deleted nor whole.
                                    ModelDownloadWorker.cancel(context)
                                    scope.launch {
                                        ModelDownloads.deleteEverything(context)
                                        termsAccepted = store.termsAccepted()
                                        storedBytes = ModelDownloads.storedBytes(context)
                                    }
                                }
                            }
                        },
                        accent = accent,
                        enabled = !plan.busy || action == ModelFetchAction.STOP,
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

        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            storageLine(storedBytes),
            style = MaterialTheme.typography.labelSmall,
            color = if (storedBytes > 0L) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one line that is true whatever else is on screen: how much of this phone this feature is
 * using. Shown even at zero, because "nothing" is the answer most players want confirmed.
 */
private fun storageLine(bytes: Long): String =
    if (bytes <= 0L) {
        "Using no storage on this device."
    } else {
        "Using ${ModelFetchRules.describeBytes(bytes)} of this device's storage."
    }

private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
