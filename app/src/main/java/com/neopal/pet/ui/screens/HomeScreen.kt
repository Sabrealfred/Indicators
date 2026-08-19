package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.ItemCatalog
import com.neopal.pet.domain.ItemKind
import com.neopal.pet.domain.PetState
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.Routes
import com.neopal.pet.ui.art.ItemIcon
import com.neopal.pet.ui.components.ActionButton
import com.neopal.pet.ui.components.CoinPill
import com.neopal.pet.ui.components.ConsoleFrame
import com.neopal.pet.ui.components.FaceButton
import com.neopal.pet.ui.components.LevelPill
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.PetStage
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.components.ToastBanner
import com.neopal.pet.ui.components.WindowSize
import com.neopal.pet.ui.components.rememberWindowSize
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/** The pet is the point of this screen; chrome never gets more than this share of the height. */
private const val CHROME_MAX_FRACTION = 0.42f

/** In a two-pane layout the scene keeps at least this much of the window height. */
private const val PET_MIN_HEIGHT_FRACTION = 0.45f

/** One dock entry. Holding them as data lets the same set render as a row or as a grid. */
private data class HomeAction(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val enabled: Boolean = true,
    val badge: Int? = null,
    val onClick: () -> Unit,
)

/**
 * The main screen: the console chassis, the live pet, its meters, and the action dock.
 * Everything the player needs minute to minute is one tap away from here.
 *
 * The screen has two shapes. Portrait on a phone stacks bar / scene / meters / dock. Landscape and
 * tablet widths put the scene on the left and move all the chrome into a standing right panel, so
 * the pet never gets letterboxed and a tablet is not just a stretched phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: PetViewModel, onOpen: (String) -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    var showFeedSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val window = rememberWindowSize()

    LaunchedEffect(pet.isDead) {
        if (pet.isDead) onOpen(Routes.MEMORIAL)
    }

    // The scene is passed down as a slot so each layout can size it without re-plumbing PetStage.
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        PetStage(
            state = pet,
            config = ui.config,
            action = ui.animation,
            actionId = ui.animationId,
            deltas = ui.deltas,
            servedItemId = ui.servedItemId,
            modifier = stageModifier,
            onTapPet = { viewModel.petPet() },
            onDoubleTapPet = { viewModel.tickle() },
            onLongPressPet = { viewModel.snapshot("${pet.name}, ${pet.stage.displayName}") },
            onScoopPoop = { viewModel.scoopPoop() },
            onSwipeUp = { viewModel.toss() },
        )
    }

    val actions = listOf(
        HomeAction("Feed", Icons.Filled.Restaurant, NeoColors.StatSatiety, enabled = !pet.isDead) { showFeedSheet = true },
        HomeAction("Clean", Icons.Filled.CleaningServices, NeoColors.StatHygiene, enabled = !pet.isDead, badge = pet.poops) { viewModel.cleanRoom() },
        HomeAction("Play", Icons.Filled.SportsEsports, NeoColors.NeonCyan, enabled = CareActions.canPlay(pet) == null) { onOpen(Routes.GAMES) },
        HomeAction("Medicine", Icons.Filled.Medication, NeoColors.StatHealth, enabled = !pet.isDead, badge = if (pet.isSick) 1 else 0) { viewModel.useMedicine() },
        HomeAction(if (pet.lightsOff) "Lights on" else "Lights off", Icons.Filled.Lightbulb, NeoColors.StatEnergy, enabled = !pet.isDead) { viewModel.toggleLights() },
        HomeAction("Praise", Icons.Filled.ThumbUp, NeoColors.StatBond, enabled = !pet.isDead) { viewModel.praise() },
        HomeAction("Scold", Icons.Filled.ThumbDown, NeoColors.StatDiscipline, enabled = !pet.isDead) { viewModel.scold() },
        HomeAction("Shop", Icons.Filled.ShoppingBag, NeoColors.NeonPurple) { onOpen(Routes.SHOP) },
        HomeAction("Album", Icons.Filled.PhotoCamera, NeoColors.NeonYellow) { onOpen(Routes.ALBUM) },
        HomeAction("Diary", Icons.AutoMirrored.Filled.MenuBook, NeoColors.StatHygiene) { onOpen(Routes.CHRONICLE) },
        HomeAction("Awards", Icons.Filled.EmojiEvents, NeoColors.NeonGreen) { onOpen(Routes.ACHIEVEMENTS) },
        HomeAction("Settings", Icons.Filled.Settings, NeoColors.OnDarkMuted) { onOpen(Routes.SETTINGS) },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ConsoleFrame(
            modifier = Modifier
                .fillMaxSize()
                // safeDrawing, not just the status bar: in landscape the cutout and the gesture
                // bar are on the sides, exactly where the rails live.
                .windowInsetsPadding(WindowInsets.safeDrawing),
            onFace = { button ->
                when (button) {
                    FaceButton.A -> viewModel.petPet()
                    FaceButton.B -> Unit
                    FaceButton.X -> onOpen(Routes.GAMES)
                    FaceButton.Y -> showFeedSheet = true
                }
            },
            onMenu = { onOpen(Routes.SETTINGS) },
            onHome = { onOpen(Routes.STATS) },
        ) {
            if (window.isTwoPane) {
                TwoPaneHome(pet = pet, config = ui.config, window = window, actions = actions, stage = stage)
            } else {
                StackedHome(pet = pet, config = ui.config, actions = actions, stage = stage)
            }
        }

        ToastBanner(
            message = ui.toast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = if (window.isShort) 40.dp else 70.dp),
        )

        ui.offlineReport?.let { report ->
            OfflineReportCard(
                report = report,
                onDismiss = viewModel::dismissOfflineReport,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!ui.config.tutorialSeen) {
            TutorialOverlay(
                petName = pet.name,
                onDone = viewModel::markTutorialSeen,
                modifier = Modifier.fillMaxSize(),
            )
        }

        ui.achievementBanner?.let { achievement ->
            AchievementBanner(
                title = achievement.title,
                description = achievement.description,
                reward = achievement.rewardCoins,
                onDismiss = viewModel::dismissAchievementBanner,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = if (window.isShort) 40.dp else 70.dp),
            )
        }
    }

    if (showFeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFeedSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            FeedSheet(
                pet = pet,
                onFeed = { id ->
                    viewModel.feed(id)
                    showFeedSheet = false
                },
                onShop = {
                    showFeedSheet = false
                    onOpen(Routes.SHOP)
                },
            )
        }
    }
}

/**
 * Portrait phone: bar, scene, meters, dock. The meters and dock are capped and scroll internally,
 * so a small phone — or a 1.3x font scale — eats into the chrome rather than into the pet.
 */
@Composable
private fun StackedHome(
    pet: PetState,
    config: GameConfig,
    actions: List<HomeAction>,
    stage: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val chromeMax = maxHeight * CHROME_MAX_FRACTION
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(pet = pet, config = config)

            stage(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = chromeMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                StatsStrip(pet)
                ActionRow(actions)
            }
        }
    }
}

/**
 * Landscape and tablet: the scene keeps the left and most of the width, everything else stands in
 * a persistent right panel. The panel scrolls, so large fonts push it into a scroll, never a clip.
 */
@Composable
private fun TwoPaneHome(
    pet: PetState,
    config: GameConfig,
    window: WindowSize,
    actions: List<HomeAction>,
    stage: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelFraction = if (window.isExpandedWidth) 0.34f else 0.32f
        // The panel is sized for its content, but the scene always keeps the larger half.
        val panelWidth = (maxWidth * panelFraction)
            .coerceIn(200.dp, 360.dp)
            .coerceAtMost(maxWidth * 0.45f)
        val petMinHeight = maxHeight * PET_MIN_HEIGHT_FRACTION

        Row(modifier = Modifier.fillMaxSize()) {
            stage(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = petMinHeight)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TopBar(pet = pet, config = config, dense = true)
                // A tablet has room for the whole read-out; a landscape phone panel gets the core five.
                StatsPanel(pet = pet, showAll = window.isExpandedWidth)
                ActionGrid(actions)
            }
        }
    }
}

@Composable
private fun TopBar(
    pet: PetState,
    config: GameConfig,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val name = pet.name
    val subtitle = "${pet.stage.displayName} · ${pet.branch.displayName} · day ${pet.ageInPetDays(config)}"
    if (dense) {
        // In a ~200-360dp panel the pills and a name cannot share a line without the name vanishing.
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = NeoColors.OnDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = NeoColors.OnDarkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelPill(pet.level, pet.xp, pet.xpForNextLevel)
                Spacer(Modifier.width(8.dp))
                CoinPill(pet.coins)
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = NeoColors.OnDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoColors.OnDarkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            LevelPill(pet.level, pet.xp, pet.xpForNextLevel)
            Spacer(Modifier.width(8.dp))
            CoinPill(pet.coins)
        }
    }
}

/**
 * The five-across strip for portrait. The bars run in compact mode with the label drawn above:
 * a labelled StatBar puts label and value on one 60dp-wide line, which a large font scale clips.
 */
@Composable
private fun StatsStrip(pet: PetState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatCell("FOOD", pet.stats.satiety, NeoColors.StatSatiety, Modifier.weight(1f))
        StatCell("MOOD", pet.stats.happiness, NeoColors.StatHappiness, Modifier.weight(1f))
        StatCell("ENERGY", pet.stats.energy, NeoColors.StatEnergy, Modifier.weight(1f))
        StatCell("CLEAN", pet.stats.hygiene, NeoColors.StatHygiene, Modifier.weight(1f))
        StatCell("HP", pet.stats.health, NeoColors.StatHealth, Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // StatBar already reads out "LABEL n of 100"; this copy would just repeat it.
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.height(3.dp))
        StatBar(label = label, value = value, color = color, compact = true)
    }
}

/** The standing panel's meters: full-width bars with room for label and value on one line. */
@Composable
private fun StatsPanel(pet: PetState, showAll: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatBar("Satiety", pet.stats.satiety, NeoColors.StatSatiety)
        StatBar("Happiness", pet.stats.happiness, NeoColors.StatHappiness)
        StatBar("Energy", pet.stats.energy, NeoColors.StatEnergy)
        StatBar("Hygiene", pet.stats.hygiene, NeoColors.StatHygiene)
        StatBar("Health", pet.stats.health, NeoColors.StatHealth)
        if (showAll) {
            StatBar("Discipline", pet.stats.discipline, NeoColors.StatDiscipline)
            StatBar("Bond", pet.stats.bond, NeoColors.StatBond)
        }
    }
}

/** Compact widths: one scrolling row, because there is no room for a second line of buttons. */
@Composable
private fun ActionRow(actions: List<HomeAction>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { action ->
            DockButton(action, Modifier.widthIn(min = 64.dp))
        }
    }
}

/** Wide windows: a wrapping grid. A horizontal scroller in a standing panel wastes the space. */
@Composable
private fun ActionGrid(actions: List<HomeAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        val columns = (maxWidth / 78.dp).toInt().coerceIn(2, 5)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            actions.chunked(columns).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowActions.forEach { action -> DockButton(action, Modifier.weight(1f)) }
                    // Keep the last row's cells the same width as every other row's.
                    repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DockButton(action: HomeAction, modifier: Modifier = Modifier) {
    ActionButton(
        label = action.label,
        icon = action.icon,
        accent = action.accent,
        onClick = action.onClick,
        modifier = modifier.heightIn(min = MinTouchTarget + 24.dp),
        enabled = action.enabled,
        badge = action.badge,
    )
}

@Composable
private fun FeedSheet(pet: PetState, onFeed: (String) -> Unit, onShop: () -> Unit) {
    val owned = ItemCatalog.foods.filter { (pet.inventory[it.id] ?: 0) > 0 } +
        ItemCatalog.all.filter { it.kind == ItemKind.MEDICINE && (pet.inventory[it.id] ?: 0) > 0 }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Pantry", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "Satiety ${pet.stats.satiety.roundToInt()} · weight ${pet.weightGrams.roundToInt()} g",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (owned.isEmpty()) {
            Text("The pantry is empty.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onShop, modifier = Modifier.fillMaxWidth()) { Text("Go to the shop") }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(owned) { item ->
                    Card(
                        onClick = { onFeed(item.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.width(104.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(androidx.compose.ui.graphics.Color(item.tint).copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                ItemIcon(item.iconKey, androidx.compose.ui.graphics.Color(item.tint), Modifier.size(44.dp), variant = item.id)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "x${pet.inventory[item.id] ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AchievementBanner(
    title: String,
    description: String,
    reward: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(title) {
        kotlinx.coroutines.delay(2_800)
        onDismiss()
    }
    Card(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NeoColors.SurfaceCard),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = NeoColors.NeonYellow, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = NeoColors.OnDark)
                Text("$description  ·  +$reward coins", style = MaterialTheme.typography.labelSmall, color = NeoColors.OnDarkMuted)
            }
        }
    }
}

/**
 * First-run coach marks. Three short cards, dismissible at any point, shown once — the
 * gestures on the pet are invisible otherwise and nobody discovers them by accident.
 */
@Composable
private fun TutorialOverlay(petName: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    val steps = listOf(
        "Tap $petName to pet it. Double-tap to tickle, swipe up to toss it in the air." to "Say hello",
        "Tap a mess on the floor to scoop it. Long-press $petName for a photo." to "Got it",
        "Feed, play and clean to raise it well — how you care decides what it evolves into." to "Start",
    )
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xCC08090F))
            .clickable { if (step < steps.lastIndex) step += 1 else onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = NeoColors.SurfaceCard),
            modifier = Modifier.padding(28.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(22.dp),
            ) {
                Text(
                    text = "TIP ${step + 1}/${steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoColors.NeonCyan,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = steps[step].first,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeoColors.OnDark,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { if (step < steps.lastIndex) step += 1 else onDone() }) {
                    Text(steps[step].second)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoColors.OnDarkMuted,
                    modifier = Modifier.clickable { onDone() },
                )
            }
        }
    }
}

/**
 * Shown once after a long absence. A virtual pet that quietly changes behind your back feels
 * broken; being told what happened — good and bad — is what makes the offline simulation land.
 */
@Composable
private fun OfflineReportCard(
    report: PetViewModel.OfflineReport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val away = when {
        report.minutesAway >= 1440 -> "${report.minutesAway / 1440} day(s)"
        report.minutesAway >= 60 -> "${report.minutesAway / 60} hour(s)"
        else -> "${report.minutesAway} minutes"
    }
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xCC08090F))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = NeoColors.SurfaceCard),
            modifier = Modifier.padding(26.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "WHILE YOU WERE AWAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeoColors.NeonCyan,
                )
                Text(
                    text = "$away without you",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeoColors.OnDark,
                )
                Spacer(Modifier.height(10.dp))
                report.lines.forEach { line ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("·  ", style = MaterialTheme.typography.bodyMedium, color = NeoColors.NeonCyan)
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = NeoColors.OnDark)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("I'm back")
                }
            }
        }
    }
}
