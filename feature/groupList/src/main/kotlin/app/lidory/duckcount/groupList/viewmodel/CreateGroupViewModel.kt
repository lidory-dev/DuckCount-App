package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateGroupViewModel(
    val groupUuid: String?,
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateGroupState())
    val state = _state.onStart {
        if (groupUuid != null) {
            val group = groupListCallBacks.getGroupWithMembers(groupUuid).first()
            updateTitle(group.title)
            updateDescription(group.description)
            updateMembers(group.members)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

    private var _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    var isMemberDialogShown by mutableStateOf(false)

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

    init {
        loadAuthenticatedMember()
    }

    private fun loadAuthenticatedMember() {
        val authenticatedUserUuid = groupListCallBacks.getAuthenticatedUserUuid() ?: return

        viewModelScope.launch {
            val fullName = groupListCallBacks.getUserFullName(authenticatedUserUuid).first()
                ?: authenticatedUserUuid

            _state.update {
                it.copy(
                    authenticatedMember = GroupMemberWithFullName(
                        member = GroupMember(
                            userUuid = authenticatedUserUuid,
                            groupUuid = "",
                            isAdmin = true,
                        ),
                        fullName = fullName,
                    ),
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = _searchQuery
        .flatMapLatest { query ->
            groupListCallBacks.getUsersFiltered(filter = query).map {
                it.filterNot { user -> user.uuid == groupListCallBacks.getAuthenticatedUserUuid() }
            }
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
        _searchQuery.value = ""
    }

    fun updateSearchQuery(query: String) {
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
            val existingUuids = state.value.members.map { it.member.userUuid }.toSet()
            _state.update {
                it.copy(
                    members = state.value.members.filter { memberWithFullName -> memberWithFullName.member.userUuid in members.map { member -> member.userUuid } } +
                            members.filterNot { member -> member.userUuid in existingUuids }.map { member ->
                                val fullName = groupListCallBacks.getUserFullName(member.userUuid).first() ?: member.userUuid
		                        GroupMemberWithFullName(member, fullName = fullName)
                            },
                )
            }

            _selectedUuids.update { _state.value.members.map { it.member.userUuid }.toSet() }
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
                    userUuid = groupListCallBacks.getAuthenticatedUserUuid() ?: "",
                    groupUuid = "",
                    isAdmin = true,
                ),
            )
        }
    }

    fun updateGroup() = viewModelScope.launch {
        groupUuid?.let {
            groupListCallBacks.updateGroup(
                GroupWithMembers(
                    uuid = groupUuid,
                    title = state.value.title,
                    description = state.value.description,
                    members = state.value.members.map {
                        it.member.copy(groupUuid = groupUuid)
                    },
                ),
            )
        }
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}

data class CreateGroupState(
    val title: String = "",
    val description: String = "",
    val members: List<GroupMemberWithFullName> = emptyList(),
    val authenticatedMember: GroupMemberWithFullName? = null,
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
