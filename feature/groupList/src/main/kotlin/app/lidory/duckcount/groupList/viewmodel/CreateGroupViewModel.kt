package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.GroupListCallBacks
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateGroupViewModel(
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateGroupState())

    val state = _state

    private var _searchQuery = MutableStateFlow("")

    var searchQuery by mutableStateOf("")
        private set

    var isMemberDialogShown by mutableStateOf(false)

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = _searchQuery
        .flatMapLatest { query ->
            groupListCallBacks.getUsersFiltered(filter = query)
        }
        .map { users ->
            UserListState(users)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserListState(),
        )

    val selectionToggleState: StateFlow<ToggleableState> = combine(
        _selectedUuids,
        usersState,
    ) { selectedUuids, users ->
        toggleableState(selectedUuids = selectedUuids, totalUsers = users.users.size)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ToggleableState.Off,
    )

    fun showMemberDialog() {
        isMemberDialogShown = true
    }

    fun hideMemberDialog() {
        isMemberDialogShown = false
    }

    fun clearSearchQuery() {
        searchQuery = ""
        _searchQuery.value = ""
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun toggleSelectAllMembers() {
        val allUuids = usersState.value.users.mapTo(mutableSetOf()) { it.uuid }
        _selectedUuids.update { selected ->
            if (selected == allUuids) emptySet() else allUuids
        }
    }

    fun toggleSelectedUser(uuid: String) {
        _selectedUuids.update { selected ->
            if (uuid in selected) selected - uuid else selected + uuid
        }
    }

    fun updateTitle(title: String) {
        _state.update {
            it.copy(title = title)
        }
    }

    fun updateDescription(description: String) {
        _state.update {
            it.copy(description = description)
        }
    }

    fun updateMembers(members: List<GroupMember>) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    members = members.map { member ->
                        val fullName = groupListCallBacks.getUserFullName(member.userUuid)
                        MemberWithFullName(member, fullName = fullName.first())
                    },
                )
            }
        }
    }

    fun removeMember(member: GroupMember) {
        _state.update {
            it.copy(
                members = it.members.filterNot { memberWithFullName -> memberWithFullName.member.uuid == member.uuid },
            )
        }
        _selectedUuids.update { selected ->
            if (member.userUuid in selected) selected - member.userUuid else selected + member.userUuid
        }
    }

    fun createGroup() {
        viewModelScope.launch {
            groupListCallBacks.createGroup(
                title = state.value.title,
                description = state.value.description,
                members = state.value.members.map { it.member } + GroupMember(
                    // TODO: Use connected user uuid
                    userUuid = "",
                    groupUuid = "",
                    isAdmin = true,
                ),
            )
        }
    }

    companion object {
        const val MAX_DESCRIPTION_LINE = 2000
    }
}

data class CreateGroupState(
    val title: String = "",
    val description: String = "",
    val members: List<MemberWithFullName> = emptyList(),
)

data class MemberWithFullName(
    val member: GroupMember,
    val fullName: String,
)

data class UserListState(
    val users: List<User> = emptyList(),
)

fun toggleableState(
    selectedUuids: Set<String>,
    totalUsers: Int,
): ToggleableState = when {
    totalUsers > 0 && selectedUuids.size == totalUsers -> ToggleableState.On
    selectedUuids.isNotEmpty() -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}
