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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.ItemCatalog
import com.neopal.pet.domain.ItemKind
import com.neopal.pet.domain.PetState
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.Routes
import com.neopal.pet.ui.art.ItemIcon
import com.neopal.pet.ui.components.CoinPill
import com.neopal.pet.ui.components.ConsoleFrame
import com.neopal.pet.ui.components.FaceButton
import com.neopal.pet.ui.components.LevelPill
import com.neopal.pet.ui.components.ActionButton
import com.neopal.pet.ui.components.PetStage
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.components.ToastBanner
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/**
 * The main screen: the console chassis, the live pet, its meters, and the action dock.
 * Everything the player needs minute to minute is one tap away from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: PetViewModel, onOpen: (String) -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    var showFeedSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(pet.isDead) {
        if (pet.isDead) onOpen(Routes.MEMORIAL)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ConsoleFrame(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
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
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(pet = pet, config = ui.config)

                // The live scene fills most of the screen.
                PetStage(
                    state = pet,
                    config = ui.config,
                    action = ui.animation,
                    actionId = ui.animationId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    onTapPet = { viewModel.petPet() },
                    onLongPressPet = { viewModel.snapshot("${pet.name}, ${pet.stage.displayName}") },
                )

                StatsStrip(pet)
                ActionDock(
                    pet = pet,
                    onFeed = { showFeedSheet = true },
                    onClean = viewModel::cleanRoom,
                    onPlay = { onOpen(Routes.GAMES) },
                    onMedicine = { viewModel.useMedicine() },
                    onLights = viewModel::toggleLights,
                    onPraise = viewModel::praise,
                    onScold = viewModel::scold,
                    onShop = { onOpen(Routes.SHOP) },
                    onAlbum = { onOpen(Routes.ALBUM) },
                    onAchievements = { onOpen(Routes.ACHIEVEMENTS) },
                    onSettings = { onOpen(Routes.SETTINGS) },
                )
            }
        }

        ToastBanner(
            message = ui.toast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 70.dp),
        )

        ui.achievementBanner?.let { achievement ->
            AchievementBanner(
                title = achievement.title,
                description = achievement.description,
                reward = achievement.rewardCoins,
                onDismiss = viewModel::dismissAchievementBanner,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 70.dp),
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

@Composable
private fun TopBar(pet: PetState, config: com.neopal.pet.domain.GameConfig) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pet.name, style = MaterialTheme.typography.titleMedium, color = NeoColors.OnDark)
            Text(
                text = "${pet.stage.displayName} · ${pet.branch.displayName} · day ${pet.ageInPetDays(config)}",
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.OnDarkMuted,
            )
        }
        LevelPill(pet.level, pet.xp, pet.xpForNextLevel)
        Spacer(Modifier.width(8.dp))
        CoinPill(pet.coins)
    }
}

@Composable
private fun StatsStrip(pet: PetState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatBar("FOOD", pet.stats.satiety, NeoColors.StatSatiety, Modifier.weight(1f))
        StatBar("MOOD", pet.stats.happiness, NeoColors.StatHappiness, Modifier.weight(1f))
        StatBar("ENERGY", pet.stats.energy, NeoColors.StatEnergy, Modifier.weight(1f))
        StatBar("CLEAN", pet.stats.hygiene, NeoColors.StatHygiene, Modifier.weight(1f))
        StatBar("HP", pet.stats.health, NeoColors.StatHealth, Modifier.weight(1f))
    }
}

@Composable
private fun ActionDock(
    pet: PetState,
    onFeed: () -> Unit,
    onClean: () -> Unit,
    onPlay: () -> Unit,
    onMedicine: () -> Unit,
    onLights: () -> Unit,
    onPraise: () -> Unit,
    onScold: () -> Unit,
    onShop: () -> Unit,
    onAlbum: () -> Unit,
    onAchievements: () -> Unit,
    onSettings: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            ActionButton("Feed", Icons.Filled.Restaurant, NeoColors.StatSatiety, onFeed, enabled = !pet.isDead)
        }
        item {
            ActionButton(
                label = "Clean",
                icon = Icons.Filled.CleaningServices,
                accent = NeoColors.StatHygiene,
                onClick = onClean,
                enabled = !pet.isDead,
                badge = pet.poops,
            )
        }
        item {
            ActionButton("Play", Icons.Filled.SportsEsports, NeoColors.NeonCyan, onPlay, enabled = CareActions.canPlay(pet) == null)
        }
        item {
            ActionButton(
                label = "Medicine",
                icon = Icons.Filled.Medication,
                accent = NeoColors.StatHealth,
                onClick = onMedicine,
                enabled = !pet.isDead,
                badge = if (pet.isSick) 1 else 0,
            )
        }
        item {
            ActionButton(
                label = if (pet.lightsOff) "Lights on" else "Lights off",
                icon = Icons.Filled.Lightbulb,
                accent = NeoColors.StatEnergy,
                onClick = onLights,
                enabled = !pet.isDead,
            )
        }
        item { ActionButton("Praise", Icons.Filled.ThumbUp, NeoColors.StatBond, onPraise, enabled = !pet.isDead) }
        item { ActionButton("Scold", Icons.Filled.ThumbDown, NeoColors.StatDiscipline, onScold, enabled = !pet.isDead) }
        item { ActionButton("Shop", Icons.Filled.ShoppingBag, NeoColors.NeonPurple, onShop) }
        item { ActionButton("Album", Icons.Filled.PhotoCamera, NeoColors.NeonYellow, onAlbum) }
        item { ActionButton("Awards", Icons.Filled.EmojiEvents, NeoColors.NeonGreen, onAchievements) }
        item { ActionButton("Settings", Icons.Filled.Settings, NeoColors.OnDarkMuted, onSettings) }
    }
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
                                ItemIcon(item.iconKey, androidx.compose.ui.graphics.Color(item.tint), Modifier.size(44.dp))
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
