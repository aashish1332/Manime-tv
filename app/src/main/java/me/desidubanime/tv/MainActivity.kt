package me.desidubanime.tv

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import me.desidubanime.tv.data.model.Episode
import me.desidubanime.tv.player.PlaybackSession
import me.desidubanime.tv.ui.detail.DetailScreen
import me.desidubanime.tv.ui.detail.DetailViewModel
import me.desidubanime.tv.ui.home.HomeScreen
import me.desidubanime.tv.ui.player.PlayerScreen
import me.desidubanime.tv.ui.search.SearchScreen
import me.desidubanime.tv.ui.theme.DesiDubTheme
import me.desidubanime.tv.ui.splash.SplashScreen
import me.desidubanime.tv.ui.update.UpdatePrompt

class MainActivity : ComponentActivity() {
    // Double-back-to-exit: first press shows a toast, second press within
    // 2 seconds exits the app. TV remotes have no task-switcher, so a single
    // accidental BACK press shouldn't kill a long TV session.
    private var backPressedAt = 0L

    /**
     * Root nav controller, set by AppNav. BACK on a pushed route (Detail /
     * Search / Settings / Player) must pop the stack — not trigger the
     * double-tap exit gate. Without this, BACK on the Detail screen showed
     * the exit toast instead of going Home.
     */
    internal var nav: androidx.navigation.NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DesiDubTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.fillMaxSize()) {
                        AppNav()
                        // Self-update notice. The manager decides whether there
                        // is anything to show (never during playback), so this
                        // is mounted once, above every route.
                        UpdatePrompt()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "install unknown apps" screen finishes an
        // install that was blocked on that permission.
        me.desidubanime.tv.data.update.UpdateManager.resumePendingInstall(this)?.let { line ->
            Toast.makeText(this, line, Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> {
                me.desidubanime.tv.player.PlayerKeyBus.onMenu?.let { it(); return true }
            }
            // Many TV remotes carry a dedicated Search key. Wiring it to the
            // search screen is one press instead of Sidebar -> Search.
            KeyEvent.KEYCODE_SEARCH -> {
                val controller = nav ?: return super.onKeyDown(keyCode, event)
                if (controller.currentDestination?.route != Routes.SEARCH) {
                    controller.navigate(Routes.SEARCH)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Player overlays get first crack at BACK (menu close, server
                // list dismiss, etc.). If they handle it, we're done.
                if (me.desidubanime.tv.player.PlayerKeyBus.onBack?.invoke() == true) return true
                // A pushed route (Detail / Search / Settings / Player) pops back
                // instead of triggering the double-tap exit gate. `previousBack
                // StackEntry` is the gate: popBackStack() also pops the START
                // destination (returning true), which would exit the app from
                // Home without the double-press confirmation.
                val controller = nav
                if (controller?.previousBackStackEntry != null && controller.popBackStack()) {
                    backPressedAt = 0
                    return true
                }
                // On the Home screen (root of the stack), require a double
                // press to exit — show a toast on the first press.
                val now = System.currentTimeMillis()
                if (now - backPressedAt < 2_000) {
                    backPressedAt = 0
                    finish()
                    return true
                }
                backPressedAt = now
                Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Unwrap the Compose ContextWrapper chain to the hosting Activity. */
private fun android.content.Context.findActivity(): MainActivity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is MainActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{animeId}"
    const val PLAYER = "player/{index}"
    const val TW_ANIME = "twanime"
    fun detail(id: Int) = "detail/$id"
    fun player(index: Int) = "player/$index"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Hand the controller to the Activity so its BACK handler can pop pushed
    // routes. Without this the reference stayed null, popBackStack() never ran,
    // and EVERY back press — Detail, Settings, even mid-playback — fell through
    // to the double-tap exit gate and killed the app instead of going Home.
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    androidx.compose.runtime.DisposableEffect(nav, activity) {
        activity?.nav = nav
        onDispose { if (activity?.nav === nav) activity.nav = null }
    }

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(nav)
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenAnime = { nav.navigate(Routes.detail(it.id)) },
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenTwAnime = { nav.navigate(Routes.TW_ANIME) },
                onResumeWatch = { entry ->
                    // Rebuild the playlist for this anime, then jump straight into
                    // the saved episode at the saved position.
                    scope.launch {
                        runCatching {
                            val repo = me.desidubanime.tv.data.ServiceLocator.repository
                            val episodes = repo.episodes(entry.animeId)
                            if (episodes.isNotEmpty()) {
                                val index = entry.episodeIndex.coerceIn(episodes.indices)
                                PlaybackSession.set(
                                    entry.animeId,
                                    entry.animeTitle,
                                    entry.posterUrl,
                                    episodes,
                                    index,
                                )
                                PlaybackSession.resumePositionMs = entry.positionMs
                                // Restore the server/quality/language that was
                                // playing when the app closed.
                                entry.serverLabel?.let { PlaybackSession.preferredServerLabel = it }
                                entry.audioLang?.let { PlaybackSession.preferredAudioLang = it }
                                entry.subLang?.let { PlaybackSession.preferredSubLang = it }
                                entry.videoHeight?.let { PlaybackSession.preferredVideoHeight = it }
                                nav.navigate(Routes.player(index))
                            }
                        }
                    }
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenAnime = { nav.navigate(Routes.detail(it.id)) })
        }
        composable(Routes.SETTINGS) {
            me.desidubanime.tv.ui.settings.SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.TW_ANIME) {
            me.desidubanime.tv.ui.tw.TwAnimeScreen(
                onOpenAnime = { nav.navigate(Routes.detail(it.id)) },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("animeId") { type = NavType.IntType }),
        ) { entry ->
            val animeId = entry.arguments?.getInt("animeId") ?: return@composable
            val vm: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            DetailScreen(
                animeId = animeId,
                vm = vm,
                onOpenSeason = { season -> nav.navigate(Routes.detail(season.id)) },
                onPlayEpisode = { index ->
                    // Stash the playlist for the player + queue navigation.
                    val data = (vm.state.value as? me.desidubanime.tv.ui.common.UiState.Success)?.data
                    if (data != null) {
                        data.episodes.getOrNull(index)
                            ?.let { vm.markPlayed(data.anime.id, it.numberInt) }
                        PlaybackSession.set(
                            data.anime.id,
                            data.anime.title,
                            data.anime.posterUrl,
                            data.episodes,
                            index,
                        )
                        // Picking the episode you were already half-way through
                        // picks up where it stopped. Any other episode (or a
                        // finished one) starts from the top.
                        PlaybackSession.resumePositionMs = vm.resumePositionFor(index)
                        nav.navigate(Routes.player(index))
                    }
                },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument("index") { type = NavType.IntType }),
        ) { entry ->
            val index = entry.arguments?.getInt("index") ?: 0
            PlayerScreen(startIndex = index, onBack = { nav.popBackStack() })
        }
    }
}
