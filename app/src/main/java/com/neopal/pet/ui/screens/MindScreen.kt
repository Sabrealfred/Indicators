package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.Activity
import com.neopal.pet.domain.ActivityKind
import com.neopal.pet.domain.Autonomy
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Decision
import com.neopal.pet.domain.Learning
import com.neopal.pet.domain.PlanBoard
import com.neopal.pet.domain.PlanStepState
import com.neopal.pet.domain.PlanStepView
import com.neopal.pet.domain.Skill
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelDigits
import com.neopal.pet.ui.components.PixelDivider
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.dimmedFor
import com.neopal.pet.ui.components.pixelShine
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberWindowSize

/**
 * One rung of the ladder, as this screen needs to draw it.
 *
 * Built once at the top of the screen rather than asked per row: [Skill.ladder] sorts the enum on
 * every read, and "why is this out of reach" is a question about the whole pet, not about the row.
 */
private data class SkillRung(
    val skill: Skill,
    val known: Boolean,
    val studying: Boolean,
    /** Non-null for a rung the pet cannot start on. Also non-null for one it already knows. */
    val blocker: String?,
)

/**
 * The creature's mind: how much of its own day it runs, what it has been deciding and why, and
 * what it is still learning.
 *
 * The decision log is the reason this screen exists. The autonomy switch, the study bar and the
 * ladder are all levers; the log is the only place in the game where the creature explains itself,
 * and an autonomous pet that merely acts is indistinguishable from a random one. So every line
 * carries the reason in the pet's own words and a bar for how badly it wanted it — and the panel
 * beside it shows the options that lost, including the ones it wanted and could not have. That
 * last case is the whole point of the skills half: a pet that is starving and never learned to
 * work the pantry has to be visibly starving-and-unable, or the player has no idea to go and
 * teach it.
 */
@Composable
fun MindScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val window = rememberWindowSize()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The app draws edge to edge; without this the back button sits under the status bar.
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.mind_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(
                        R.string.mind_subtitle,
                        pet.name,
                        pet.autonomy.displayName,
                        pet.intellect.toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // A baby has no decisions to explain and no skills to reach for. Showing it the full
            // screen with every number at zero would read as a broken pet rather than a young one.
            if (!pet.isMindAwake) {
                DormantPanel(
                    name = pet.name,
                    stage = pet.stage.displayName,
                    dead = pet.isDead,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(pixelUnits(4)))
                return@Column
            }

            val ladder = remember { Skill.ladder }
            val rungs = ladder.map { skill ->
                SkillRung(
                    skill = skill,
                    known = skill in pet.skills,
                    studying = pet.studying == skill,
                    blocker = viewModel.skillBlocker(skill),
                )
            }
            val considerations = viewModel.considerations()
            val activityTarget = pet.activity?.targetId?.let { id ->
                pet.pals.firstOrNull { it.id == id }?.name
            }
            // Null for every save that never set up a remote mind, which is most of them; the
            // panel simply is not there. See [PlanBoard.of].
            val planBoard = PlanBoard.of(pet.plan, pet.ageSeconds)

            // Two columns once there is room for them. One tall ribbon of panels down the middle
            // of a tablet is a phone layout nobody went back to.
            if (window.isTwoPane) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                    ) {
                        AutonomyPanel(pet.autonomy, viewModel::setAutonomy, Modifier.fillMaxWidth())
                        NowPanel(pet.name, pet.autonomy, pet.activity, pet.ageSeconds, activityTarget, Modifier.fillMaxWidth())
                        // Between "what it is doing" and "what it decided", because that is where
                        // an intention sits: it is the thread the single decisions were beads on.
                        planBoard?.let { PlanPanel(it, pet.name, Modifier.fillMaxWidth()) }
                        DecisionLogPanel(pet.decisions, pet.ageSeconds, pet.autonomy, Modifier.fillMaxWidth())
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                    ) {
                        WeighingPanel(considerations, pet.autonomy, Modifier.fillMaxWidth())
                        LearningPanel(
                            name = pet.name,
                            intellect = pet.intellect,
                            studying = pet.studying ?: Learning.nextSkill(pet),
                            studyFraction = viewModel.studyFraction(),
                            studySessions = pet.studySessions,
                            selfCareActions = pet.selfCareActions,
                            teachBlocker = Learning.teachBlockedReason(pet),
                            onTeach = viewModel::teach,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SkillsPanel(rungs, Modifier.fillMaxWidth())
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                ) {
                    AutonomyPanel(pet.autonomy, viewModel::setAutonomy, Modifier.fillMaxWidth())
                    NowPanel(pet.name, pet.autonomy, pet.activity, pet.ageSeconds, activityTarget, Modifier.fillMaxWidth())
                    planBoard?.let { PlanPanel(it, pet.name, Modifier.fillMaxWidth()) }
                    DecisionLogPanel(pet.decisions, pet.ageSeconds, pet.autonomy, Modifier.fillMaxWidth())
                    WeighingPanel(considerations, pet.autonomy, Modifier.fillMaxWidth())
                    LearningPanel(
                        name = pet.name,
                        intellect = pet.intellect,
                        studying = pet.studying ?: Learning.nextSkill(pet),
                        studyFraction = viewModel.studyFraction(),
                        studySessions = pet.studySessions,
                        selfCareActions = pet.selfCareActions,
                        teachBlocker = Learning.teachBlockedReason(pet),
                        onTeach = viewModel::teach,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SkillsPanel(rungs, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(pixelUnits(4)))
        }
    }
}

/**
 * The honest empty state.
 *
 * A screen of zeroes would say the creature has a mind and that it is worth nothing. It has no
 * mind yet, which is a different sentence, and the only useful thing to add is when that changes.
 */
@Composable
private fun DormantPanel(name: String, stage: String, dead: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.onSurfaceVariant
    val headline = stringResource(if (dead) R.string.mind_dormant_dead else R.string.mind_dormant_young)
    val body = if (dead) {
        stringResource(R.string.mind_dormant_dead_body, name)
    } else {
        stringResource(R.string.mind_dormant_young_body, name, stage.lowercase())
    }
    val dormantReadOut = stringResource(R.string.cd_headline_body, headline, body)

    PixelPanel(
        modifier = modifier,
        accent = accent,
        title = stringResource(R.string.mind_panel_title),
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = dormantReadOut },
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(pixelUnits(8)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Handing over the day, or taking it back.
 *
 * All three descriptions are on screen at once rather than only the live one's, because this is
 * the single control in the game that changes who is playing it. A player should be able to read
 * what they are giving away before they give it away, not discover it afterwards from a toast.
 */
@Composable
private fun AutonomyPanel(
    current: Autonomy,
    onChoose: (Autonomy) -> Unit,
    modifier: Modifier = Modifier,
) {
    PixelPanel(
        modifier = modifier,
        accent = NeoAccents.cyan,
        title = stringResource(R.string.mind_who_decides),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = current.displayName.uppercase(),
                color = if (current == Autonomy.OFF) MaterialTheme.colorScheme.surface else NeoAccents.cyan,
                contentDescription = "",
            )
        },
    ) {
        Autonomy.entries.forEachIndexed { index, option ->
            if (index > 0) Spacer(Modifier.height(pixelUnits(2)))
            AutonomyOption(
                option = option,
                selected = option == current,
                onChoose = { onChoose(option) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One of the three settings, with its description attached.
 *
 * [com.neopal.pet.ui.components.PixelChip] is the kit's one-of-many control and this borrows its
 * exact idiom — sunk in while it is the live one, announced as a radio button — but a chip is a
 * label and nothing else, and here the sentence under the label is most of what is being chosen.
 */
@Composable
private fun AutonomyOption(
    option: Autonomy,
    selected: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    val accent = if (selected) NeoAccents.cyan else MaterialTheme.colorScheme.onSurfaceVariant

    val optionReadOut = stringResource(R.string.cd_headline_body, option.displayName, option.description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .pixelSurface(
                fill = if (selected) lerp(panel, NeoAccents.cyan, 0.22f) else panel,
                accent = accent,
                bevel = if (selected) PixelBevel.PRESSED else PixelBevel.RAISED,
                borderUnits = 1,
                background = background,
            )
            // Role.RadioButton rather than Button: "selected" is the state that has to be spoken.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onChoose)
            .padding(pixelUnits(2))
            .semantics(mergeDescendants = true) { contentDescription = optionReadOut },
    ) {
        // A filled block against an empty well, so which one is live survives a colour-blind eye
        // and a screenshot in greyscale.
        Box(
            modifier = Modifier
                .size(pixelUnits(6))
                .pixelSurface(
                    fill = if (selected) accent else lerp(panel, background, 0.35f),
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                    borderUnits = 1,
                    background = background,
                ),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What it is doing this minute.
 *
 * Announced as a live region because it is the one line on the screen that changes on its own —
 * but the announcement is built from the activity alone, with no elapsed time in it, so a screen
 * reader speaks up when the creature changes its mind rather than once a minute forever.
 */
@Composable
private fun NowPanel(
    name: String,
    autonomy: Autonomy,
    activity: Activity?,
    ageSeconds: Long,
    targetName: String?,
    modifier: Modifier = Modifier,
) {
    val running = autonomy != Autonomy.OFF
    val accent = if (running && activity != null) NeoAccents.green else MaterialTheme.colorScheme.onSurfaceVariant

    val headline = when {
        !running -> stringResource(R.string.mind_now_waiting, name)
        activity == null -> stringResource(R.string.mind_now_between, name)
        targetName != null -> stringResource(R.string.mind_now_doing_with, name, activity.kind.displayName, targetName)
        else -> stringResource(R.string.mind_now_doing, name, activity.kind.displayName)
    }
    val detail = when {
        !running -> stringResource(R.string.mind_now_waiting_body)
        activity == null -> stringResource(R.string.mind_now_between_body)
        else -> stringResource(
            R.string.mind_now_timing,
            ago(ageSeconds - activity.startedAtSeconds),
            span(activity.endsAtSeconds - ageSeconds),
        )
    }

    PixelPanel(
        modifier = modifier,
        accent = accent,
        title = stringResource(R.string.mind_right_now),
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = headline
                },
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(pixelUnits(8)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The plan, whole.
 *
 * This is the panel the feature never had. The decision log answers "why did it just do that" one
 * line at a time, which is a genuinely different question: three log entries read as three
 * unrelated reactions even when they were one intention, and "eat, then tidy up, then go and find
 * Moss, because I want to be presentable before company" only means anything as a whole.
 *
 * It is drawn only when there *is* a plan. Plans need a remote mind, and a panel reading "no plan
 * yet" on every save that never pasted an API key would not be an honest empty state — it would
 * be an advertisement on the one screen that is supposed to be about the creature.
 */
@Composable
private fun PlanPanel(board: PlanBoard, name: String, modifier: Modifier = Modifier) {
    // Green while it is live, for the same reason "Right now" is green: this is the creature
    // getting on with something. A plan past its clock goes quiet rather than red — running out
    // of time is not a fault, it is a creature that aimed slightly beyond its afternoon.
    val accent = if (board.isOutOfTime) MaterialTheme.colorScheme.onSurfaceVariant else NeoAccents.green
    val clock = if (board.isOutOfTime) {
        stringResource(R.string.mind_plan_out_of_time)
    } else {
        stringResource(R.string.mind_plan_time_left, span(board.secondsLeft))
    }
    val grown = if (board.extensions == 0) {
        null
    } else {
        pluralStringResource(R.plurals.mind_plan_extended, board.extensions, board.extensions)
    }
    val readOut = stringResource(
        R.string.cd_mind_plan,
        name,
        board.goal,
        (board.done + 1).coerceAtMost(board.total),
        board.total,
        clock,
    )

    PixelPanel(
        modifier = modifier,
        accent = accent,
        title = stringResource(R.string.mind_plan_title),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = stringResource(R.string.fraction, board.done, board.total),
                color = MaterialTheme.colorScheme.surface,
                contentDescription = stringResource(R.string.cd_mind_steps_done, board.done, board.total),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = readOut },
        ) {
            // The goal in the creature's own words, given room to wrap for the same reason a
            // decision's reason is: a sentence cut to one line is a sentence the player guesses at.
            Text(
                text = board.goal,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelBar(
                    fraction = board.fraction,
                    color = accent,
                    segments = board.total.coerceAtLeast(1),
                    height = pixelUnits(4),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                PixelDigits(
                    text = stringResource(R.string.fraction, board.done, board.total),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(pixelUnits(2)))
            board.steps.forEachIndexed { index, view ->
                if (index > 0) Spacer(Modifier.height(pixelUnits(1)))
                PlanStepRow(view)
            }
            Spacer(Modifier.height(pixelUnits(2)))
            PixelDivider()
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                text = clock,
                style = MaterialTheme.typography.labelSmall,
                color = if (board.isOutOfTime) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (grown != null) {
                Text(
                    text = grown,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Said once, here, because a plan is the one thing on this screen a player could
            // mistake for a promise. Every step is re-checked when it comes up.
            Text(
                text = stringResource(R.string.mind_plan_caveat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One step: a tick for done, the pointer for the one it is on, and its reason underneath. */
@Composable
private fun PlanStepRow(view: PlanStepView, modifier: Modifier = Modifier) {
    val current = view.state == PlanStepState.CURRENT
    val done = view.state == PlanStepState.DONE
    val tint = when {
        current -> NeoAccents.green
        done -> NeoAccents.cyan
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val marker = when {
        done -> Icons.Filled.Check
        current -> Icons.Filled.RadioButtonChecked
        else -> Icons.Filled.RadioButtonUnchecked
    }
    Row(modifier = modifier.fillMaxWidth()) {
        Icon(
            imageVector = marker,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(pixelUnits(5)),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titled(view.step.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (view.step.why.isNotBlank()) {
                Text(
                    text = view.step.why,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The log. Newest first, because the question a player arrives with is "why did it just do that".
 *
 * The domain keeps the list oldest-first and capped, so the whole of it fits on screen; there is
 * no paging and no "show more", and there should not be. A log you have to ask for twice is a log
 * nobody reads.
 */
@Composable
private fun DecisionLogPanel(
    decisions: List<Decision>,
    ageSeconds: Long,
    autonomy: Autonomy,
    modifier: Modifier = Modifier,
) {
    PixelPanel(
        modifier = modifier,
        accent = NeoAccents.cyan,
        title = stringResource(R.string.mind_decided_title),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = "${decisions.size}",
                color = MaterialTheme.colorScheme.surface,
                contentDescription = stringResource(R.string.cd_mind_decisions, decisions.size),
            )
        },
    ) {
        if (decisions.isEmpty()) {
            Text(
                text = stringResource(
                    if (autonomy == Autonomy.OFF) R.string.mind_decided_none_off else R.string.mind_decided_none_yet,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            return@PixelPanel
        }

        decisions.asReversed().forEachIndexed { index, decision ->
            if (index > 0) {
                Spacer(Modifier.height(pixelUnits(2)))
                PixelDivider()
                Spacer(Modifier.height(pixelUnits(2)))
            }
            DecisionRow(decision = decision, ageSeconds = ageSeconds, latest = index == 0)
        }
    }
}

/** One line of the log: what it chose, why, and how sure it was. */
@Composable
private fun DecisionRow(
    decision: Decision,
    ageSeconds: Long,
    latest: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (latest) NeoAccents.cyan else MaterialTheme.colorScheme.onSurfaceVariant
    val elapsed = ago(ageSeconds - decision.atSeconds)
    val runnerUp = decision.runnerUp?.let { stringResource(R.string.mind_runner_up, it.displayName) }
    // How it turned out, once it has. Null while the activity is still running, and null forever
    // for one the player cut short — either way there is nothing yet for the creature to say.
    val outcome = decision.outcome
    val readOut = stringResource(
        R.string.cd_mind_decision,
        titled(decision.kind),
        elapsed,
        decision.reason,
        outcome?.let { "$it " } ?: "",
        percent(decision.utility),
        runnerUp?.let { " $it" } ?: "",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titled(decision.kind),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = elapsed,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // The sentence the creature would say. It gets room to wrap: a reason cut to one line is
        // a reason the player has to guess at, which is the opposite of the point.
        Text(
            text = decision.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        // The other half of the same sentence: what came of it, in the same voice.
        if (outcome != null) {
            Text(
                text = outcome,
                style = MaterialTheme.typography.bodySmall,
                color = NeoAccents.green,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (runnerUp != null) {
            Text(
                text = runnerUp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(pixelUnits(1)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelBar(
                fraction = decision.utility,
                color = accent,
                segments = 10,
                height = pixelUnits(4),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            PixelDigits(
                text = percent(decision.utility),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Everything the brain is holding up against everything else, losers included.
 *
 * The blocked options are the reason this panel exists. Dropping them would leave the screen
 * saying the pet simply did not fancy eating, which is a lie about the one mechanic skills are
 * there to create — and it is the lie that stops the player from ever going and teaching it.
 */
@Composable
private fun WeighingPanel(
    considerations: List<Consideration>,
    autonomy: Autonomy,
    modifier: Modifier = Modifier,
) {
    // Sorted by utility already, so the first entry is the thing it wants most. If that one is
    // blocked, it is the single most useful sentence on the screen and gets said loudly.
    val thwarted = considerations.firstOrNull()?.takeIf { !it.available && autonomy != Autonomy.OFF }
    val blocked = considerations.count { !it.available }

    PixelPanel(
        modifier = modifier,
        accent = NeoAccents.gold,
        title = stringResource(R.string.mind_weighing_title),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = "$blocked",
                color = if (blocked > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
                contentDescription = stringResource(R.string.cd_mind_blocked, blocked, considerations.size),
            )
        },
    ) {
        if (autonomy == Autonomy.OFF) {
            Text(
                text = stringResource(R.string.mind_weighing_off),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(pixelUnits(2)))
        }

        thwarted?.let { want ->
            ThwartedBanner(want)
            Spacer(Modifier.height(pixelUnits(2)))
        }

        if (considerations.isEmpty()) {
            Text(
                text = stringResource(R.string.mind_weighing_empty),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            return@PixelPanel
        }

        considerations.forEachIndexed { index, consideration ->
            if (index > 0) {
                Spacer(Modifier.height(pixelUnits(2)))
                PixelDivider()
                Spacer(Modifier.height(pixelUnits(2)))
            }
            ConsiderationRow(consideration)
        }
    }
}

/**
 * The creature wanting something it cannot have, said out loud.
 *
 * A live region, because it appears without the player touching anything and it is the cue to go
 * and teach — the one thing this screen can say that changes what the player does next.
 */
@Composable
private fun ThwartedBanner(want: Consideration, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_mind_thwarted, titled(want.kind), want.blockedBy.orEmpty())
    PixelPanel(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = readOut
            },
        accent = MaterialTheme.colorScheme.error,
        bevel = PixelBevel.FLAT,
        borderUnits = 1,
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(pixelUnits(6)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mind_thwarted_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.mind_thwarted_line, titled(want.kind), want.blockedBy.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One option on the scales: how badly it wants it, what it would say, and what is in the way. */
@Composable
private fun ConsiderationRow(consideration: Consideration, modifier: Modifier = Modifier) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (consideration.available) NeoAccents.gold else MaterialTheme.colorScheme.error
    val ink = if (consideration.available) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val verdict = consideration.blockedBy?.let { stringResource(R.string.mind_cannot_sentence, it) }
        ?: stringResource(R.string.mind_could_do_this)
    val readOut = stringResource(
        R.string.cd_mind_consideration,
        titled(consideration.kind),
        percent(consideration.utility),
        consideration.reason,
        verdict,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        // A padlock or an open slot: available and blocked differ in shape before they differ in
        // colour, the same way a finished mission does.
        Box(
            modifier = Modifier
                .size(pixelUnits(8))
                .pixelSurface(
                    fill = lerp(panel, accent, if (consideration.available) 0.20f else 0.10f),
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                    borderUnits = 1,
                    background = panel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!consideration.available) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(pixelUnits(4)),
                )
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = titled(consideration.kind),
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                PixelDigits(
                    text = percent(consideration.utility),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(pixelUnits(1)))
            PixelBar(
                fraction = consideration.utility,
                color = if (consideration.available) accent else dimmedFor(accent, panel, 0.45f),
                segments = 10,
                height = pixelUnits(4),
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Text(
                text = consideration.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            consideration.blockedBy?.let { reason ->
                Text(
                    text = stringResource(R.string.mind_cannot, reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Getting cleverer: the ceiling, the session, and the one button that speeds either up.
 *
 * Intellect and the study bar are two different clocks and are drawn as two different things.
 * Intellect is the slow one that decides what is reachable at all; the session is the short one
 * that finishes with something new on the ladder below. Sharing a bar between them was never an
 * option — a player would read a full bar as "done" and it would mean neither.
 */
@Composable
private fun LearningPanel(
    name: String,
    intellect: Float,
    studying: Skill?,
    studyFraction: Float,
    studySessions: Int,
    selfCareActions: Int,
    teachBlocker: String?,
    onTeach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = NeoAccents.gold
    val ceiling = Learning.MAX_INTELLECT
    val intellectReadOut = stringResource(R.string.cd_mind_intellect, intellect.toInt(), ceiling.toInt())

    PixelPanel(
        modifier = modifier,
        accent = accent,
        title = stringResource(R.string.mind_learning_title),
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = intellectReadOut },
        ) {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(pixelUnits(7)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            PixelDigits(text = "${intellect.toInt()}", color = accent, scale = 2)
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mind_intellect),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.mind_intellect_ceiling, ceiling.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))
        PixelBar(
            fraction = intellect / ceiling,
            color = accent,
            height = pixelUnits(5),
        )

        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        if (studying == null) {
            Text(
                text = stringResource(R.string.mind_nothing_in_reach, name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Not a live region: this creeps up every tick, and a screen reader that says so every
            // tick is one the player turns off.
            val studyReadOut = stringResource(R.string.cd_mind_studying, studying.displayName, percent(studyFraction))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = studyReadOut },
            ) {
                Text(
                    text = stringResource(R.string.mind_studying, studying.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = studying.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelBar(
                        fraction = studyFraction,
                        color = NeoAccents.green,
                        segments = 10,
                        height = pixelUnits(4),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(pixelUnits(2)))
                    PixelDigits(
                        text = percent(studyFraction),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(pixelUnits(3)))
        TeachButton(target = studying, blocker = teachBlocker, onTeach = onTeach)

        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            text = stringResource(
                R.string.mind_tally,
                pluralStringResource(R.plurals.mind_sittings, studySessions, studySessions),
                pluralStringResource(R.plurals.mind_things, selfCareActions, selfCareActions),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One lesson.
 *
 * It says what it will be teaching, and when it will not it says why instead of going quiet — a
 * refusal a player cannot read is a bug report. The refusals are all things one tap can fix:
 * hungry, tired, asleep.
 */
@Composable
private fun TeachButton(
    target: Skill?,
    blocker: String?,
    onTeach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = blocker == null
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (armed) NeoAccents.green else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (armed) R.string.mind_teach else R.string.mind_teach_blocked)
    val detail = when {
        !armed -> blocker.orEmpty()
        target != null -> stringResource(R.string.mind_teach_target, target.displayName.lowercase())
        else -> stringResource(R.string.mind_teach_no_target)
    }
    val readOut = if (armed) {
        stringResource(R.string.cd_mind_teach, detail)
    } else {
        stringResource(R.string.cd_mind_teach_blocked, detail)
    }

    PixelButton(
        onClick = onTeach,
        modifier = modifier
            .fillMaxWidth()
            // Last in the chain, so the glint reads as light falling on the button face.
            .pixelShine(enabled = armed, color = NeoAccents.green, alpha = 0.26f)
            .semantics(mergeDescendants = true) { contentDescription = readOut },
        enabled = armed,
        accent = accent,
        fill = if (armed) lerp(panel, NeoAccents.green, 0.22f) else panel,
        contentPadding = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(pixelUnits(6)),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The ladder: known, in reach, and still out of it.
 *
 * The out-of-reach rungs stay on screen rather than being hidden until they unlock. Half the value
 * of the list is being able to see what the creature could one day become, and the other half is
 * the number attached to it — a locked rung that says which intellect it wants is a plan, and one
 * that just says "locked" is a wall.
 */
@Composable
private fun SkillsPanel(rungs: List<SkillRung>, modifier: Modifier = Modifier) {
    val known = rungs.count { it.known }

    PixelPanel(
        modifier = modifier,
        accent = NeoAccents.green,
        title = stringResource(R.string.mind_ladder_title),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = stringResource(R.string.fraction, known, rungs.size),
                color = if (known == rungs.size) NeoAccents.green else MaterialTheme.colorScheme.surface,
                contentDescription = stringResource(R.string.cd_mind_skills, known, rungs.size),
            )
        },
    ) {
        rungs.forEachIndexed { index, rung ->
            if (index > 0) {
                Spacer(Modifier.height(pixelUnits(2)))
                PixelDivider()
                Spacer(Modifier.height(pixelUnits(2)))
            }
            SkillRow(rung)
        }
    }
}

/** One rung. The tick, the empty slot and the padlock are three different shapes on purpose. */
@Composable
private fun SkillRow(rung: SkillRung, modifier: Modifier = Modifier) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    // A rung the pet already knows is never "blocked", whatever the reason string says.
    val reachable = rung.known || rung.blocker == null
    val accent = when {
        rung.known -> NeoAccents.green
        rung.studying -> NeoAccents.gold
        reachable -> NeoAccents.cyan
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val state = stringResource(
        when {
            rung.known -> R.string.mind_rung_known
            rung.studying -> R.string.mind_rung_studying
            reachable -> R.string.mind_rung_reachable
            else -> R.string.mind_rung_out_of_reach
        },
    )
    val readOut = state + stringResource(
        R.string.cd_mind_rung,
        rung.skill.displayName,
        rung.skill.description,
        rung.skill.intellectRequired.toInt(),
        if (!rung.known && rung.blocker != null) " ${rung.blocker}" else "",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Box(
            modifier = Modifier
                .size(pixelUnits(9))
                .pixelSurface(
                    fill = lerp(panel, accent, if (rung.known) 0.24f else 0.08f),
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                    borderUnits = 1,
                    background = panel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val mark = when {
                rung.known -> Icons.Filled.Check
                rung.studying -> Icons.Filled.Lightbulb
                reachable -> null
                else -> Icons.Filled.Lock
            }
            mark?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(pixelUnits(5)),
                )
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rung.skill.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (reachable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rung.skill.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only the honest blockers. "Already knows this" under a ticked row is noise.
            if (!rung.known && rung.blocker != null) {
                Text(
                    text = rung.blocker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))

        Column(horizontalAlignment = Alignment.End) {
            if (rung.known) {
                Text(
                    text = stringResource(R.string.mind_known),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            } else {
                PixelBadge(
                    text = "${rung.skill.intellectRequired.toInt()}",
                    color = if (reachable) accent else dimmedFor(accent, panel, 0.45f),
                    background = panel,
                    contentDescription = "",
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    text = stringResource(R.string.mind_int),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "eating" is what the pet is doing; "Eating" is what the column is called. */
private fun titled(kind: ActivityKind): String =
    kind.displayName.replaceFirstChar { it.uppercaseChar() }

@Composable
private fun percent(value: Float): String =
    stringResource(R.string.percent_value, (value.coerceIn(0f, 1f) * 100f).toInt())

/**
 * Coarse on purpose. A decision made four minutes ago is information; one made four minutes and
 * thirteen seconds ago is a stopwatch, and it would rewrite itself on every tick of the clock.
 */
@Composable
private fun ago(seconds: Long): String {
    val past = seconds.coerceAtLeast(0L)
    val hours = past / 3600L
    val minutes = (past % 3600L) / 60L
    return when {
        hours > 0L -> stringResource(R.string.since_hours_minutes, hours, minutes)
        minutes > 0L -> stringResource(R.string.since_minutes, minutes)
        else -> stringResource(R.string.since_just_now)
    }
}

/** The same clock, forwards. Used for how much of the current activity is left to run. */
@Composable
private fun span(seconds: Long): String {
    val left = seconds.coerceAtLeast(0L)
    val hours = left / 3600L
    val minutes = (left % 3600L) / 60L
    return when {
        hours > 0L -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0L -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(R.string.duration_a_moment)
    }
}
