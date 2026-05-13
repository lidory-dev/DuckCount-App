package viewmodel

import GroupListCallBacks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import model.Group
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class GroupListViewModel(
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupListState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = _state.flatMapLatest {
        groupListCallBacks.getAllGroups()
    }
        .map { groups -> GroupListState(groups = groups) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = _state.value,
        )

    @OptIn(ExperimentalUuidApi::class)
    val tempState = GroupListState(
        groups = listOf(
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description",
                title = "This is a title",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 2",
                title = "This is a title 2",
            ),
            Group(
                Uuid.random().toString(),
                description = "This is a description 3",
                title = "This is a title 3",
            ),
        ),
    )
}

data class GroupListState(
    val isLoading: Boolean = false,
    val groups: List<Group> = emptyList(),
)