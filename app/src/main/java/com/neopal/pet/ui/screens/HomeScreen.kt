package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.Autonomy
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.Memorial
import com.neopal.pet.domain.Item
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
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.components.ToastBanner
import com.neopal.pet.ui.components.WindowSize
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberWindowSize
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** The pet is the point of this screen; chrome never gets more than this share of the height. */
private const val CHROME_MAX_FRACTION = 0.42f

/** In a two-pane layout the scene keeps at least this much of the window height. */
private const val PET_MIN_HEIGHT_FRACTION = 0.45f

/** The one item that washes instead of feeding, so the tray knows which gesture it belongs to. */
private const val SOAP_ID = "soap"

/** The care strip is chrome too, so the scrolling chrome below it gives up its share. */
private val CareStripHeight: Dp = 68.dp

/** Tray chips are square and never smaller than a comfortable thumb. */
private val TrayChipSize: Dp = 52.dp

/** The item that follows the finger while it is being carried. */
private val DragGhostSize: Dp = 48.dp

/** How far the finger has to travel over the pet before a scrub counts as a full wash. */
private val RubDistance: Dp = 360.dp

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
 * The single most useful thing to do this second, named before it is tapped. A button that only
 * promises to "help" is a coin flip; this one says which item it spends and which meter is low.
 */
private data class QuickCare(
    val label: String,
    val reason: String,
    val icon: ImageVector,
    val accent: Color,
    val onAct: () -> Unit,
)

/**
 * Live state of a tray drag. The gesture writes to it directly rather than going through
 * recomposition, so a fast finger can never read a frame-old position or a stale target.
 */
@Stable
private class TrayDragState {
    var item by mutableStateOf<Item?>(null)
    var position by mutableStateOf(Offset.Zero)
    var overStage by mutableStateOf(false)
    /** 0..1 of a full scrub; only the soap uses it. */
    var progress by mutableFloatStateOf(0f)
}

/**
 * The main screen: the console chassis, the live pet, its meters, and the action dock.
 * Everything the player needs minute to minute is one tap away from here.
 *
 * The screen has two shapes. Portrait on a phone stacks bar / scene / care strip / meters / dock.
 * Landscape and tablet widths put the scene on the left and move all the chrome into a standing
 * right panel, so the pet never gets letterboxed and a tablet is not just a stretched phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PetViewModel,
    /**
     * Called with [Memorial.deathKey] whenever this screen is built for a pet that has died.
     *
     * Whether that is *news* is not this screen's question to answer and it has nowhere to answer
     * it from: navigating to the memorial disposes this composition, so anything remembered here
     * comes back reset. The navigation graph holds the answer. See `NeoPalNav`.
     */
    onPetDied: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    var showFeedSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val window = rememberWindowSize()

    val deathKey = Memorial.deathKey(pet)
    LaunchedEffect(deathKey) {
        deathKey?.let(onPetDied)
    }

    // Where the scene ended up on screen, so a dragged item knows when it is over the pet.
    val stageBounds = remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val dragState = remember { TrayDragState() }
    val haptics = LocalHapticFeedback.current
    val hapticsOn by rememberUpdatedState(ui.config.hapticsEnabled)
    val rubDistancePx = with(LocalDensity.current) { RubDistance.toPx() }

    // The scene is passed down as a slot so each layout can size it without re-plumbing PetStage.
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        PetStage(
            state = pet,
            config = ui.config,
            action = ui.animation,
            actionId = ui.animationId,
            deltas = ui.deltas,
            servedItemId = ui.servedItemId,
            modifier = stageModifier.onGloballyPositioned { stageBounds.value = it.boundsInRoot() },
            onTapPet = { viewModel.petPet() },
            onDoubleTapPet = { viewModel.tickle() },
            onLongPressPet = { viewModel.snapshot("${pet.name}, ${pet.stage.displayName}") },
            onScoopPoop = { viewModel.scoopPoop() },
            onSwipeUp = { viewModel.toss() },
        )
    }

    // One highlight at a time: the dock should answer "what now?" without doing it for you.
    val urgent = CareActions.topNeed(pet)
    fun cue(need: String): Int = if (urgent == need) 1 else 0
    // A finished mission is the only thing in this game that waits to be collected, so it gets
    // the dock's own badge rather than a strip of its own — the pet keeps every pixel it had.
    val collectable = viewModel.missions().count { it.claimable }
    // True when the brain wanted something and could not have it for want of a skill. Anything
    // else it is blocked on — an empty pantry, broad daylight — is not the player's cue to teach.
    val wantsTeaching = pet.autonomy != Autonomy.OFF &&
        viewModel.considerations().any { it.blockedBy == "not learned yet" }
    val actions = listOf(
        HomeAction("Feed", Icons.Filled.Restaurant, NeoColors.StatSatiety, enabled = !pet.isDead, badge = cue("Hungry")) { showFeedSheet = true },
        HomeAction("Clean", Icons.Filled.CleaningServices, NeoColors.StatHygiene, enabled = !pet.isDead, badge = pet.poops) { viewModel.cleanRoom() },
        HomeAction("Play", Icons.Filled.SportsEsports, NeoColors.NeonCyan, enabled = CareActions.canPlay(pet) == null, badge = cue("Bored")) { onOpen(Routes.GAMES) },
        HomeAction("Missions", Icons.Filled.Assignment, NeoColors.NeonYellow, badge = collectable) { onOpen(Routes.MISSIONS) },
        HomeAction("Medicine", Icons.Filled.Medication, NeoColors.StatHealth, enabled = !pet.isDead, badge = if (pet.isSick) 1 else 0) { viewModel.useMedicine() },
        HomeAction(if (pet.lightsOff) "Lights on" else "Lights off", Icons.Filled.Lightbulb, NeoColors.StatEnergy, enabled = !pet.isDead, badge = cue("Sleepy")) { viewModel.toggleLights() },
        HomeAction("Praise", Icons.Filled.ThumbUp, NeoColors.StatBond, enabled = !pet.isDead) { viewModel.praise() },
        HomeAction("Scold", Icons.Filled.ThumbDown, NeoColors.StatDiscipline, enabled = !pet.isDead) { viewModel.scold() },
        // The badge is the pet asking to be taught: an autonomous creature that wants something
        // it never learned how to do is the one state this screen cannot show on its own.
        HomeAction("Mind", Icons.Filled.Psychology, NeoColors.NeonCyan, badge = if (wantsTeaching) 1 else 0) { onOpen(Routes.MIND) },
        // No badge: an unread count would be a lie, since the creature only ever speaks when
        // spoken to. It never starts a conversation on its own.
        HomeAction("Talk", Icons.AutoMirrored.Filled.Chat, NeoColors.StatBond, enabled = !pet.isDead) { onOpen(Routes.TALK) },
        HomeAction("Colony", Icons.Filled.Groups, NeoColors.NeonGreen, badge = pet.presentPals.size) { onOpen(Routes.COLONY) },
        HomeAction("Shop", Icons.Filled.ShoppingBag, NeoColors.NeonPurple) { onOpen(Routes.SHOP) },
        HomeAction("Album", Icons.Filled.PhotoCamera, NeoColors.NeonYellow) { onOpen(Routes.ALBUM) },
        HomeAction("Diary", Icons.AutoMirrored.Filled.MenuBook, NeoColors.StatHygiene) { onOpen(Routes.CHRONICLE) },
        HomeAction("Awards", Icons.Filled.EmojiEvents, NeoColors.NeonGreen) { onOpen(Routes.ACHIEVEMENTS) },
        HomeAction("Settings", Icons.Filled.Settings, NeoColors.OnDarkMuted) { onOpen(Routes.SETTINGS) },
    ) + if (pet.isDead) {
        // The way back. The memorial opens itself once per death and then stops, so without this
        // a player who chose "Stay a moment" would be sitting in a room with no door: the next
        // generation is started from the memorial and nowhere else.
        listOf(HomeAction("Memorial", Icons.Filled.LocalFlorist, NeoColors.OnDarkMuted) { onOpen(Routes.MEMORIAL) })
    } else {
        emptyList()
    }

    val quickCare = quickCareFor(pet, viewModel, onOpen)
    val inventory = pet.inventory
    val carriable = remember(inventory) {
        val foods = ItemCatalog.foods
            .filter { (inventory[it.id] ?: 0) > 0 }
            .sortedByDescending { it.satiety }
        val soap = ItemCatalog[SOAP_ID]?.takeIf { (inventory[SOAP_ID] ?: 0) > 0 }
        (listOfNotNull(foods.firstOrNull(), soap) + foods.drop(1)).distinct()
    }
    // Nothing in the tray is worth carrying to an egg, a sleeper or a pet that is gone.
    val tray = if (pet.isDead || pet.isEgg || pet.isSleeping) emptyList() else carriable

    val onDragStart: (Item, Offset) -> Unit = { item, at ->
        dragState.item = item
        dragState.position = at
        dragState.overStage = stageBounds.value.contains(at)
        dragState.progress = 0f
    }
    val onDragMove: (Offset, Offset) -> Unit = { at, delta ->
        val carried = dragState.item
        if (carried != null) {
            val over = stageBounds.value.contains(at)
            // A tick as the item crosses into the room is the only cue that the drop will land.
            if (over && !dragState.overStage && hapticsOn) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            dragState.overStage = over
            dragState.position = at
            if (over && carried.id == SOAP_ID) {
                dragState.progress =
                    (dragState.progress + (abs(delta.x) + abs(delta.y)) / rubDistancePx).coerceAtMost(1f)
                // A full scrub pays off under the finger, without waiting for the release.
                if (dragState.progress >= 1f) {
                    dragState.item = null
                    viewModel.bathe()
                }
            }
        }
    }
    val onDragEnd: () -> Unit = {
        val carried = dragState.item
        val landed = dragState.overStage
        dragState.item = null
        if (carried != null && landed) {
            if (carried.id == SOAP_ID) viewModel.bathe() else viewModel.feed(carried.id)
        }
    }
    val onDragCancel: () -> Unit = { dragState.item = null }

    val careStrip: @Composable (Modifier) -> Unit = { stripModifier ->
        CareStrip(
            pet = pet,
            quick = quickCare,
            tray = tray,
            drag = dragState,
            onTapItem = { item -> if (item.id == SOAP_ID) viewModel.bathe() else viewModel.feed(item.id) },
            onDragStart = onDragStart,
            onDragMove = onDragMove,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            modifier = stripModifier,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
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
                TwoPaneHome(
                    pet = pet,
                    config = ui.config,
                    window = window,
                    actions = actions,
                    stage = stage,
                    careStrip = careStrip,
                )
            } else {
                StackedHome(
                    pet = pet,
                    config = ui.config,
                    actions = actions,
                    stage = stage,
                    careStrip = careStrip,
                )
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

        // Last, so the carried item passes over every overlay — and takes no input of its own.
        DragLayer(
            drag = dragState,
            stage = stageBounds.value,
            origin = rootOrigin,
            modifier = Modifier.fillMaxSize(),
        )
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
                    // Not `feed`. The sheet lists medicines too, and feeding a pill consumed the
                    // dose without curing anything. See CareActions.use.
                    viewModel.useItem(id)
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
 * Resolves the most urgent need into one concrete, nameable action. Returns null when there is
 * genuinely nothing pressing — an affordance that fires blanks teaches players to ignore it.
 */
private fun quickCareFor(pet: PetState, viewModel: PetViewModel, onOpen: (String) -> Unit): QuickCare? {
    if (pet.isDead || pet.isEgg || pet.isSleeping) return null
    fun owned(id: String): Boolean = (pet.inventory[id] ?: 0) > 0

    if (pet.isSick) {
        val dose = ItemCatalog.ofKind(ItemKind.MEDICINE)
            .filter { it.health > 0f && owned(it.id) }
            .maxByOrNull { it.health }
        return if (dose != null) {
            QuickCare(
                label = "Give ${dose.name}",
                reason = "${pet.name} is sick",
                icon = Icons.Filled.Medication,
                accent = NeoColors.StatHealth,
            ) { viewModel.useMedicine(dose.id) }
        } else {
            QuickCare(
                label = "Buy medicine",
                reason = "${pet.name} is sick and the cabinet is empty",
                icon = Icons.Filled.ShoppingBag,
                accent = NeoColors.StatHealth,
            ) { onOpen(Routes.SHOP) }
        }
    }

    return when (CareActions.topNeed(pet)) {
        "Hungry" -> {
            val food = ItemCatalog.foods.filter { owned(it.id) }.maxByOrNull { it.satiety }
            if (food != null) {
                QuickCare(
                    label = "Feed ${food.name}",
                    reason = "Satiety ${pet.stats.satiety.roundToInt()}",
                    icon = Icons.Filled.Restaurant,
                    accent = NeoColors.StatSatiety,
                ) { viewModel.feed(food.id) }
            } else {
                QuickCare(
                    label = "Buy food",
                    reason = "Satiety ${pet.stats.satiety.roundToInt()} and the pantry is empty",
                    icon = Icons.Filled.ShoppingBag,
                    accent = NeoColors.StatSatiety,
                ) { onOpen(Routes.SHOP) }
            }
        }

        "Dirty" -> when {
            pet.poops > 0 -> QuickCare(
                label = "Clean the room",
                reason = "${pet.poops} mess${if (pet.poops > 1) "es" else ""} on the floor",
                icon = Icons.Filled.CleaningServices,
                accent = NeoColors.StatHygiene,
            ) { viewModel.cleanRoom() }

            owned(SOAP_ID) -> QuickCare(
                label = "Scrub with Bubble Soap",
                reason = "Hygiene ${pet.stats.hygiene.roundToInt()}",
                icon = Icons.Filled.CleaningServices,
                accent = NeoColors.StatHygiene,
            ) { viewModel.bathe() }

            else -> QuickCare(
                label = "Clean the room",
                reason = "Hygiene ${pet.stats.hygiene.roundToInt()}",
                icon = Icons.Filled.CleaningServices,
                accent = NeoColors.StatHygiene,
            ) { viewModel.cleanRoom() }
        }

        "Sleepy" -> QuickCare(
            label = "Tuck ${pet.name} in",
            reason = "Energy ${pet.stats.energy.roundToInt()}",
            icon = Icons.Filled.Lightbulb,
            accent = NeoColors.StatEnergy,
        ) { viewModel.putToSleep() }

        // Too tired or too ill to play is still boredom; petting is the one thing always accepted.
        "Bored" -> if (CareActions.canPlay(pet) == null) {
            QuickCare(
                label = "Play a game",
                reason = "Happiness ${pet.stats.happiness.roundToInt()}",
                icon = Icons.Filled.SportsEsports,
                accent = NeoColors.NeonCyan,
            ) { onOpen(Routes.GAMES) }
        } else {
            QuickCare(
                label = "Pet ${pet.name}",
                reason = "Happiness ${pet.stats.happiness.roundToInt()}, not up for a game",
                icon = Icons.Filled.Favorite,
                accent = NeoColors.StatHappiness,
            ) { viewModel.petPet() }
        }

        else -> null
    }
}

/**
 * Portrait phone: bar, scene, care strip, meters, dock. The meters and dock are capped and scroll
 * internally, so a small phone — or a 1.3x font scale — eats into the chrome rather than the pet.
 */
@Composable
private fun StackedHome(
    pet: PetState,
    config: GameConfig,
    actions: List<HomeAction>,
    stage: @Composable (Modifier) -> Unit,
    careStrip: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The care strip counts against the same budget, so the scene keeps the share it had.
        val chromeMax = (maxHeight * CHROME_MAX_FRACTION - CareStripHeight).coerceAtLeast(120.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(pet = pet, config = config)

            stage(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )

            careStrip(Modifier.fillMaxWidth())

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
 *
 * The care strip stays under the scene rather than in the panel: it is a drag source, and a drag
 * that starts inside a scrolling column is a fight between the finger and the scroll.
 */
@Composable
private fun TwoPaneHome(
    pet: PetState,
    config: GameConfig,
    window: WindowSize,
    actions: List<HomeAction>,
    stage: @Composable (Modifier) -> Unit,
    careStrip: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelFraction = if (window.isExpandedWidth) 0.34f else 0.32f
        // The panel is sized for its content, but the scene always keeps the larger half.
        val panelWidth = (maxWidth * panelFraction)
            .coerceIn(200.dp, 360.dp)
            .coerceAtMost(maxWidth * 0.45f)
        val stripMax = maxHeight * (1f - PET_MIN_HEIGHT_FRACTION)

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                stage(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                careStrip(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = stripMax),
                )
            }
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
            // Stacked, not side by side: the level pill is a fixed 96dp and the coin pill grew
            // when its count moved to block glyphs, so at four figures the two no longer fit
            // across a panel that floors at 200dp — and the digits would clip, silently.
            LevelPill(pet.level, pet.xp, pet.xpForNextLevel)
            Spacer(Modifier.height(4.dp))
            CoinPill(pet.coins)
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
 * The quick-care button and the carry tray on one line.
 *
 * They belong together: both answer "what does it need?", and both live outside every scrolling
 * container so a drag that starts here is never mistaken for a scroll. How many chips fit is a
 * function of the width — the button keeps the rest and ellipsises rather than pushing them off.
 */
@Composable
private fun CareStrip(
    pet: PetState,
    quick: QuickCare?,
    tray: List<Item>,
    drag: TrayDragState,
    onTapItem: (Item) -> Unit,
    onDragStart: (Item, Offset) -> Unit,
    onDragMove: (Offset, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        val reserved = if (quick != null) 148.dp else 8.dp
        val roomForChips = ((maxWidth - reserved) / (TrayChipSize + 6.dp)).toInt()
        val visible = if (tray.isEmpty()) emptyList() else tray.take(roomForChips.coerceIn(1, 4))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (quick != null) {
                QuickCareButton(care = quick, modifier = Modifier.weight(1f))
            } else {
                CalmStatus(pet = pet, modifier = Modifier.weight(1f))
            }
            visible.forEach { item ->
                TrayChip(
                    item = item,
                    count = pet.inventory[item.id] ?: 0,
                    petName = pet.name,
                    carried = drag.item?.id == item.id,
                    onTap = { onTapItem(item) },
                    onStart = { at -> onDragStart(item, at) },
                    onMove = onDragMove,
                    onEnd = onDragEnd,
                    onCancel = onDragCancel,
                )
            }
        }
    }
}

@Composable
private fun QuickCareButton(care: QuickCare, modifier: Modifier = Modifier) {
    PixelButton(
        onClick = care.onAct,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "${care.label}. ${care.reason}"
        },
        accent = care.accent,
        // Mixed most of the way to the console screen: the accent is a signal, not a slab.
        fill = lerp(care.accent, NeoColors.SurfaceDark, 0.76f),
        background = NeoColors.SurfaceDark,
        contentPadding = PaddingValues(horizontal = pixelUnits(2), vertical = pixelUnits(1)),
    ) {
        Icon(care.icon, contentDescription = null, tint = care.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = care.label,
                style = MaterialTheme.typography.labelLarge,
                color = NeoColors.OnDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = care.reason,
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.OnDarkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What the strip says when nothing is wrong. Silence would read as a screen that failed to load. */
@Composable
private fun CalmStatus(pet: PetState, modifier: Modifier = Modifier) {
    val text = when {
        pet.isEgg -> "The egg is warming up."
        pet.isSleeping -> "${pet.name} is asleep. Nothing needed."
        pet.isDead -> "${pet.name} is no longer with us."
        else -> "Nothing urgent. ${pet.name} is doing fine."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = NeoColors.OnDarkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One carriable item. Tapping serves it — that path has to keep working for anyone who cannot
 * drag — and dragging it onto the scene does the same thing with your own hand.
 */
@Composable
private fun TrayChip(
    item: Item,
    count: Int,
    petName: String,
    carried: Boolean,
    onTap: () -> Unit,
    onStart: (Offset) -> Unit,
    onMove: (Offset, Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = Color(item.tint)
    val washes = item.id == SOAP_ID
    val readOut = if (washes) {
        "Wash $petName with ${item.name}, $count left. Or drag it onto $petName."
    } else {
        "Feed ${item.name} to $petName, $count left. Or drag it onto $petName."
    }
    // The chip's own position, so a drag can be reported in the coordinates the scene is measured in.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)

    Box(
        modifier = modifier
            .size(TrayChipSize)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(item.id) {
                detectDragGestures(
                    onDragStart = { local -> start(origin + local) },
                    onDrag = { change, amount -> move(origin + change.position, amount) },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                )
            }
            .semantics(mergeDescendants = true) { contentDescription = readOut }
            .clickable(role = Role.Button, onClick = onTap)
            .pixelSurface(
                fill = lerp(tint, NeoColors.SurfaceDark, 0.78f),
                accent = tint,
                bevel = if (carried) PixelBevel.PRESSED else PixelBevel.RAISED,
                background = NeoColors.SurfaceDark,
            )
            .padding(pixelUnits(2)),
        contentAlignment = Alignment.Center,
    ) {
        ItemIcon(
            iconKey = item.iconKey,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                // While it is in the air it should not also be sitting in the tray.
                .alpha(if (carried) 0.2f else 1f),
            variant = item.id,
        )
        if (count > 1) {
            PixelBadge(
                text = "$count",
                color = NeoColors.ChassisLight,
                background = NeoColors.SurfaceDark,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * The carried item and the landing zone it is over. Purely a read-out: it holds no pointer input
 * of its own, so every gesture the scene already understood still reaches the scene.
 */
@Composable
private fun DragLayer(
    drag: TrayDragState,
    stage: Rect,
    origin: Offset,
    modifier: Modifier = Modifier,
) {
    val item = drag.item ?: return
    val tint = Color(item.tint)
    val washes = item.id == SOAP_ID
    val density = LocalDensity.current
    val ghostHalf = with(density) { (DragGhostSize / 2).toPx() }

    Box(modifier = modifier.clearAndSetSemantics { }) {
        if (drag.overStage && !stage.isEmpty) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((stage.left - origin.x).roundToInt(), (stage.top - origin.y).roundToInt())
                    }
                    .size(
                        width = with(density) { stage.width.toDp() },
                        height = with(density) { stage.height.toDp() },
                    )
                    .background(tint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                PixelPanel(
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .widthIn(max = 220.dp),
                    fill = NeoColors.SurfaceCard,
                    accent = tint,
                    background = NeoColors.SurfaceDark,
                    contentPadding = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
                ) {
                    Text(
                        text = if (washes) "Rub to scrub" else "Let go to feed",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeoColors.OnDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (washes) {
                        Spacer(Modifier.height(pixelUnits(1)))
                        PixelBar(
                            fraction = drag.progress,
                            color = tint,
                            segments = 12,
                            height = pixelUnits(3),
                            trackColor = NeoColors.SurfaceDark,
                            background = NeoColors.SurfaceDark,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                // Read inside the layout lambda, so the finger moves the ghost without recomposing.
                .offset {
                    IntOffset(
                        (drag.position.x - origin.x - ghostHalf).roundToInt(),
                        (drag.position.y - origin.y - ghostHalf).roundToInt(),
                    )
                }
                .size(DragGhostSize),
        ) {
            ItemIcon(item.iconKey, tint, Modifier.fillMaxSize(), variant = item.id)
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
                    PixelPanel(
                        onClick = { onFeed(item.id) },
                        accent = Color(item.tint),
                        background = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .width(104.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${item.name}, ${pet.inventory[item.id] ?: 0} left"
                            },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(item.tint).copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                ItemIcon(item.iconKey, Color(item.tint), Modifier.size(44.dp), variant = item.id)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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
    PixelPanel(
        modifier = modifier.padding(horizontal = 16.dp),
        fill = NeoColors.SurfaceCard,
        accent = NeoColors.NeonYellow,
        background = NeoColors.SurfaceDark,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        "Drag food from the tray onto $petName to feed it, and rub the soap over it to wash." to "Nice",
        "Feed, play and clean to raise it well — how you care decides what it evolves into." to "Start",
    )
    Box(
        modifier = modifier
            .background(Color(0xCC08090F))
            .clickable { if (step < steps.lastIndex) step += 1 else onDone() },
        contentAlignment = Alignment.Center,
    ) {
        PixelPanel(
            fill = NeoColors.SurfaceCard,
            accent = NeoColors.NeonCyan,
            background = NeoColors.ChassisBlack,
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
                Box(
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .clickable(role = Role.Button) { onDone() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeoColors.OnDarkMuted,
                    )
                }
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
            .background(Color(0xCC08090F))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        PixelPanel(
            fill = NeoColors.SurfaceCard,
            accent = NeoColors.NeonCyan,
            background = NeoColors.ChassisBlack,
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
