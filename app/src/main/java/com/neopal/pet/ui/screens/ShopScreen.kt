@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.Item
import com.neopal.pet.domain.ItemCatalog
import com.neopal.pet.domain.ItemKind
import com.neopal.pet.domain.PetState
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.drawItem
import com.neopal.pet.ui.components.CoinPill
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberPixelShinePhase
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.abs

/** Shop, wardrobe and room picker in one place; owned cosmetics switch to "equip". */
@Composable
fun ShopScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    var tab by remember { mutableIntStateOf(0) }
    var inspecting by remember { mutableStateOf<Item?>(null) }
    val tabs = listOf(
        stringResource(R.string.shop_tab_food),
        stringResource(R.string.shop_tab_care),
        stringResource(R.string.shop_tab_toys),
        stringResource(R.string.shop_tab_hats),
        stringResource(R.string.shop_tab_rooms),
    )

    val items = when (tab) {
        0 -> ItemCatalog.foods
        1 -> ItemCatalog.ofKind(ItemKind.MEDICINE)
        2 -> ItemCatalog.ofKind(ItemKind.TOY)
        3 -> ItemCatalog.hats
        else -> ItemCatalog.rooms
    }

    // The dearest thing on this shelf the player can afford and does not already own: the one
    // tile a glint is worth spending on, and it climbs the shelf as coins come in. A shop where
    // every tile sparkles points at nothing.
    val featured = remember(items, pet.coins, pet.inventory) {
        items.filter { it.price > 0 && pet.coins >= it.price && (pet.inventory[it.id] ?: 0) == 0 }
            .maxByOrNull { it.price }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back), tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.shop_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            CoinPill(pet.coins)
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(3)))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
            horizontalArrangement = Arrangement.spacedBy(pixelUnits(3)),
        ) {
            items(items) { item ->
                ShopCard(
                    item = item,
                    pet = pet,
                    shine = item.id == featured?.id,
                    onClick = { inspecting = item },
                )
            }
        }
    }

    inspecting?.let { item ->
        ItemDetailDialog(
            item = item,
            pet = pet,
            onDismiss = { inspecting = null },
            onBuy = { viewModel.buy(item.id) },
            // One router, shared with the pantry sheet. This screen used to send soap to
            // useMedicine, which applies health and happiness and not hygiene — so soap washed
            // nothing and cured illness instead. See CareActions.use.
            onUse = { viewModel.useItem(item.id) },
        )
    }
}

@Composable
private fun ShopCard(item: Item, pet: PetState, shine: Boolean, onClick: () -> Unit) {
    val owned = pet.inventory[item.id] ?: 0
    val equipped = pet.equippedHat == item.id || pet.roomTheme == item.id
    val canAfford = pet.coins >= item.price
    // Each item frames itself in its own colour, so a shelf of them reads as distinct things.
    val tint = Color(item.tint)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    // On the kit's shine clock, so every glint in the app sweeps together. Read inside the draw
    // lambda below, never here, where it would recompose the whole card sixty times a second.
    val shinePhase = rememberPixelShinePhase(enabled = shine)

    PixelPanel(
        modifier = Modifier.fillMaxWidth(),
        fill = fill,
        accent = tint,
        contentPadding = PaddingValues(pixelUnits(2)),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pixelUnits(18))
                // The icon sits in a well pressed into the card, the way a cartridge sits in a slot.
                .pixelSurface(
                    fill = lerp(fill, tint, 0.24f),
                    accent = tint,
                    bevel = PixelBevel.PRESSED,
                )
                .padding(pixelUnits(3)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(pixelUnits(14))) {
                drawItem(item.iconKey, tint, variant = item.id, shinePhase = shinePhase.value)
            }
            if (equipped) {
                PixelBadge(
                    text = stringResource(R.string.shop_equipped),
                    color = NeoColors.NeonGreen,
                    contentColor = NeoColors.ChassisBlack,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(pixelUnits(2)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    item.isCosmetic && owned > 0 ->
                        stringResource(if (equipped) R.string.shop_in_use else R.string.shop_tap_to_equip)
                    else -> stringResource(R.string.shop_price, item.price)
                },
                style = MaterialTheme.typography.labelMedium,
                // Straight neon cyan/red land near 2:1 on the light theme; both have AA-safe twins.
                color = if (canAfford || owned > 0) NeoAccents.cyan else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (owned > 0 && !item.isCosmetic) {
                Text(stringResource(R.string.shop_owned_count, owned), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * One line of the effect table. A stat that lives on a 0..100 scale gets a meter as well as a
 * number; weight has no ceiling to fill, so it stays a bare delta.
 */
private data class ItemEffect(val label: String, val value: Float, val statColor: Color?)

/**
 * What an item actually does, before you spend on it. Buying blind is the fastest way to
 * make a shop feel like a slot machine.
 */
@Composable
private fun ItemDetailDialog(
    item: Item,
    pet: PetState,
    onDismiss: () -> Unit,
    onBuy: () -> Unit,
    onUse: () -> Unit,
) {
    val owned = pet.inventory[item.id] ?: 0
    val equipped = pet.equippedHat == item.id || pet.roomTheme == item.id
    val canAfford = pet.coins >= item.price
    val tint = Color(item.tint)
    // The same seven names the stats screen already had resources for; they were spelled out
    // again here in English and are now the one set of words in both places.
    val effects = listOfNotNull(
        item.satiety.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_satiety), it, NeoColors.StatSatiety) },
        item.happiness.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_happiness), it, NeoColors.StatHappiness) },
        item.energy.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_energy), it, NeoColors.StatEnergy) },
        item.hygiene.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_hygiene), it, NeoColors.StatHygiene) },
        item.health.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_health), it, NeoColors.StatHealth) },
        item.bond.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stat_bond), it, NeoColors.StatBond) },
        item.weight.takeIf { it != 0f }?.let { ItemEffect(stringResource(R.string.stats_weight), it, null) },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        // Squared off and filled like the panels behind it; a 28dp radius reads as another app.
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(item.name) },
        text = {
            Column {
                Text(item.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(pixelUnits(3)))
                if (effects.isEmpty()) {
                    Text(
                        stringResource(R.string.shop_cosmetic_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    effects.forEach { effect -> EffectRow(effect) }
                }
                Spacer(Modifier.height(pixelUnits(3)))
                Text(
                    text = when {
                        item.isCosmetic && owned > 0 ->
                            stringResource(if (equipped) R.string.shop_in_use else R.string.shop_owned)
                        owned > 0 -> stringResource(R.string.shop_you_have, owned)
                        else -> stringResource(R.string.shop_price_and_purse, item.price, pet.coins)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canAfford || owned > 0) NeoAccents.cyan else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            when {
                owned > 0 -> PixelButton(
                    onClick = { onUse(); onDismiss() },
                    accent = tint,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        stringResource(
                            when {
                                !item.isCosmetic -> R.string.shop_use_it
                                equipped -> R.string.shop_take_off
                                else -> R.string.shop_wear_it
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                canAfford -> PixelButton(
                    onClick = { onBuy(); onDismiss() },
                    accent = tint,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        stringResource(R.string.shop_buy),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                else -> PixelButton(
                    onClick = onDismiss,
                    enabled = false,
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        stringResource(R.string.shop_not_enough_coins),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        dismissButton = {
            PixelButton(
                onClick = onDismiss,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        },
    )
}

@Composable
private fun EffectRow(effect: ItemEffect) {
    val signed = (if (effect.value > 0) "+" else "") + effect.value.toInt()
    // Label, number and meter are one fact; three fragments is what TalkBack reads otherwise.
    val readOut = stringResource(R.string.cd_item_effect, effect.label, signed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = pixelUnits(1))
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                effect.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = signed,
                style = MaterialTheme.typography.labelMedium,
                color = if (effect.value > 0) NeoAccents.green else MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
        if (effect.statColor != null) {
            Spacer(Modifier.height(pixelUnits(1)))
            PixelBar(
                fraction = abs(effect.value) / 100f,
                color = if (effect.value > 0) effect.statColor else NeoColors.NeonRed,
                segments = 10,
                height = pixelUnits(3),
            )
        }
    }
}
