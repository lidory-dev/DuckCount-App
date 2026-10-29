import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.android.ui.text.nameOrder
import component.ContactRequestsCard
import model.User
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactListState
import viewmodel.ContactListViewModel
import java.text.Normalizer
import app.lidory.duckcount.android.ui.R as androidUiR


@Composable
fun ContactListScreen(
    navigateToDetailScreen: (String) -> Unit,
    navigateToUsernameReservation: () -> Unit,
    navigateToContactRequests: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val viewModel = koinViewModel<ContactListViewModel>()
    val state by viewModel.usersState.collectAsStateWithLifecycle()

    when (val state = state) {
        is ContactListState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is ContactListState.UsernameReservationRequired -> {
            DiscoveryUsernameReservationPrompt(
                navigateToUsernameReservation = navigateToUsernameReservation,
                modifier = modifier,
            )
        }

        is ContactListState.Content -> {
            ContactList(
                users = state.users,
                hasContacts = state.hasContacts,
                contactRequestCount = state.contactRequestCount,
                searchQuery = viewModel.searchQuery,
                onSearchQueryChange = { viewModel.onSearchQueryChange(query = it) },
                navigateToDetailScreen = navigateToDetailScreen,
                navigateToContactRequests = navigateToContactRequests,
                modifier = modifier,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

/**
 * Stands in for the contact list until a username is reserved, and sends the user to
 * the settings screen that does the reserving.
 */
@Composable
private fun DiscoveryUsernameReservationPrompt(
    navigateToUsernameReservation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Padding.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(0.4f))

        Icon(
            modifier = Modifier.size(32.dp),
            painter = painterResource(androidUiR.drawable.ic_username),
            contentDescription = null,
        )

        Spacer(modifier = Modifier.height(Padding.medium))

        Text(
            textAlign = TextAlign.Center,
            text = stringResource(R.string.username_reservation_required),
        )

        Spacer(modifier = Modifier.height(Padding.medium))

        DuckCountButton(
            onClick = navigateToUsernameReservation,
        ) {
            Text(text = stringResource(R.string.reserve_a_username))
        }

        Spacer(modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun ContactList(
    users: List<User>,
    hasContacts: Boolean,
    contactRequestCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    navigateToDetailScreen: (String) -> Unit,
    navigateToContactRequests: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val listState = rememberLazyListState()

    val groupedContactList = remember(users) {
        users
            .groupBy { it.section() }
            .toSortedMap(compareBy<String> { it == OTHER_SECTION }.thenBy { it })
            .mapValues { (_, section) ->
                section.sortedWith(compareBy(nameOrder()) { it.fullName })
            }
    }

    LaunchedEffect(searchQuery) {
        listState.animateScrollToItem(0)
    }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                focusManager.clearFocus()
            }
            .padding(horizontal = Padding.medium)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        DuckCountSearchBar(
            modifier = Modifier.fillMaxWidth(),
            searchQuery = searchQuery,
            onSearchChange = onSearchQueryChange,
            focusManager = focusManager,
        )

        if (searchQuery.isBlank() && contactRequestCount > 0) {
            ContactRequestsCard(
                count = contactRequestCount,
                onClick = navigateToContactRequests,
            )
        }

        if (groupedContactList.isEmpty()) {
            // Nobody to find, whatever is searched, or nobody whose name holds what is.
            if (hasContacts) {
                EmptyContentContainer(
                    icon = androidUiR.drawable.ic_search,
                    text = androidUiR.string.no_result,
                )
            } else {
                EmptyContentContainer(
                    icon = androidUiR.drawable.ic_contacts_group,
                    text = R.string.no_contacts_yet,
                )
            }
        } else {
            LazyColumn(
                state = listState,
            ) {
                groupedContactList.forEach { (letter, users) ->
                    if (searchQuery.isBlank()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = Padding.medium),
                                    text = letter,
                                )

                            }
                        }
                    }

                    items(
                        items = users,
                        key = { user -> user.uuid },
                    ) { user ->
                        Card(
                            shape = RoundedCornerShape(size = Padding.large),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(200),
                                    placementSpec = tween(200),
                                )
                                .padding(vertical = Padding.medium),
                            onClick = { navigateToDetailScreen(user.uuid) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(TextFieldDefaults.MinHeight),
                            ) {
                                with(sharedTransitionScope) {
                                    ProfilePictureCard(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .sharedElement(
                                                sharedContentState = rememberSharedContentState(key = "profile_picture_${user.uuid}"),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                            ),
                                        profilePicture = user.profilePicture,
                                        uuid = user.uuid,
                                        fullName = user.fullName,
                                    )

                                    Text(
                                        modifier = Modifier.sharedBounds(
                                            sharedContentState = rememberSharedContentState(key = "full_name_${user.uuid}"),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        ),
                                        text = user.fullName,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

/** The section for names that do not start with a letter, listed last. */
private const val OTHER_SECTION = "#"

/**
 * The section a contact is listed under: the first letter of their name without its accent,
 * or [OTHER_SECTION]. Contacts pick their own name, so it may be empty or start with anything.
 */
private fun User.section(): String =
    if (fullName.firstOrNull()?.isLetter() == true) {
        Normalizer.normalize(fullName, Normalizer.Form.NFD).first().uppercase()
    } else {
        OTHER_SECTION
    }
