package com.mylibrary.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mylibrary.AppUiState
import com.mylibrary.AppViewModel
import com.mylibrary.R
import com.mylibrary.core.domain.model.AppLanguage
import com.mylibrary.core.ui.component.MyLibraryScaffold
import com.mylibrary.core.ui.component.TopLevelDestination
import com.mylibrary.core.ui.theme.MyLibraryTheme
import com.mylibrary.feature.library.BookDetailsRoute
import com.mylibrary.feature.library.BookDetailsViewModel
import com.mylibrary.feature.library.LibraryRoute
import com.mylibrary.feature.reader.ReaderRoute
import com.mylibrary.feature.search.SearchRoute
import com.mylibrary.feature.settings.SettingsRoute
import com.mylibrary.ui.navigation.Routes

/**
 * The root of every MyLibrary screen.
 *
 * Two things are established here for the whole tree, and nowhere else:
 *
 *  1. **Language.** The context handed to Compose is scoped to the user's chosen language, so
 *     `stringResource` and `LocalLayoutDirection` resolve against it. Applying this once at the
 *     root is what makes an in-app language change take effect everywhere without restarting.
 *  2. **Theme.** Material 3 colour scheme and typography, with the reading-direction-aware type
 *     scale chosen from the layout direction established in step 1 — which is why the theme is
 *     applied *inside* the locale override and not outside it.
 */
@Composable
fun MyLibraryApp(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Waiting for the persisted settings avoids painting the default theme and then immediately
    // repainting the user's — with a dark theme that flash is a white screen on every cold start.
    if (!state.isReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    MyLibraryLocalized(state.settings.language) {
        MyLibraryTheme(settings = state.settings) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                MyLibraryNavHost()
            }
        }
    }
}

/**
 * Provides the chosen language to the whole composition.
 *
 * **`LocalContext` is deliberately not overridden, and must not be.** It is tempting — the localized
 * context is right there — but `createConfigurationContext` returns a plain `ContextImpl`, not the
 * hosting Activity, and `hiltViewModel()` reads `LocalContext` to build its
 * `HiltViewModelFactory`, which requires an Activity context and throws
 * `IllegalStateException: Expected an activity context…` otherwise. Swapping it crashes the app on
 * the first screen that creates a ViewModel.
 *
 * Localising through the two locals that actually matter avoids that entirely:
 *
 *  - `LocalResources` is what `stringResource` resolves against, so this is the localisation lever;
 *  - `LocalConfiguration` carries the chosen locale for anything that inspects it directly;
 *  - `LocalLayoutDirection` is what flips the layout for Arabic.
 *
 * The Activity context therefore stays in place for everything that genuinely needs it — Hilt's
 * ViewModel factory, the `ContentResolver` behind the document picker — while every string and
 * every layout direction still follows the user's choice.
 */
@Composable
private fun MyLibraryLocalized(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) { baseContext.withAppLanguage(language) }

    CompositionLocalProvider(
        LocalResources provides localizedContext.resources,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalLayoutDirection provides localizedContext.resources.configuration.toComposeLayoutDirection(),
        content = content,
    )
}

@Composable
private fun MyLibraryNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    MyLibraryScaffold(
        // An empty list on non-top-level routes is what removes the navigation bar for the reader.
        destinations = if (Routes.isTopLevel(currentRoute)) TopLevelDestinations else emptyList(),
        currentRoute = currentRoute,
        onNavigate = { route -> navController.navigateTopLevel(route) },
        // The rail is the only place the app's name appears on a wide window: a bottom bar names
        // itself by being at the bottom of a phone, where a rail is a column of three icons with
        // nothing saying whose they are.
        railHeader = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        },
    ) { contentModifier ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = contentModifier,
        ) {
            composable(Routes.LIBRARY) {
                LibraryRoute(
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                    onOpenDetails = { bookId -> navController.navigate(Routes.bookDetails(bookId)) },
                )
            }

            composable(Routes.SEARCH) {
                SearchRoute(
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsRoute()
            }

            composable(
                route = Routes.BOOK_DETAILS,
                arguments = listOf(navArgument(Routes.BOOK_ID_ARG) { type = NavType.LongType }),
            ) {
                BookDetailsRoute(
                    onOpenReader = { bookId -> navController.navigate(Routes.reader(bookId)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.READER,
                arguments = listOf(navArgument(Routes.BOOK_ID_ARG) { type = NavType.LongType }),
                // Opening a book rises into it rather than cutting to it: a page arriving at its
                // final size from slightly smaller is what makes the transition read as the book
                // opening rather than as a screen replacing another. Short, and deliberately not a
                // setting — there is nothing here a reader would want to configure.
                enterTransition = {
                    fadeIn(tween(OPENING_MS)) + scaleIn(
                        initialScale = BOOK_OPENING_SCALE,
                        animationSpec = tween(OPENING_MS, easing = FastOutSlowInEasing),
                    )
                },
                exitTransition = { fadeOut(tween(CLOSING_MS)) },
                popEnterTransition = { fadeIn(tween(CLOSING_MS)) },
                popExitTransition = {
                    fadeOut(tween(CLOSING_MS)) + scaleOut(
                        targetScale = BOOK_OPENING_SCALE,
                        animationSpec = tween(CLOSING_MS, easing = FastOutSlowInEasing),
                    )
                },
            ) {
                ReaderRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Switching between top-level destinations.
 *
 * `saveState`/`restoreState` keep each tab's scroll position and, in the library's case, its
 * filters — switching to Settings and back must not scroll the shelf to the top. `popUpTo` the
 * start destination stops the back stack growing by one entry per tab tap.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** How long a book takes to open and to close, in milliseconds. */
private const val OPENING_MS = 260
private const val CLOSING_MS = 200

/** How much smaller the page is at the start of the opening transition. */
private const val BOOK_OPENING_SCALE = 0.92f

/** The three destinations in the navigation bar and rail. */
private val TopLevelDestinations: List<TopLevelDestination>
    get() = listOf(
        TopLevelDestination(
            route = Routes.LIBRARY,
            labelResId = R.string.nav_library,
            selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
            unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        ),
        TopLevelDestination(
            route = Routes.SEARCH,
            labelResId = R.string.nav_search,
            selectedIcon = Icons.Filled.Search,
            unselectedIcon = Icons.Outlined.Search,
        ),
        TopLevelDestination(
            route = Routes.SETTINGS,
            labelResId = R.string.nav_settings,
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
        ),
    )

/** Re-exported so the navigation argument key has a single definition. */
internal const val BOOK_ID_ARGUMENT = BookDetailsViewModel.ARG_BOOK_ID
