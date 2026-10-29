package app.lidory.duckcount.navigation

import ContactListScreen
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.lidory.duckcount.R
import app.lidory.duckcount.android.ui.composable.button.isButtonPinned
import app.lidory.duckcount.expenseList.ExpenseListScreen
import app.lidory.duckcount.expenseList.component.AddExpenseFloatingActionButton
import app.lidory.duckcount.groupList.GroupListScreen
import app.lidory.duckcount.groupList.component.AddGroupFloatingActionButton
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
import app.lidory.duckcount.navigation.key.NavigableTabNavKey
import authenticationUtils.getDiscoveryUsername
import authenticationUtils.getDiscoveryUsernameFlow
import component.AddContactFloatingActionButton

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun Dashboard(
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    openGroups: Boolean,
    navigateToExpenseDetailScreen: (String, String?) -> Unit,
    navigateToCreateExpenseScreen: () -> Unit,
    navigateToContactDetailScreen: (String) -> Unit,
    navigateToNewContact: () -> Unit,
    navigateToContactRequests: () -> Unit,
    navigateToCreateGroupScreen: () -> Unit,
    navigateToGroupDetailScreen: (String) -> Unit,
    navigateToSettingsScreen: () -> Unit,
    navigateToUsernameReservation: () -> Unit,
) {
    // On the groups when a notification about one opened the app, the first tab under them as
    // picking them would leave it, see selectTab. Only the stack the dashboard starts with: the
    // one it saved is what comes back from then on.
    val startTabs = if (openGroups) listOf(ExpenseListScreenKey, GroupListScreenKey) else listOf(ExpenseListScreenKey)
    val backStack = rememberNavBackStack(*startTabs.toTypedArray())

    // The contacts tab asks for a username to be reserved until there is one.
    val reservedUsername by remember { getDiscoveryUsernameFlow() }
        .collectAsStateWithLifecycle(initialValue = getDiscoveryUsername())

    // Whether the keyboard is the dashboard's own, opened from its search field: from the moment
    // a field of the dashboard takes focus until the keyboard has closed with none focused. Only
    // then do the tab and its add button make room for it. A screen opened from the dashboard can
    // open a keyboard of its own as it slides in, as adding a contact does: the dashboard would
    // otherwise climb with it on its way out, or slide back in making room for one about to close.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // Derived, so that the dashboard recomposes once the keyboard has closed, rather than on every
    // frame its inset moves by.
    val isKeyboardClosed by remember(density, ime) { derivedStateOf { ime.getBottom(density) == 0 } }
    var hasFocus by remember { mutableStateOf(false) }
    var isKeyboardOwn by remember { mutableStateOf(false) }

    LaunchedEffect(hasFocus, isKeyboardClosed) {
        if (!hasFocus && isKeyboardClosed) isKeyboardOwn = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopDuckCountBar(
                title = R.string.app_name,
                navigateToSettingsScreen = navigateToSettingsScreen,
            )
        },
        bottomBar = {
            BottomNavigationBar(
                backStack = backStack,
            )
        },
    ) { innerPadding ->
        // The keyboard opens over the bottom bar. The tab ends above the keyboard, so that every
        // contact a search finds can be scrolled into view, and the add button goes up with it,
        // see below, where the Scaffold's own slot for the button would leave it behind.
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .onFocusChanged { focusState ->
                    hasFocus = focusState.hasFocus

                    if (focusState.hasFocus) isKeyboardOwn = true
                },
        ) {
            NavDisplay(
                modifier = if (isKeyboardOwn) Modifier.imePadding() else Modifier,
                backStack = backStack,
                transitionSpec = {
                    val direction = getTransitionSpecDirection(initialState, targetState)
                    slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
                },
                popTransitionSpec = {
                    val direction = getTransitionSpecDirection(initialState, targetState)
                    slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
                },
                predictivePopTransitionSpec = {
                    val direction = getTransitionSpecDirection(initialState, targetState)
                    slideIntoContainer(direction, tween()) togetherWith slideOutOfContainer(direction, tween())
                },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = { key ->
                    when (key) {
                        is ExpenseListScreenKey -> {
                            NavEntry(
                                key = key,
                                metadata = ListDetailSceneStrategy.listPane(
                                    detailPlaceholder = {
                                        Text("Choose an expense to display more information about it.")
                                    },
                                ),
                            ) {
                                ExpenseListScreen(
                                    onExpenseClicked = navigateToExpenseDetailScreen,
                                )
                            }
                        }

                        is GroupListScreenKey -> {
                            NavEntry(
                                key = key,
                            ) {
                                GroupListScreen(
                                    onGroupClicked = navigateToGroupDetailScreen,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    sharedTransitionScope = sharedTransitionScope,
                                )
                            }
                        }

                        is ContactListScreenKey -> {
                            NavEntry(
                                key = key,
                            ) {
                                ContactListScreen(
                                    navigateToDetailScreen = navigateToContactDetailScreen,
                                    navigateToUsernameReservation = navigateToUsernameReservation,
                                    navigateToContactRequests = navigateToContactRequests,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    sharedTransitionScope = sharedTransitionScope,
                                )
                            }
                        }

                        else -> throw RuntimeException("Invalid NavKey.")
                    }
                },
            )

            // The button goes up with the keyboard while the window keeps room for the button and
            // the search field above it, see isButtonPinned. With less, as on a phone in landscape,
            // the button would cover the field being typed in: it stays above the bottom bar then,
            // behind the keyboard until the keyboard closes.
            val isButtonPinned = isButtonPinned()
            val addButtonModifier = Modifier
                .align(Alignment.BottomEnd)
                .then(if (isKeyboardOwn && isButtonPinned) Modifier.imePadding() else Modifier)
                .padding(FabSpacing)

            when (backStack.lastOrNull()) {
                is ExpenseListScreenKey -> {
                    AddExpenseFloatingActionButton(
                        modifier = addButtonModifier,
                        onClick = navigateToCreateExpenseScreen,
                    )
                }

                is GroupListScreenKey -> {
                    AddGroupFloatingActionButton(
                        modifier = addButtonModifier,
                        onClick = navigateToCreateGroupScreen,
                    )
                }

                is ContactListScreenKey -> {
                    if (reservedUsername != null) {
                        AddContactFloatingActionButton(
                            modifier = addButtonModifier,
                            onClick = navigateToNewContact,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far the dashboard's add button keeps from the bottom bar, or the keyboard, and from the end
 * of the content, which a navigation bar or a camera cutout at the side moves in: where the
 * Scaffold's own slot for it would put it.
 */
private val FabSpacing = 16.dp

fun getTransitionSpecDirection(
    initialState: Scene<NavKey>,
    targetState: Scene<NavKey>,
): AnimatedContentTransitionScope.SlideDirection {
    val tabs = NavigableTabNavKey.tabs.map { it.toString() }
    val initialIndex = tabs.indexOf(initialState.key)
    val targetIndex = tabs.indexOf(targetState.key)

    val direction = if (initialIndex < targetIndex) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }

    return direction
}