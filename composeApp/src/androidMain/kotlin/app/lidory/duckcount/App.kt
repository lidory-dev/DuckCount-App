package app.lidory.duckcount

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.navigation3.runtime.rememberNavBackStack
import app.lidory.duckcount.android.ui.theme.AppTheme
import app.lidory.duckcount.navigation.NavigationRoot
import app.lidory.duckcount.navigation.key.ContactRequestListScreenKey
import app.lidory.duckcount.navigation.key.DashboardKey
import app.lidory.duckcount.navigation.key.ExpenseDetailScreenKey
import app.lidory.duckcount.navigation.key.GroupDetailScreenKey
import authenticationUtils.SessionEnd
import database.data.repository.ExpenseRepository
import database.data.repository.GroupRepository
import kotlinx.coroutines.flow.first
import navigation.key.OnBoardingScreenKey
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * [openContactRequests] comes from a notification about a request waiting to be
 * answered, and is what takes the user straight to it. [openGroups] comes from one about a
 * group the user was added to, and opens the dashboard on the group list. [openGroupUuid] comes
 * from one that says which group it is about, and opens that group over the list.
 * [openExpenseUuid] comes from one about a single expense, and opens it over its group, or over
 * the one-time expenses for one in no group.
 */
@Composable
fun App(
    openContactRequests: Boolean = false,
    openGroups: Boolean = false,
    openGroupUuid: String? = null,
    openExpenseUuid: String? = null,
) {
    AppTheme {
        val appViewModel = koinViewModel<AppViewModel>()
        val isUpdateRequired by appViewModel.isUpdateRequired.collectAsState()

        // The server has refused this build. Ahead of everything else, whatever was on screen
        // goes, and there is no way back to it: the app can no longer load anything.
        if (isUpdateRequired) {
            UpdateRequiredScreen()

            return@AppTheme
        }

        val startup by appViewModel.startup.collectAsState()

        when (val state = startup) {
            // Brief hold while the start destination is resolved from local storage, which also
            // keeps the onboarding screen from flashing before we know where to go. Painted in
            // the splash color rather than the app background so that if it is ever visible it
            // reads as the splash still being up, not as a second screen.
            Startup.Loading ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.splash_background)),
                )

            else -> {
                val backStack = rememberNavBackStack(
                    if (state is Startup.LoggedIn) DashboardKey else OnBoardingScreenKey,
                )

                val context = LocalContext.current
                val sessionEnd by appViewModel.sessionEnd.collectAsState()

                if (state is Startup.LoggedIn) {
                    // Pushed on top of the dashboard rather than instead of it, so that
                    // going back from the requests lands where the app would have opened.
                    LaunchedEffect(Unit) {
                        if (openContactRequests) backStack.add(ContactRequestListScreenKey)
                    }

                    if (openGroupUuid != null || openExpenseUuid != null) {
                        val groupRepository = koinInject<GroupRepository>()
                        val expenseRepository = koinInject<ExpenseRepository>()

                        // Likewise over the list the dashboard opens on. A notification can outlive
                        // what it names, deleted since, which leaves that list. Both read before
                        // either is pushed, for an expense to slide in over its group in one move.
                        LaunchedEffect(Unit) {
                            val group = openGroupUuid?.takeIf { groupRepository.getWithMembers(it).first() != null }
                            val expense = openExpenseUuid?.takeIf { expenseRepository.getWithBeneficiaries(it).first() != null }

                            group?.let { backStack.add(GroupDetailScreenKey(it)) }
                            expense?.let { backStack.add(ExpenseDetailScreenKey(groupUuid = group, expenseUuid = it)) }
                        }
                    }
                }

                // The server has ended the session (the refresh token was turned down). Like
                // a log-out, that takes a new process, which also lands on the onboarding
                // screen and tells the user why.
                LaunchedEffect(sessionEnd) {
                    val end = sessionEnd ?: return@LaunchedEffect

                    context.restartApp(
                        notice = when (end) {
                            SessionEnd.EXPIRED -> RestartNotice.SESSION_EXPIRED
                            SessionEnd.ACCOUNT_DELETED -> RestartNotice.ACCOUNT_DELETED
                        },
                    )
                }

                NavigationRoot(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    backStack = backStack,
                    resumeRegistration = (state as? Startup.ResumeRegistration)?.pending,
                    openGroups = openGroups,
                )
            }
        }
    }
}
