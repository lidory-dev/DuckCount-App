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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import model.User
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactListState
import viewmodel.ContactListViewModel
import java.text.Normalizer


@Composable
fun ContactListScreen(
    navigateToDetailScreen: (String) -> Unit,
    navigateToUsernameReservation: () -> Unit,
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
                searchQuery = viewModel.searchQuery,
                onSearchQueryChange = { viewModel.onSearchQueryChange(query = it) },
                navigateToDetailScreen = navigateToDetailScreen,
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
    TODO("Add an icon in the column")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Padding.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            textAlign = TextAlign.Center,
            text = stringResource(R.string.username_reservation_required),
        )

        Spacer(modifier = Modifier.height(Padding.large))

        DuckCountButton(
            onClick = navigateToUsernameReservation,
        ) {
            Text(text = stringResource(R.string.reserve_a_username))
        }
    }
}

@Composable
private fun ContactList(
    users: List<User>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    navigateToDetailScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val listState = rememberLazyListState()

    val groupedContactList = remember(users) {
        users
            .sortedWith { user1, user2 ->
                if (user1.fullName.first().isLetter())
                    user1.fullName.first().compareTo(user2.fullName.first())
                else
                    1
            }
            .groupBy {
                val char = it.fullName.first()
                if (char.isLetter())
                    Normalizer.normalize(it.fullName, Normalizer.Form.NFD).first().uppercase()
                else
                    "#"
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
