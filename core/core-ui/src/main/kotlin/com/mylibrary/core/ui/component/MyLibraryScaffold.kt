package com.mylibrary.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mylibrary.core.ui.util.rememberWindowSizeClass
import com.mylibrary.core.ui.util.showsNavigationRail

/**
 * One destination in the app's top-level navigation.
 *
 * Defined here rather than in `:app` so that the navigation bar, the rail and any screen that links
 * to a destination all agree on its label and icon by construction, instead of by two lists being
 * kept in step by hand. Each destination carries both a filled and an outlined icon because
 * Material 3 signals selection by *changing the icon*, not only by tinting it — which matters for
 * users who cannot distinguish the selected and unselected container colours.
 */
data class TopLevelDestination(
    /** The navigation route; must match the route registered in the app's navigation graph. */
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/**
 * The app's adaptive shell: a bottom navigation bar on a phone, a navigation rail on anything
 * wider, and content placed *beside* the rail rather than below it.
 *
 * This is the only place in MyLibrary that chooses between the two, so no individual screen has to
 * know which one it is inside. The rail also keeps its destination labels visible permanently
 * (which is what Material 3 asks for at 600dp+) instead of relying on tooltips.
 */
@Composable
fun MyLibraryScaffold(
    destinations: List<TopLevelDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    /** Extra content pinned above the navigation bar, such as a mini player. */
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    /** Optional content at the top of the navigation rail, conventionally the app name. */
    railHeader: @Composable ColumnScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val useRail = rememberWindowSizeClass().showsNavigationRail

    // A screen that is not a top-level destination — the reader, the book details page — passes an
    // empty destination list. In that case the shell renders no navigation chrome at all, which is
    // what lets the reader go genuinely full screen without a second scaffold implementation.
    if (destinations.isEmpty()) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) { content(Modifier.fillMaxSize()) }
        }
        return
    }

    if (useRail) {
        Row(modifier = modifier.fillMaxSize()) {
            NavigationRail(
                // The rail draws its own window insets, so the surrounding Row must not double
                // them up; NavigationRailDefaults handles the side and top bars.
                windowInsets = NavigationRailDefaults.windowInsets,
                header = railHeader,
            ) {
                destinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationRailItem(
                        selected = selected,
                        onClick = { onNavigate(destination.route) },
                        icon = { DestinationIcon(destination, selected) },
                        label = { Text(stringResource(destination.labelResId)) },
                    )
                }
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = topBar,
                bottomBar = bottomBar,
                snackbarHost = snackbarHost,
                floatingActionButton = floatingActionButton,
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) { content(Modifier.fillMaxSize()) }
            }
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = topBar,
            bottomBar = {
                Column {
                    bottomBar()
                    NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
                        destinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onNavigate(destination.route) },
                                icon = { DestinationIcon(destination, selected) },
                                label = { Text(stringResource(destination.labelResId)) },
                            )
                        }
                    }
                }
            },
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) { content(Modifier.fillMaxSize()) }
        }
    }
}

/** Swaps between the filled and outlined icon so selection survives a colour-blind palette. */
@Composable
private fun DestinationIcon(destination: TopLevelDestination, selected: Boolean) {
    Icon(
        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
        contentDescription = null,
    )
}

/**
 * The scaffold a *feature screen* uses, nested inside [MyLibraryScaffold].
 *
 * This exists to solve one specific problem correctly, in one place. When a screen sits inside the
 * app shell it is already being padded for the bottom navigation bar, so a second `Scaffold` that
 * applies window insets again would push the content up by the height of the navigation bar — the
 * classic "why is there a gap at the bottom of the list" bug.
 *
 * So this scaffold consumes only the top and horizontal insets (which the outer shell deliberately
 * does not, because the top app bar belongs to the screen) and leaves the bottom alone. Every
 * feature gets this by construction rather than by each one remembering.
 */
@Composable
fun FeatureScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        content = content,
    )
}
