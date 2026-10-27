package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MembershipChangeError
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupSettingsViewModel(
    private val groupUuid: String,
    private val groupSettingsCallBacks: GroupSettingsCallBacks,
) : ViewModel() {
    fun getUserFullName(userUuid: String): Flow<String?> {
        return groupSettingsCallBacks.getUserFullName(userUuid)
    }

    private val _state = MutableStateFlow(GroupSettingsState())

    val state: StateFlow<GroupSettingsState> = _state.onStart {
        groupSettingsCallBacks.getGroupWithMembers(groupUuid = groupUuid).onEach { groupWithMembers ->
            _state.update {
                it.copy(
                    groupWithMembers = groupWithMembers,
                    membersWithFullName = groupWithMembers.members.filter { it.userUuid.isNotBlank() }.map { member ->

                        GroupMemberWithFullName(
                            member = member,
                            fullName = getUserFullName(member.userUuid).first(),
                        )
                    },
                )
            }
        }.launchIn(viewModelScope)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = GroupSettingsState(),
    )

    /** The most members the group may have, see [GroupSettingsCallBacks.maxMembers]. */
    val maxMembers: Int = groupSettingsCallBacks.maxMembers

    // True while a change to the members is on its way to the server, so that the button that made
    // it can show an in-place spinner, and neither the picker nor the confirmation it was made from
    // be closed until it is answered, which the user is told of there.
    private val _isChanging = MutableStateFlow(false)
    val isChanging: StateFlow<Boolean> = _isChanging.asStateFlow()

    // Why the members picked could not be added, told over the picker, which stays open for the
    // user to pick differently.
    private val _additionErrors = Channel<MembershipChangeError>(Channel.BUFFERED)
    val additionErrors: Flow<MembershipChangeError> = _additionErrors.receiveAsFlow()

    // What came of a change once the picker, or the confirmation, closed on its answer, see
    // MembershipChangeResult.message.
    private val _notices = Channel<MembershipChangeResult>(Channel.BUFFERED)
    val notices: Flow<MembershipChangeResult> = _notices.receiveAsFlow()

    var isMemberDialogShown by mutableStateOf(false)
        private set

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val memberUuids: Flow<Set<String>> = _state
        .map { state -> state.membersWithFullName.mapTo(HashSet()) { it.member.userUuid } }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = combine(
        _searchQuery.flatMapLatest { query -> groupSettingsCallBacks.getInvitableUsersFiltered(filter = query) },
        memberUuids,
    ) { users, memberUuids ->
        UserListState(invitableUsers(users = users, memberUuids = memberUuids))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UserListState(),
    )

    // Who the picker lists whatever is searched, see listedPicks.
    private val listedUuids: StateFlow<Set<String>> = combine(
        groupSettingsCallBacks.getInvitableUsersFiltered(filter = ""),
        memberUuids,
    ) { users, memberUuids ->
        invitableUsers(users = users, memberUuids = memberUuids).mapTo(HashSet()) { it.uuid }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptySet(),
    )

    // Who is picked to be added to the group, never one of its members, whom the picker does not
    // list, see invitableUsers. Picked anew each time the picker opens, see closeMemberDialog, and
    // told as the picker lists them, see listedPicks: the picks are kept as they were, so that a
    // list read late, or read again, unpicks nobody for good.
    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = combine(_selectedUuids, listedUuids) { picked, listed ->
        listedPicks(picked = picked, listedUuids = listed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptySet(),
    )

    // Who the user is asked to confirm removing, and then removing, until the removal is answered.
    var memberToRemove by mutableStateOf<GroupMemberWithFullName?>(null)
        private set

    fun getAuthenticatedUserUuid(): String? {
        return groupSettingsCallBacks.getAuthenticatedUserUuid()
    }

    fun showMemberDialog() {
        isMemberDialogShown = true
    }

    /** Closes the picker, unless the members picked are being added: what comes of it is told there. */
    fun hideMemberDialog() {
        if (_isChanging.value) return

        closeMemberDialog()
    }

    // The selection goes with the picker, which opens with nobody picked.
    private fun closeMemberDialog() {
        isMemberDialogShown = false
        _selectedUuids.value = emptySet()
        _searchQuery.value = ""
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelectedUser(uuid: String) {
        _selectedUuids.update { picked ->
            toggledListedSelection(
                picked = picked,
                listedUuids = listedUuids.value,
                uuid = uuid,
                maxMembers = maxMembers,
                currentMemberCount = _state.value.membersWithFullName.size,
            )
        }
    }

    /**
     * Adds whoever is picked to the group, see [GroupSettingsCallBacks.addMembers], and closes the
     * picker once the server took them, or will.
     */
    fun addMembers() = viewModelScope.launch {
        if (_isChanging.value) return@launch

        _isChanging.value = true

        try {
            val result = groupSettingsCallBacks.addMembers(groupUuid = groupUuid, userUuids = selectedUuids.value)

            if (result is MembershipChangeResult.Failed) {
                _additionErrors.send(result.error)
            } else {
                closeMemberDialog()
                _notices.send(result)
            }
        } finally {
            _isChanging.value = false
        }
    }

    /** Asks the user to confirm removing [member] from the group, see [removeMember]. */
    fun askToRemove(member: GroupMemberWithFullName) {
        memberToRemove = member
    }

    /**
     * Drops the removal the user was asked to confirm, unless it is under way: what comes of it is
     * told once answered.
     */
    fun cancelRemoval() {
        if (_isChanging.value) return

        memberToRemove = null
    }

    /** Removes [member] from the group, see [GroupSettingsCallBacks.removeMember]. */
    fun removeMember(member: GroupMemberWithFullName) = viewModelScope.launch {
        if (_isChanging.value) return@launch

        _isChanging.value = true

        try {
            val result = groupSettingsCallBacks.removeMember(groupUuid = groupUuid, userUuid = member.member.userUuid)

            memberToRemove = null
            _notices.send(result)
        } finally {
            _isChanging.value = false
        }
    }
}

data class GroupSettingsState(
    val groupWithMembers: GroupWithMembers? = null,
    val membersWithFullName: List<GroupMemberWithFullName> = emptyList(),
)

/** What the user can do about the members of a group from its settings, see [memberActions]. */
enum class MemberActions {
    /** Nothing: only the group's creator adds and removes members, see [GroupWithMembers.canChangeMembers]. */
    NONE,

    /** Adding and removing, once the change waiting on the server is taken, see [GroupWithMembers.hasPendingChange]. */
    WAITING,

    /** Adding and removing. */
    AVAILABLE,
}

/**
 * What the user can do about the members of [this], see [MemberActions]: nothing in a group they
 * were removed from either, which they did not create, see [GroupWithMembers.canChangeMembers].
 */
fun GroupWithMembers.memberActions(): MemberActions = when {
    !canChangeMembers -> MemberActions.NONE
    hasPendingChange -> MemberActions.WAITING
    else -> MemberActions.AVAILABLE
}

/**
 * Whether the member [userUuid] can be removed by the user [selfUuid], given the [actions] they
 * have: anybody but them, the group's creator, who never leaves it, as nobody else could change
 * its members.
 */
fun isRemovable(userUuid: String, selfUuid: String?, actions: MemberActions): Boolean =
    actions == MemberActions.AVAILABLE && userUuid != selfUuid

/** Who of [users] can be added to the group of [memberUuids]: whoever is not in it yet. */
fun invitableUsers(users: List<User>, memberUuids: Set<String>): List<User> =
    users.filterNot { it.uuid in memberUuids }

/**
 * Who of [picked] is picked still: those the picker lists, [listedUuids], whatever is searched.
 * Anybody else, a contact a sync forgot meanwhile say, could not be unpicked there, and would be
 * counted and added all the same, refused for it each time the user confirmed.
 */
fun listedPicks(picked: Set<String>, listedUuids: Set<String>): Set<String> = picked intersect listedUuids

/**
 * [picked] with [uuid] taken out or added among those of them the picker lists, [listedUuids], see
 * [listedPicks] and [toggledSelection], the [currentMemberCount] members counting: whoever else is
 * picked stays so, and counts for nothing until listed again.
 */
fun toggledListedSelection(
    picked: Set<String>,
    listedUuids: Set<String>,
    uuid: String,
    maxMembers: Int,
    currentMemberCount: Int,
): Set<String> {
    val listed = listedPicks(picked = picked, listedUuids = listedUuids)

    return picked - listed + toggledSelection(
        selected = listed,
        uuid = uuid,
        maxMembers = maxMembers,
        currentMemberCount = currentMemberCount,
    )
}
