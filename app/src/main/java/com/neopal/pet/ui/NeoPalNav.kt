package com.neopal.pet.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.neopal.pet.ui.screens.AchievementsScreen
import com.neopal.pet.ui.screens.AlbumScreen
import com.neopal.pet.ui.screens.BootScreen
import com.neopal.pet.ui.screens.GamesScreen
import com.neopal.pet.ui.screens.HomeScreen
import com.neopal.pet.ui.screens.MemorialScreen
import com.neopal.pet.ui.screens.NewGameScreen
import com.neopal.pet.ui.screens.SettingsScreen
import com.neopal.pet.ui.screens.ShopScreen
import com.neopal.pet.ui.screens.StatsScreen
import com.neopal.pet.ui.games.CatchGameScreen
import com.neopal.pet.ui.games.MemoryGameScreen
import com.neopal.pet.ui.games.RhythmGameScreen

object Routes {
    const val BOOT = "boot"
    const val NEW_GAME = "new_game"
    const val HOME = "home"
    const val STATS = "stats"
    const val SHOP = "shop"
    const val GAMES = "games"
    const val GAME_RHYTHM = "game_rhythm"
    const val GAME_MEMORY = "game_memory"
    const val GAME_CATCH = "game_catch"
    const val ALBUM = "album"
    const val ACHIEVEMENTS = "achievements"
    const val SETTINGS = "settings"
    const val MEMORIAL = "memorial"
}

/** Root of the app: one NavHost, one shared ViewModel, console-style slide transitions. */
@Composable
fun NeoPalApp(viewModel: PetViewModel = viewModel(factory = PetViewModel.Factory)) {
    val navController = rememberNavController()
    val ui by viewModel.ui.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.BOOT,
            enterTransition = { slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220)) },
            exitTransition = { slideOutHorizontally(tween(280)) { -it / 6 } + fadeOut(tween(180)) },
            popEnterTransition = { slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(220)) },
            popExitTransition = { slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(180)) },
        ) {
            composable(Routes.BOOT) {
                BootScreen(
                    loading = ui.loading,
                    hasSave = ui.pet != null,
                    onContinue = { hasSave ->
                        val target = if (hasSave) Routes.HOME else Routes.NEW_GAME
                        navController.navigate(target) {
                            popUpTo(Routes.BOOT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.NEW_GAME) {
                NewGameScreen(
                    isNextGeneration = ui.pet?.isDead == true,
                    generation = ui.pet?.generation ?: 1,
                    onStart = { name, species ->
                        if (ui.pet?.isDead == true) viewModel.startNextGeneration(name, species)
                        else viewModel.startNewGame(name, species)
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.NEW_GAME) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpen = { route -> navController.navigate(route) },
                )
            }
            composable(Routes.STATS) { StatsScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.SHOP) { ShopScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.GAMES) {
                GamesScreen(
                    viewModel = viewModel,
                    onPlay = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.GAME_RHYTHM) {
                RhythmGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.GAME_MEMORY) {
                MemoryGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.GAME_CATCH) {
                CatchGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.ALBUM) { AlbumScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.ACHIEVEMENTS) { AchievementsScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onResetToNewGame = {
                        viewModel.resetEverything()
                        navController.navigate(Routes.NEW_GAME) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                )
            }
            composable(Routes.MEMORIAL) {
                MemorialScreen(
                    viewModel = viewModel,
                    onStartNextGeneration = {
                        navController.navigate(Routes.NEW_GAME) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
