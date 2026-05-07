import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.searchbar.DuckCountSearchBar
import app.lidory.duckcount.android.ui.constant.Padding
import component.ProfilePictureCard
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactListViewModel
import java.text.Normalizer


@Composable
fun ContactListScreen(
    navigateToDetailScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<ContactListViewModel>()
    val state by viewModel.usersState.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val listState = rememberLazyListState()

    val groupedContactList = remember(state.users) {
        state.users
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

    LaunchedEffect(viewModel.searchQuery) {
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
            searchQuery = viewModel.searchQuery,
            onSearchChange = { viewModel.onSearchQueryChange(query = it) },
            focusManager = focusManager,
        )

        LazyColumn(
            state = listState,
        ) {
            groupedContactList.forEach { (letter, users) ->
                if (viewModel.searchQuery.isBlank()) {
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
                            ProfilePictureCard(
                                modifier = Modifier.fillMaxHeight(),
                                profilePicture = user.profilePicture,
                                uuid = user.uuid,
                                fullName = user.fullName,
                            )

                            Text(
                                text = user.fullName,
                            )
                        }
                    }
                }
            }
        }
    }
}
