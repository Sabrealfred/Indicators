package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.neopal.pet.R
import com.neopal.pet.domain.Autonomy
import com.neopal.pet.domain.ChildPreview
import com.neopal.pet.domain.Colony
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.FamilyTree
import com.neopal.pet.domain.Genome
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Morphology
import com.neopal.pet.domain.NestEgg
import com.neopal.pet.domain.Pal
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Relation
import com.neopal.pet.domain.Skill
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
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
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.pixelWellFill
import com.neopal.pet.ui.components.rememberWindowSize
import kotlin.math.roundToInt

/**
 * The pose every drawing on this screen uses.
 *
 * A single shared instance because it is genuinely shared — nothing here is animated, and a
 * screen that can hold a dozen creatures at once should not allocate a dozen identical frames
 * on every recomposition to say so.
 */
private val ColonyPose = CreatureFrame(mouthOpen = 0.22f)

/** A face in the list: big enough to read the ears and the stance off, small enough to stack. */
private val RowPortrait: Dp = pixelUnits(15)

/** A parent on the breeding panel. Deliberately smaller than the child it is being read into. */
private val ParentPortrait: Dp = pixelUnits(13)

/** The child preview. The one drawing on this screen the player is meant to study. */
private val ChildPortrait: Dp = pixelUnits(26)

/** An egg in the nest. */
private val EggPortrait: Dp = pixelUnits(12)

/**
 * The colony: who the pet knows, who it is related to, and what a pairing would make.
 *
 * Three questions share this screen because they are the same question asked at three distances.
 * Who is in the room decides who can become a friend; a friend decides who can become a mate; a
 * mate decides what the next generation looks like. Splitting them across three screens would
 * hide the only chain of cause and effect in the game that runs longer than one pet's life.
 *
 * The empty state is the one most players see, and it is treated as the main state rather than
 * as a fallback. A new pet knows nobody, nothing the player can press will summon anyone, and a
 * blank list would read as a broken feature instead of as a world that has not happened yet.
 */
@Composable
fun ColonyScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val config = ui.config
    val pals = viewModel.pals()
    val window = rememberWindowSize()

    // Derived, not stored. Keyed on the parts these actually read, because the foreground clock
    // hands us a fresh PetState every second and neither the tree nor a preview depends on time.
    val tree = remember(pet.name, pet.parentNames, pet.pals, pet.nest) { Colony.familyTree(pet) }

    // Who the breeding panel is talking about. Held here rather than derived, so that the
    // once-a-second tick underneath cannot quietly move the preview onto somebody else. Plain
    // remember is enough: the activity declares orientation in its own configChanges, so a
    // rotation never rebuilds this screen in the first place.
    var chosenId by remember { mutableStateOf<String?>(null) }
    val chosen = pals.firstOrNull { it.id == chosenId }
        ?: pals.firstOrNull { it.canCourt && it.present }
        ?: pals.firstOrNull { it.canCourt }
        ?: pals.firstOrNull()

    val here = pals.count { it.present }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The app draws edge to edge; without this the back button sits under the status bar,
            // and in landscape it sits under the cutout.
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
                    "COLONY",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (pals.isEmpty()) {
                        stringResource(R.string.colony_nobody_met, pet.name)
                    } else {
                        stringResource(
                            R.string.colony_known_summary,
                            creatureCount(pals.size),
                            if (here == 0) {
                                stringResource(R.string.colony_none_here)
                            } else {
                                stringResource(R.string.colony_count_here, here)
                            },
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (pals.isEmpty()) {
                NobodyYetPanel(pet)
            } else {
                PalsPanel(
                    pet = pet,
                    pals = pals,
                    here = here,
                    chosenId = chosen?.id,
                    onChoose = { chosenId = it },
                )
            }

            Spacer(Modifier.height(pixelUnits(3)))
            FamilyPanel(pet = pet, tree = tree)

            Spacer(Modifier.height(pixelUnits(3)))
            NestPanel(pet = pet, incubation = Colony.incubationSeconds(config))

            if (chosen != null) {
                Spacer(Modifier.height(pixelUnits(3)))
                BreedingPanel(
                    pet = pet,
                    partner = chosen,
                    blocker = viewModel.pairingBlocker(chosen.id),
                    stacked = window.isCompactWidth,
                    onPair = { viewModel.pair(chosen.id) },
                )
            }
            Spacer(Modifier.height(pixelUnits(5)))
        }
    }
}

// ---------------------------------------------------------------- who is here

/** Everyone the pet knows, family first, each at the shape it actually inherited. */
@Composable
private fun PalsPanel(
    pet: PetState,
    pals: List<Pal>,
    here: Int,
    chosenId: String?,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = NeoAccents.cyan,
        title = stringResource(R.string.colony_who_knows, pet.name),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = "$here",
                color = if (here > 0) NeoAccents.green else MaterialTheme.colorScheme.surface,
                contentDescription = if (here > 0) {
                    stringResource(R.string.cd_colony_in_room, here)
                } else {
                    stringResource(R.string.cd_colony_room_empty)
                },
            )
        },
    ) {
        pals.forEachIndexed { index, pal ->
            if (index > 0) {
                Spacer(Modifier.height(pixelUnits(2)))
                PixelDivider()
                Spacer(Modifier.height(pixelUnits(2)))
            }
            PalRow(
                pal = pal,
                chosen = pal.id == chosenId,
                onChoose = { onChoose(pal.id) },
            )
        }
        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            text = stringResource(R.string.colony_tap_a_name, Colony.MAX_REMEMBERED_PALS),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The second-commonest dead end after an empty list: a room full of visitors nobody ever
        // becomes fond of. Time in the room does it by itself, slowly; going over to somebody is
        // what makes it quick, and that is the pet's own move — it either has not been taught it
        // or is not allowed to make it. Said here rather than left for the player to infer from
        // a meter that crawls.
        val knowsHow = Skill.SOCIALISE in pet.skills
        val allowed = pet.autonomy == Autonomy.FULL
        if (pals.none { it.isFriend } && (!knowsHow || !allowed)) {
            Spacer(Modifier.height(pixelUnits(2)))
            if (!knowsHow) {
                Condition(
                    met = false,
                    text = stringResource(
                        R.string.colony_needs_socialise,
                        pet.name,
                        Skill.SOCIALISE.displayName.lowercase(),
                    ),
                )
            }
            if (!allowed) {
                Condition(
                    met = false,
                    text = stringResource(
                        R.string.colony_needs_autonomy,
                        pet.name,
                        pet.autonomy.displayName,
                        Autonomy.FULL.displayName,
                    ),
                )
            }
        }
    }
}

/**
 * One companion: the silhouette its genes actually produced, where it stands, and how close it
 * is to the next rung.
 *
 * The whole row is one accessibility node, the way a mission row is. The drawing carries the
 * same facts as the sentence beside it, and reading the portrait, the badges and the meter as
 * four separate stops turns one creature into four swipes that say nothing new.
 */
@Composable
private fun PalRow(
    pal: Pal,
    chosen: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = accentFor(pal.relation)
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val mark = nextMarkFor(pal)
    val affinity = pal.affinity.roundToInt()

    val readOut = stringResource(
        R.string.cd_colony_pal,
        pal.name,
        pal.relation.displayName.lowercase(),
        describe(pal.species, pal.stage, pal.genome),
        stringResource(if (pal.present) R.string.cd_colony_present else R.string.cd_colony_absent),
        affinity,
        markSentence(pal, mark),
    ) + if (chosen) stringResource(R.string.cd_colony_chosen) else ""

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MinTouchTarget)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onChoose)
            // A chosen row is pressed into the panel rather than merely tinted: the shape says it
            // before the colour does, which is the only version that survives a colour-blind eye.
            // The padding is paid whether or not the surface is drawn, so choosing a row does not
            // shove every row under it down by two units.
            .then(
                if (chosen) {
                    Modifier.pixelSurface(
                        fill = lerp(panel, accent, 0.16f),
                        accent = accent,
                        bevel = PixelBevel.PRESSED,
                        borderUnits = 1,
                        background = panel,
                    )
                } else {
                    Modifier
                },
            )
            .padding(pixelUnits(2))
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Portrait(
            species = pal.species,
            stage = pal.stage,
            genome = pal.genome,
            portraitSize = RowPortrait,
            accent = accent,
            // Folded into the row sentence above; a second reading of the same creature here
            // would only make TalkBack say it twice.
            description = null,
        )
        Spacer(Modifier.width(pixelUnits(2)))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pal.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                PixelBadge(
                    text = pal.relation.displayName,
                    color = if (pal.relation == Relation.VISITOR) panel else accent,
                    background = panel,
                    contentDescription = "",
                )
            }
            Text(
                text = stringResource(
                    R.string.colony_pal_line,
                    pal.stage.displayName,
                    pal.species.displayName,
                    stringResource(if (pal.present) R.string.colony_here_now else R.string.colony_away),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (pal.present) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(pixelUnits(1)))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The marker is the *next* rung, not both of them: one line the player is walking
                // towards is legible on a 4dp meter, and two are a pair of ticks nobody can tell
                // apart. Which rung it is, is said in the caption underneath.
                PixelBar(
                    fraction = pal.affinity / 100f,
                    color = accent,
                    segments = 10,
                    height = pixelUnits(4),
                    trackColor = panel,
                    markerFraction = mark?.let { it / 100f },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                PixelDigits(text = "$affinity", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = markCaption(pal, mark),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- the empty room

/**
 * What a visitor is, and what actually brings one.
 *
 * Everything on this panel is checked against the pet standing in front of the player rather
 * than written as general advice, because the useful answer to "why has nobody come" is almost
 * always one specific thing about this save — too young, asleep, or nobody ever taught it how to
 * say hello.
 */
@Composable
private fun NobodyYetPanel(pet: PetState, modifier: Modifier = Modifier) {
    val oldEnough = pet.stage.order >= LifeStage.CHILD.order && !pet.isDead
    val awake = !pet.isSleeping
    val knowsHow = Skill.SOCIALISE in pet.skills
    val allowed = pet.autonomy == Autonomy.FULL
    val sociability = (pet.genome.sociability * 100f).roundToInt()

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = NeoAccents.cyan,
        title = stringResource(R.string.colony_nobody_yet),
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                tint = NeoAccents.cyan,
                modifier = Modifier.size(pixelUnits(7)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = stringResource(R.string.colony_alone, pet.name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            text = stringResource(R.string.colony_what_is_a_visitor, pet.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        Text(
            text = stringResource(R.string.colony_before_calls),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(pixelUnits(1)))
        Condition(
            met = oldEnough,
            text = if (oldEnough) {
                stringResource(R.string.colony_old_enough, pet.name)
            } else {
                stringResource(R.string.colony_too_young, pet.name, pet.stage.displayName.lowercase())
            },
        )
        Condition(
            met = awake,
            text = if (awake) {
                stringResource(R.string.colony_awake, pet.name)
            } else {
                stringResource(R.string.colony_asleep, pet.name)
            },
        )
        Condition(
            met = true,
            neutral = true,
            text = stringResource(R.string.colony_sociability, sociability),
        )

        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        Text(
            text = stringResource(R.string.colony_before_friend),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(pixelUnits(1)))
        Condition(
            met = knowsHow,
            text = if (knowsHow) {
                stringResource(R.string.colony_knows_socialise, pet.name, Skill.SOCIALISE.displayName.lowercase())
            } else {
                stringResource(R.string.colony_lacks_socialise, pet.name, Skill.SOCIALISE.displayName.lowercase())
            },
        )
        Condition(
            met = allowed,
            text = if (allowed) {
                stringResource(R.string.colony_may_approach, pet.autonomy.displayName.lowercase(), pet.name)
            } else {
                stringResource(
                    R.string.colony_may_not_approach,
                    pet.name,
                    pet.autonomy.displayName,
                    Autonomy.FULL.displayName,
                )
            },
        )
        Condition(
            met = true,
            neutral = true,
            text = stringResource(
                R.string.colony_currency,
                Pal.FRIEND_AT.roundToInt(),
                Pal.COURT_AT.roundToInt(),
            ),
        )
    }
}

/**
 * One line of the empty-state checklist: a filled tick for something already true, an empty slot
 * for something still outstanding, and no tick at all for a line that is only information.
 */
@Composable
private fun Condition(
    met: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    neutral: Boolean = false,
) {
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = when {
        neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        met -> NeoAccents.green
        else -> NeoAccents.gold
    }
    val prefix = when {
        neutral -> ""
        met -> stringResource(R.string.cd_condition_met)
        else -> stringResource(R.string.cd_condition_unmet)
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = pixelUnits(1))
            .semantics(mergeDescendants = true) { contentDescription = prefix + text },
    ) {
        if (neutral) {
            Spacer(Modifier.width(pixelUnits(6)))
        } else {
            Box(
                modifier = Modifier
                    .size(pixelUnits(5))
                    .pixelSurface(
                        fill = lerp(panel, accent, if (met) 0.24f else 0.06f),
                        accent = accent,
                        bevel = PixelBevel.PRESSED,
                        borderUnits = 1,
                        background = panel,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (met) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(pixelUnits(3)),
                    )
                }
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------- the family

/**
 * The line this pet sits in: the two names above it, the mates beside it, the children below.
 *
 * A founder is stated as a founder rather than left blank. "No parents recorded" and "this line
 * starts here" are different facts, and only one of them is ever true in this game — every save
 * knows perfectly well whether its pet hatched from a pairing or from nothing.
 */
@Composable
private fun FamilyPanel(pet: PetState, tree: FamilyTree, modifier: Modifier = Modifier) {
    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = NeoAccents.gold,
        title = stringResource(R.string.colony_family),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = "${tree.offspring.size}",
                color = if (tree.offspring.isEmpty()) MaterialTheme.colorScheme.surface else NeoAccents.gold,
                contentDescription = stringResource(
                    R.string.cd_colony_children,
                    childCount(tree.offspring.size),
                    tree.name,
                ),
            )
        },
    ) {
        FamilyLine(
            label = stringResource(R.string.colony_parents),
            body = if (tree.parentNames.isEmpty()) {
                stringResource(R.string.colony_founder, tree.name)
            } else {
                tree.parentNames.joinToString(stringResource(R.string.conjunction_and))
            },
            muted = tree.parentNames.isEmpty(),
        )
        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        FamilyLine(
            label = stringResource(R.string.colony_mates),
            body = if (tree.mates.isEmpty()) {
                stringResource(R.string.colony_no_mates, Pal.COURT_AT.roundToInt())
            } else {
                tree.mates.joinToString(listSeparator()) { it.name }
            },
            muted = tree.mates.isEmpty(),
        )
        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        FamilyLine(
            label = stringResource(R.string.colony_children),
            body = if (tree.offspring.isEmpty()) {
                stringResource(R.string.colony_no_children)
            } else {
                val nameAndStage = stringResource(R.string.colony_child_name_stage)
                tree.offspring.joinToString(listSeparator()) {
                    nameAndStage.format(it.name, it.stage.displayName.lowercase())
                }
            },
            muted = tree.offspring.isEmpty(),
        )

        if (tree.expecting.isNotEmpty()) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelDivider()
            Spacer(Modifier.height(pixelUnits(2)))
            FamilyLine(
                label = stringResource(R.string.colony_expecting),
                body = run {
                    val withWhom = stringResource(R.string.colony_with_parent)
                    tree.expecting.joinToString(listSeparator()) { withWhom.format(it.otherParentName) }
                },
                muted = false,
            )
        }

        if (tree.friends.isNotEmpty()) {
            Spacer(Modifier.height(pixelUnits(2)))
            PixelDivider()
            Spacer(Modifier.height(pixelUnits(2)))
            FamilyLine(
                label = stringResource(R.string.colony_friends),
                body = tree.friends.joinToString(listSeparator()) { it.name },
                muted = false,
            )
        }

        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            text = stringResource(R.string.colony_generation, pet.generation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A labelled block of names.
 *
 * The label sits above the names rather than in a fixed column beside them. A colony can hold a
 * dozen companions and a name is whatever the player typed, so a fixed label column is a width
 * that a long list, a narrow phone or a large font scale all eventually break.
 */
@Composable
private fun FamilyLine(label: String, body: String, muted: Boolean, modifier: Modifier = Modifier) {
    val readOut = stringResource(R.string.cd_labelled_line, label, body)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- the nest

/** What is in the nest, and how long each of it has left. */
@Composable
private fun NestPanel(pet: PetState, incubation: Long, modifier: Modifier = Modifier) {
    val next = Colony.nextHatchInSeconds(pet)
    val accent = if (pet.nest.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else NeoAccents.green

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        title = stringResource(R.string.colony_nest),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = stringResource(R.string.fraction, pet.nest.size, Colony.MAX_NEST_EGGS),
                color = if (pet.nest.isEmpty()) MaterialTheme.colorScheme.surface else NeoAccents.green,
                contentDescription = stringResource(
                    R.string.cd_colony_nest,
                    eggCount(pet.nest.size),
                    Colony.MAX_NEST_EGGS,
                ),
            )
        },
    ) {
        if (pet.nest.isEmpty()) {
            Text(
                text = stringResource(
                    R.string.colony_nest_empty,
                    pet.name,
                    Colony.MAX_NEST_EGGS,
                    clock(incubation),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (next != null && next <= 0L) {
                    stringResource(R.string.colony_egg_due)
                } else {
                    stringResource(R.string.colony_next_hatch, clock(next ?: 0L))
                },
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            pet.nest.forEachIndexed { index, egg ->
                Spacer(Modifier.height(pixelUnits(2)))
                if (index > 0) {
                    PixelDivider()
                    Spacer(Modifier.height(pixelUnits(2)))
                }
                EggRow(egg = egg, ageSeconds = pet.ageSeconds)
            }
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                text = stringResource(R.string.colony_genes_fixed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One egg: whose it is, and how far through incubation it has got. */
@Composable
private fun EggRow(
    egg: NestEgg,
    ageSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val otherParent = egg.otherParentName
    val span = (egg.hatchesAtSeconds - egg.laidAtSeconds).coerceAtLeast(1L)
    val left = (egg.hatchesAtSeconds - ageSeconds).coerceAtLeast(0L)
    val done = ((span - left).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val ready = egg.isReady(ageSeconds)
    val accent = if (ready) NeoAccents.gold else NeoAccents.green

    val readOut = stringResource(
        R.string.cd_colony_egg,
        otherParent,
        egg.species.displayName,
        if (ready) {
            stringResource(R.string.cd_colony_egg_ready)
        } else {
            stringResource(R.string.cd_colony_egg_progress, (done * 100f).roundToInt(), clock(left))
        },
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Portrait(
            species = egg.species,
            stage = LifeStage.EGG,
            genome = egg.genome,
            portraitSize = EggPortrait,
            accent = accent,
            description = null,
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.colony_with_parent, otherParent),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (ready) {
                    stringResource(R.string.colony_egg_ready, egg.species.displayName)
                } else {
                    stringResource(R.string.colony_egg_left, egg.species.displayName, clock(left))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            PixelBar(
                fraction = done,
                color = accent,
                segments = 12,
                height = pixelUnits(4),
            )
        }
    }
}

// ---------------------------------------------------------------- breeding

/**
 * What this pairing would make, and — always — why it can or cannot be made.
 *
 * The preview is shown even when the pairing is refused. "Here is the creature, and here is the
 * one thing standing between you and it" is a goal; a greyed-out button with nothing beside it
 * is a dead end, and the rules behind the refusal (a skill, two ages, a fondness level, a full
 * nest, a shared bloodline) are not guessable from outside.
 */
@Composable
private fun BreedingPanel(
    pet: PetState,
    partner: Pal,
    blocker: String?,
    stacked: Boolean,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Deterministic by construction: previewChild averages the two genomes and rolls nothing.
    // Remembered anyway, and keyed only on what it actually reads, so the once-a-second clock
    // does not rebuild a Morphology and a sentence for a picture that has not changed.
    val preview: ChildPreview? = remember(pet.genome, pet.branch, pet.name, partner.id, partner.genome) {
        Colony.previewChild(pet, partner.id)
    }
    val accent = if (blocker == null) NeoAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        title = stringResource(R.string.colony_breeding),
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        if (preview == null) {
            // Only reachable if the companion is forgotten between the list being read and this
            // panel being drawn. Saying so beats an empty panel.
            Text(
                text = stringResource(R.string.colony_partner_gone, partner.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PixelPanel
        }

        ParentsRow(pet = pet, partner = partner)
        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        ChildPreviewBlock(pet = pet, partner = partner, preview = preview, stacked = stacked)

        Spacer(Modifier.height(pixelUnits(3)))
        if (blocker != null) {
            // The refusal sits above the button, not after it: the reason has to be read before
            // the dead control is pressed, not as an explanation for why nothing happened.
            BlockerNote(blocker)
            Spacer(Modifier.height(pixelUnits(2)))
        }
        val pairReadOut = if (blocker == null) {
            stringResource(R.string.cd_colony_pair, pet.name, partner.name)
        } else {
            stringResource(R.string.cd_colony_pair_blocked, partner.name, blocker)
        }
        PixelButton(
            onClick = onPair,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = pairReadOut },
            enabled = blocker == null,
            accent = accent,
            fill = if (blocker == null) {
                lerp(MaterialTheme.colorScheme.surfaceVariant, NeoAccents.gold, 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentPadding = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(pixelUnits(6)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (blocker == null) {
                        stringResource(R.string.colony_pair_with, partner.name.uppercase())
                    } else {
                        stringResource(R.string.colony_cannot_pair)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (blocker == null) R.string.colony_pair_detail else R.string.colony_pair_blocked_detail,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The two creatures being read into the child, so the resemblance can be checked by eye. */
@Composable
private fun ParentsRow(pet: PetState, partner: Pal, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        ParentChip(
            name = pet.name,
            species = pet.species,
            stage = pet.stage,
            branch = pet.branch,
            genome = pet.genome,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Text(
            text = "+",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(pixelUnits(2)))
        ParentChip(
            name = partner.name,
            species = partner.species,
            stage = partner.stage,
            branch = EvolutionBranch.BALANCED,
            genome = partner.genome,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One parent: its silhouette and its name, sized to sit beside the other. */
@Composable
private fun ParentChip(
    name: String,
    species: Species,
    stage: LifeStage,
    branch: EvolutionBranch,
    genome: Genome,
    modifier: Modifier = Modifier,
) {
    val chipReadOut = stringResource(R.string.cd_colony_parent_chip, name, describe(species, stage, genome))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = chipReadOut },
    ) {
        Portrait(
            species = species,
            stage = stage,
            branch = branch,
            genome = genome,
            portraitSize = ParentPortrait,
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
            description = null,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The child itself.
 *
 * Drawn as an adult, which is what [Colony.previewChild] hands over — a preview at the child's
 * own stage is a featureless newborn every time and tells the player nothing about the pairing.
 * The picture is the average of the two genomes with no mutation roll, which is stated in words
 * underneath rather than implied, because an average shown as a specimen is a promise the egg
 * will not keep.
 */
@Composable
private fun ChildPreviewBlock(
    pet: PetState,
    partner: Pal,
    preview: ChildPreview,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val shiftPoints = (preview.houndlinessShift * 100f).roundToInt()
    val mine = (pet.genome.houndliness * 100f).roundToInt()
    val distance = (preview.parentDistance * 100f).roundToInt()
    val minApart = (Genome.MIN_USEFUL_DISTANCE * 100f).roundToInt()
    // Tested against the raw float, not the rounded percentage: 0.058 rounds to 6, and colouring
    // it as "fine" while the domain refuses it is the one thing this line must never do.
    val tooClose = preview.parentDistance < Genome.MIN_USEFUL_DISTANCE
    val shiftAccent = when {
        shiftPoints > 1 -> NeoAccents.gold
        shiftPoints < -1 -> NeoAccents.cyan
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shiftWord = when {
        shiftPoints > 1 -> stringResource(R.string.colony_shift_hound)
        shiftPoints < -1 -> stringResource(R.string.colony_shift_blob)
        else -> stringResource(R.string.colony_shift_same, pet.name)
    }

    val portraitDescription = stringResource(
        R.string.cd_colony_child_portrait,
        pet.name,
        partner.name,
        describe(preview.genome),
        preview.summary,
    )
    val shapeReadOut = stringResource(
        R.string.cd_colony_shape,
        shiftWord,
        signed(shiftPoints),
        pet.name,
        mine,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.colony_likely_child),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(pixelUnits(2)))

        // A narrow phone stacks the portrait over the words; anything wider sets them side by
        // side, which is where the picture and the sentence about it are easiest to compare.
        if (stacked) {
            Portrait(
                species = pet.species,
                stage = LifeStage.ADULT,
                branch = pet.branch,
                genome = preview.genome,
                morphology = preview.morphology,
                portraitSize = ChildPortrait,
                accent = NeoAccents.gold,
                description = portraitDescription,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(pixelUnits(2)))
            ChildWords(preview.summary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Portrait(
                    species = pet.species,
                    stage = LifeStage.ADULT,
                    branch = pet.branch,
                    genome = preview.genome,
                    morphology = preview.morphology,
                    portraitSize = ChildPortrait,
                    accent = NeoAccents.gold,
                    description = portraitDescription,
                )
                Spacer(Modifier.width(pixelUnits(3)))
                Box(modifier = Modifier.weight(1f)) { ChildWords(preview.summary) }
            }
        }

        Spacer(Modifier.height(pixelUnits(2)))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = shapeReadOut },
        ) {
            Text(
                text = stringResource(R.string.colony_shape),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(pixelUnits(12)),
            )
            PixelBadge(
                text = signed(shiftPoints),
                color = shiftAccent,
                background = MaterialTheme.colorScheme.surfaceVariant,
                contentDescription = "",
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = stringResource(R.string.colony_shift_line, shiftWord, pet.name, mine),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(pixelUnits(1)))
        Text(
            text = stringResource(R.string.colony_bloodlines, distance, minApart),
            style = MaterialTheme.typography.labelSmall,
            color = if (tooClose) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (partner.species != pet.species) {
            Spacer(Modifier.height(pixelUnits(1)))
            // The shape is the average of the two genomes and is therefore drawable; the palette
            // is not, because the egg picks a family at random. Drawing it in one family and
            // saying nothing would make the other outcome look like a bug.
            Text(
                text = stringResource(
                    R.string.colony_palette_caveat,
                    pet.species.displayName,
                    partner.species.displayName,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The sentence the domain wrote about this pairing, plus the caveat that it is an average. */
@Composable
private fun ChildWords(summary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        Text(
            text = stringResource(R.string.colony_average_caveat),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The one sentence standing between the player and a pairing. */
@Composable
private fun BlockerNote(reason: String, modifier: Modifier = Modifier) {
    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.error,
        bevel = PixelBevel.FLAT,
        borderUnits = 1,
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(pixelUnits(5)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------- drawing

/**
 * One creature at the shape its genes actually produced.
 *
 * The [CreatureSpec] — and the [Morphology] inside it — is built once and remembered. The draw
 * lambda runs again on every frame and on every resize, so anything allocated in there is paid
 * for over and over; a list of a dozen companions is exactly where that starts to show. Pass
 * [morphology] when the caller already has one (the child preview does) so it is not expressed
 * a second time.
 *
 * Pass [description] only when this drawing is a stop of its own. In a row that already reads
 * itself out as one sentence it stays null, because a second reading of the same creature makes
 * TalkBack say it twice.
 */
@Composable
private fun Portrait(
    species: Species,
    stage: LifeStage,
    genome: Genome,
    portraitSize: Dp,
    accent: Color,
    description: String?,
    modifier: Modifier = Modifier,
    branch: EvolutionBranch = EvolutionBranch.BALANCED,
    morphology: Morphology? = null,
) {
    val spec = remember(species, stage, branch, genome, morphology) {
        CreatureSpec(
            species = species,
            stage = stage,
            branch = branch,
            mood = Mood.HAPPY,
            morphology = morphology ?: Morphology.of(genome, stage, branch),
        )
    }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    val well = pixelWellFill(surface, background)

    Box(
        modifier = modifier
            .size(portraitSize)
            .pixelSurface(
                fill = well,
                accent = dimmedFor(accent, surface, 0.35f),
                bevel = PixelBevel.PRESSED,
                borderUnits = 1,
                background = surface,
            )
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Offset and Size are value classes and the spec is hoisted, so nothing here reaches
            // the heap. The 0.86 keeps ears and legs inside a box this small.
            drawCreature(
                center = Offset(size.width / 2f, size.height * 0.58f),
                unit = size.minDimension * 0.86f,
                spec = spec,
                frame = ColonyPose,
            )
        }
    }
}

// ---------------------------------------------------------------- words

/** Which accent stands for a given standing. Family is gold, a friend green, a stranger cyan. */
@Composable
private fun accentFor(relation: Relation): Color = when (relation) {
    Relation.OFFSPRING, Relation.PARENT, Relation.MATE -> NeoAccents.gold
    Relation.FRIEND -> NeoAccents.green
    Relation.VISITOR -> NeoAccents.cyan
}

/**
 * The next rung on the affinity ladder, or null once there is nothing left above.
 *
 * A meter carrying both marks at once is a pair of ticks four pixels apart that nobody can tell
 * apart. One mark, for the thing the player is currently walking towards, is legible.
 */
private fun nextMarkFor(pal: Pal): Float? = when {
    pal.affinity < Pal.FRIEND_AT -> Pal.FRIEND_AT
    pal.affinity < Pal.COURT_AT -> Pal.COURT_AT
    else -> null
}

/** The mark, said out loud, for the row's read-out. */
@Composable
private fun markSentence(pal: Pal, mark: Float?): String {
    if (mark == null) return stringResource(R.string.cd_colony_fond_enough)
    val gap = (mark - pal.affinity).roundToInt().coerceAtLeast(1)
    val rung = stringResource(
        if (mark == Pal.FRIEND_AT) R.string.colony_rung_friendship else R.string.colony_rung_courting,
    )
    return stringResource(R.string.cd_colony_mark_sentence, gap, rung, mark.roundToInt())
}

/** The same fact under the meter, short enough to sit on one line on a narrow phone. */
@Composable
private fun markCaption(pal: Pal, mark: Float?): String {
    if (mark == null) return stringResource(R.string.colony_past_courting, Pal.COURT_AT.roundToInt())
    val gap = (mark - pal.affinity).roundToInt().coerceAtLeast(1)
    val rung = stringResource(
        if (mark == Pal.FRIEND_AT) R.string.colony_rung_friendship else R.string.colony_rung_courting,
    )
    return stringResource(R.string.colony_mark_caption, gap, rung, mark.roundToInt())
}

/**
 * How a list of names is joined. A comma and a space in English; the resource exists so a
 * locale that separates differently is a file change and not a code change.
 */
@Composable
private fun listSeparator(): String = stringResource(R.string.list_separator)

/** "Adult Volt, long-muzzled and leggy" — enough for a screen reader to picture the drawing. */
@Composable
private fun describe(species: Species, stage: LifeStage, genome: Genome): String =
    stringResource(R.string.colony_describe, stage.displayName.lowercase(), species.displayName, describe(genome))

/** The silhouette in words, read off the same number the art reads. */
@Composable
private fun describe(genome: Genome): String = stringResource(
    when {
        genome.houndliness < 0.20f -> R.string.silhouette_round
        genome.houndliness < 0.40f -> R.string.silhouette_leggy
        genome.houndliness < 0.60f -> R.string.silhouette_half_hound
        genome.houndliness < 0.80f -> R.string.silhouette_houndish
        else -> R.string.silhouette_hound
    },
)

/** A signed whole number for a badge. The minus is a real minus sign, not a hyphen. */
private fun signed(points: Int): String = when {
    points > 0 -> "+$points"
    points < 0 -> "−${-points}"
    else -> "0"
}

@Composable
private fun creatureCount(n: Int): String = pluralStringResource(R.plurals.creature_count, n, n)

@Composable
private fun childCount(n: Int): String = pluralStringResource(R.plurals.child_count, n, n)

@Composable
private fun eggCount(n: Int): String = pluralStringResource(R.plurals.egg_count, n, n)

/**
 * A countdown in the coarsest unit that still says something.
 *
 * Coarse on purpose, like every other clock in this game: an egg sits for the better part of
 * half an hour, and a ticking second hand on it is motion rather than information — except in
 * the last minute, where the seconds are the whole point.
 */
@Composable
private fun clock(seconds: Long): String {
    val left = seconds.coerceAtLeast(0L)
    val hours = left / 3600L
    val minutes = (left % 3600L) / 60L
    return when {
        hours > 0L -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0L -> stringResource(R.string.duration_minutes, minutes)
        else -> stringResource(R.string.duration_seconds, left)
    }
}
