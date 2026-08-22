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
import com.neopal.pet.ui.screens.ChronicleScreen
import com.neopal.pet.ui.screens.ColonyScreen
import com.neopal.pet.ui.screens.GamesScreen
import com.neopal.pet.ui.screens.HomeScreen
import com.neopal.pet.ui.screens.MemorialScreen
import com.neopal.pet.ui.screens.MindScreen
import com.neopal.pet.ui.screens.MissionsScreen
import com.neopal.pet.ui.screens.NewGameScreen
import com.neopal.pet.ui.screens.SettingsScreen
import com.neopal.pet.ui.screens.ShopScreen
import com.neopal.pet.ui.screens.StatsScreen
import com.neopal.pet.ui.screens.TalkScreen
import com.neopal.pet.ui.screens.UpdateScreen
import com.neopal.pet.ui.games.CatchGameScreen
import com.neopal.pet.ui.games.DuetGameScreen
import com.neopal.pet.ui.games.FetchGameScreen
import com.neopal.pet.ui.games.HideAndSeekGameScreen
import com.neopal.pet.ui.games.MemoryGameScreen
import com.neopal.pet.ui.games.PuzzleGameScreen
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
    const val GAME_HIDE = "game_hide"
    const val GAME_FETCH = "game_fetch"
    const val GAME_DUET = "game_duet"
    const val GAME_PUZZLE = "game_puzzle"
    const val ALBUM = "album"
    const val CHRONICLE = "chronicle"
    const val ACHIEVEMENTS = "achievements"
    const val MISSIONS = "missions"
    const val MIND = "mind"
    const val COLONY = "colony"
    const val TALK = "talk"
    const val SETTINGS = "settings"
    const val UPDATE = "update"
    const val MEMORIAL = "memorial"
}

/** Root of the app: one NavHost, one shared ViewModel, console-style slide transitions. */
@Composable
fun NeoPalApp(viewModel: PetViewModel = viewModel(factory = PetViewModel.Factory)) {
    val navController = rememberNavController()
    val ui by viewModel.ui.collectAsState()
    // Deliberately above the NavHost, so it is scoped to the activity rather than to the update
    // route: created inside the route it would be cleared on every back press, and clearing it
    // cancels a download in flight. See UpdateViewModel's own note.
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)

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
                    // Only offered when the last life is over: these are its children, and they
                    // are not candidates for anything while it is still using the tank.
                    heirs = if (ui.pet?.isDead == true) viewModel.heirs() else emptyList(),
                    onStart = { name, species, heirId ->
                        if (ui.pet?.isDead == true) viewModel.startNextGeneration(name, species, heirId)
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
            composable(Routes.GAME_HIDE) {
                HideAndSeekGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.GAME_FETCH) {
                FetchGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.GAME_DUET) {
                DuetGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.GAME_PUZZLE) {
                PuzzleGameScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.ALBUM) { AlbumScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.CHRONICLE) { ChronicleScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.ACHIEVEMENTS) { AchievementsScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.MISSIONS) { MissionsScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.MIND) { MindScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.COLONY) { ColonyScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.TALK) {
                TalkScreen(
                    viewModel = viewModel,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                ) { navController.popBackStack() }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onResetToNewGame = {
                        viewModel.resetEverything()
                        navController.navigate(Routes.NEW_GAME) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                    onOpenUpdates = { navController.navigate(Routes.UPDATE) },
                )
            }
            composable(Routes.UPDATE) {
                UpdateScreen(updateViewModel) { navController.popBackStack() }
            }
            composable(Routes.MEMORIAL) {
                MemorialScreen(
                    viewModel = viewModel,
                    onStartNextGeneration = {
                        navController.navigate(Routes.NEW_GAME) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                    onOpenDiary = { navController.navigate(Routes.CHRONICLE) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
