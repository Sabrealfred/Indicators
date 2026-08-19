@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.Item
import com.neopal.pet.domain.ItemCatalog
import com.neopal.pet.domain.ItemKind
import com.neopal.pet.domain.PetState
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.ItemIcon
import com.neopal.pet.ui.components.CoinPill
import com.neopal.pet.ui.theme.NeoColors

/** Shop, wardrobe and room picker in one place; owned cosmetics switch to "equip". */
@Composable
fun ShopScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    var tab by remember { mutableIntStateOf(0) }
    var inspecting by remember { mutableStateOf<Item?>(null) }
    val tabs = listOf("Food", "Care", "Toys", "Hats", "Rooms")

    val items = when (tab) {
        0 -> ItemCatalog.foods
        1 -> ItemCatalog.ofKind(ItemKind.MEDICINE)
        2 -> ItemCatalog.ofKind(ItemKind.TOY)
        3 -> ItemCatalog.hats
        else -> ItemCatalog.rooms
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("SHOP", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
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
        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items) { item ->
                ShopCard(item = item, pet = pet, onClick = { inspecting = item })
            }
        }
    }

    inspecting?.let { item ->
        ItemDetailDialog(
            item = item,
            pet = pet,
            onDismiss = { inspecting = null },
            onBuy = { viewModel.buy(item.id) },
            onUse = {
                when (item.kind) {
                    ItemKind.HAT -> viewModel.equipHat(if (pet.equippedHat == item.id) null else item.id)
                    ItemKind.ROOM -> viewModel.setRoom(item.id)
                    ItemKind.MEDICINE -> viewModel.useMedicine(item.id)
                    else -> viewModel.feed(item.id)
                }
            },
        )
    }
}

@Composable
private fun ShopCard(item: Item, pet: PetState, onClick: () -> Unit) {
    val owned = pet.inventory[item.id] ?: 0
    val equipped = pet.equippedHat == item.id || pet.roomTheme == item.id
    val canAfford = pet.coins >= item.price
    val tint = Color(item.tint)

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                ItemIcon(item.iconKey, tint, Modifier.size(56.dp), variant = item.id)
                if (equipped) {
                    Text(
                        "EQUIPPED",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeoColors.NeonGreen,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        item.isCosmetic && owned > 0 -> if (equipped) "In use" else "Tap to equip"
                        else -> "${item.price} coins"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (canAfford || owned > 0) NeoColors.NeonCyan else NeoColors.NeonRed,
                )
                Spacer(Modifier.weight(1f))
                if (owned > 0 && !item.isCosmetic) {
                    Text("x$owned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

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
    val effects = listOfNotNull(
        item.satiety.takeIf { it != 0f }?.let { "Satiety" to it },
        item.happiness.takeIf { it != 0f }?.let { "Happiness" to it },
        item.energy.takeIf { it != 0f }?.let { "Energy" to it },
        item.hygiene.takeIf { it != 0f }?.let { "Hygiene" to it },
        item.health.takeIf { it != 0f }?.let { "Health" to it },
        item.bond.takeIf { it != 0f }?.let { "Bond" to it },
        item.weight.takeIf { it != 0f }?.let { "Weight" to it },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                Text(item.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (effects.isEmpty()) {
                    Text(
                        "Pure decoration. It changes nothing but how your pet looks.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    effects.forEach { (label, value) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = (if (value > 0) "+" else "") + value.toInt(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (value > 0) NeoColors.NeonGreen else NeoColors.NeonRed,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        item.isCosmetic && owned > 0 -> if (equipped) "In use" else "Owned"
                        owned > 0 -> "You have $owned"
                        else -> "${item.price} coins · you have ${pet.coins}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canAfford || owned > 0) NeoColors.NeonCyan else NeoColors.NeonRed,
                )
            }
        },
        confirmButton = {
            when {
                owned > 0 -> TextButton(onClick = { onUse(); onDismiss() }) {
                    Text(if (item.isCosmetic) (if (equipped) "Take off" else "Wear it") else "Use it")
                }
                canAfford -> TextButton(onClick = { onBuy(); onDismiss() }) { Text("Buy") }
                else -> TextButton(onClick = onDismiss, enabled = false) { Text("Not enough coins") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
