package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.LeaveResult
import app.lidory.duckcount.groupList.model.MemberRelation
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupSettingsViewModel(
    private val groupUuid: String,
    private val groupSettingsCallBacks: GroupSettingsCallBacks,
) : ViewModel() {
    // One collection, shared by the screen, the picker and the open sheet, see memberUuids and
    // memberSheet, and stopped 5 seconds after none of them collects it: the group and each of its
    // members' names are observed once however often the user comes back to the screen, from a
    // contact's profile or another app, and no more once they left it.
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GroupSettingsState> =
        flow { emitAll(groupSettingsCallBacks.getGroupWithMembers(groupUuid = groupUuid)) }
            .flatMapLatest { groupWithMembers ->
                groupSettingsState(group = groupWithMembers, nameOf = groupSettingsCallBacks::getDisplayName)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = GroupSettingsState(),
            )

    /** The most members the group may have, see [GroupSettingsCallBacks.maxMembers]. */
    val maxMembers: Int = groupSettingsCallBacks.maxMembers

    // True while a change to the members is on its way to the server, or the user's leave is being
    // recorded and sent, so that the button that made it can show an in-place spinner, and neither
    // the picker nor the confirmation it was made from be closed until it is answered, which the
    // user is told of there.
    private val _isChanging = MutableStateFlow(false)
    val isChanging: StateFlow<Boolean> = _isChanging.asStateFlow()

    // Why the members picked could not be added, told over the picker, which stays open for the
    // user to pick differently.
    private val _additionErrors = Channel<MembershipChangeError>(Channel.BUFFERED)
    val additionErrors: Flow<MembershipChangeError> = _additionErrors.receiveAsFlow()

    // What came of a change once the picker, or the confirmation, closed on its answer, see
    // MembershipChangeResult.message, and why a leave did not go through once its confirmation did.
    private val _notices = Channel<MembershipChangeResult>(Channel.BUFFERED)
    val notices: Flow<MembershipChangeResult> = _notices.receiveAsFlow()

    var isMemberDialogShown by mutableStateOf(false)
        private set

    // Snapshot state rather than a flow: what the user types has to come back to the search bar in
    // the same frame as the edit. A flow collected by the screen hands it back a frame late, the
    // field meanwhile taking back the text it had, and its cursor with it.
    var searchQuery by mutableStateOf("")
        private set

    private val memberUuids: Flow<Set<String>> = state
        .map { state -> state.membersWithFullName.mapTo(HashSet()) { it.member.userUuid } }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = combine(
        snapshotFlow { searchQuery }.flatMapLatest { query ->
            groupSettingsCallBacks.getInvitableUsersFiltered(filter = query)
        },
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

    // Whether the user is asked to confirm leaving the group, and then leaving it, until the leave
    // is answered.
    var isLeaveAsked by mutableStateOf(false)
        private set

    // The member whose sheet is open, as tapped, see openMember, until it closes, see closeMember.
    // The sheet shows them as the group lists them since, see sheetMember.
    private val openedMember = MutableStateFlow<GroupMemberWithFullName?>(null)

    // True while a contact request to the member whose sheet is open is on its way to the server,
    // so that its button can show an in-place spinner, and the sheet not be closed until it is
    // answered, which the user is told of there, as for a change to the members, see isChanging.
    private val _isSendingRequest = MutableStateFlow(false)

    // Why the last request sent from the open sheet was not, told there until the next one is
    // sent or the sheet closes.
    private val requestError = MutableStateFlow<ContactRequestError?>(null)

    // Whether a request sent from the open sheet is out to them, see isRequestOut, while where the
    // user stands with them, as read, does not tell it yet, see shownRelation.
    private val isRequestTaken = MutableStateFlow(false)

    /**
     * The sheet of the member [openMember] opened, see [MemberSheetState], null while none is
     * open, and until where the user stands with them is read: it opens with what it offers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val memberSheet: StateFlow<MemberSheetState?> = openedMember.flatMapLatest { opened ->
        if (opened == null) return@flatMapLatest flowOf(null)

        combine(
            groupSettingsCallBacks.getContactRelation(userUuid = opened.member.userUuid),
            sheetMember(opened = opened, states = state),
            _isSendingRequest,
            requestError,
            isRequestTaken,
        ) { relation, (group, member), isSending, error, isTaken ->
            // Only until the read tells anything but nobody the user asked, so that whatever
            // changes after shows as read: they accept and then remove the user, say. This one
            // shows the same either way.
            if (isTaken && relation != MemberRelation.NONE) isRequestTaken.value = false

            MemberSheetState(
                member = member,
                actions = memberSheetActions(
                    relation = shownRelation(relation = relation, isRequestTaken = isTaken),
                    canVouchForMembers = group.canVouchForMembers,
                    isRemovable = isRemovable(
                        userUuid = member.member.userUuid,
                        selfUuid = getAuthenticatedUserUuid(),
                        actions = group.memberActions(),
                    ),
                ),
                isSendingRequest = isSending,
                requestError = error,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null,
    )

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
        searchQuery = ""
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun toggleSelectedUser(uuid: String) {
        _selectedUuids.update { picked ->
            toggledListedSelection(
                picked = picked,
                listedUuids = listedUuids.value,
                uuid = uuid,
                maxMembers = maxMembers,
                currentMemberCount = state.value.membersWithFullName.size,
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

    /** Opens the sheet of [member], never the user, see [memberSheet]. */
    fun openMember(member: GroupMemberWithFullName) {
        openedMember.value = member
    }

    /**
     * Closes the open sheet, unless a contact request is being sent from it: what comes of it is
     * told there. What it told goes with it.
     */
    fun closeMember() {
        if (_isSendingRequest.value) return

        openedMember.value = null
        requestError.value = null
        isRequestTaken.value = false
    }

    /**
     * Asks [member], whose sheet is open, to become a contact, see
     * [GroupSettingsCallBacks.sendContactRequest], telling why not in the sheet, which stays open
     * either way.
     */
    fun sendContactRequest(member: GroupMemberWithFullName) = viewModelScope.launch {
        if (_isSendingRequest.value) return@launch

        _isSendingRequest.value = true
        requestError.value = null

        try {
            val error = groupSettingsCallBacks.sendContactRequest(groupUuid = groupUuid, userUuid = member.member.userUuid)

            // Before the spinner goes, so that the sheet never offers the request again meanwhile.
            isRequestTaken.value = isRequestOut(error)
            requestError.value = error
        } finally {
            _isSendingRequest.value = false
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

    /** Asks the user to confirm leaving the group, see [leave]. */
    fun askToLeave() {
        isLeaveAsked = true
    }

    /**
     * Drops the leave the user was asked to confirm, unless it is under way: what comes of it is
     * told once answered.
     */
    fun cancelLeave() {
        if (_isChanging.value) return

        isLeaveAsked = false
    }

    /**
     * Takes the user out of the group, see [GroupSettingsCallBacks.leaveGroup], and closes the
     * confirmation once answered, telling why not if it did not go through. Nothing more once it
     * did: the group, read again, is left, and the screen shows it read-only from then on, see
     * [GroupWithMembers.isLeft].
     */
    fun leave() = viewModelScope.launch {
        if (_isChanging.value) return@launch

        _isChanging.value = true

        try {
            val result = groupSettingsCallBacks.leaveGroup(groupUuid = groupUuid)

            isLeaveAsked = false

            if (result is LeaveResult.Failed) {
                _notices.send(MembershipChangeResult.Failed(MembershipChangeError.Failure(result.failure)))
            }
        } finally {
            _isChanging.value = false
        }
    }
}

data class GroupSettingsState(
    val groupWithMembers: GroupWithMembers? = null,
    val membersWithFullName: List<GroupMemberWithFullName> = emptyList(),
)

/**
 * Emits the settings of [group], its members named as [nameOf] tells, see [displayNames], each
 * time a name changes: a member the user is put in a group with by somebody else is named once a
 * group sync reads their profile, which can be after the screen opened.
 */
fun groupSettingsState(
    group: GroupWithMembers,
    nameOf: (userUuid: String) -> Flow<String?>,
): Flow<GroupSettingsState> {
    val members = group.members.filter { it.userUuid.isNotBlank() }

    return displayNames(userUuids = members.map { it.userUuid }, nameOf = nameOf).map { names ->
        GroupSettingsState(
            groupWithMembers = group,
            membersWithFullName = members.map { member ->
                GroupMemberWithFullName(member = member, fullName = names[member.userUuid])
            },
        )
    }
}

/**
 * The member whose sheet is open, [opened] as tapped, as the group's [members] list them now:
 * under the name read last, which can have come with the sheet open. As tapped once the group
 * lists them no more, a sync reading their removal with the sheet open, until it closes.
 */
fun shownMember(
    opened: GroupMemberWithFullName,
    members: List<GroupMemberWithFullName>,
): GroupMemberWithFullName = members.find { it.member.userUuid == opened.member.userUuid } ?: opened

/**
 * Emits what the sheet of [opened] shows of the group, as the settings, [states], tell it: the
 * group, whether it still vouches for its members and the user is still the one to remove them,
 * and [opened] as it lists them, see [shownMember], again only once either changes. Nothing until
 * the group is read.
 */
fun sheetMember(
    opened: GroupMemberWithFullName,
    states: Flow<GroupSettingsState>,
): Flow<Pair<GroupWithMembers, GroupMemberWithFullName>> = states
    .mapNotNull { state ->
        state.groupWithMembers?.let { group ->
            group to shownMember(opened = opened, members = state.membersWithFullName)
        }
    }
    .distinctUntilChanged()

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
 * are no longer in either, which they did not create, its creator never being removed from it nor
 * leaving it, see [GroupWithMembers.canChangeMembers].
 */
fun GroupWithMembers.memberActions(): MemberActions = when {
    !canChangeMembers -> MemberActions.NONE
    hasPendingChange -> MemberActions.WAITING
    else -> MemberActions.AVAILABLE
}

/**
 * Whether the settings of [this] offer the user to leave it, see [GroupWithMembers.canLeave]: a
 * group they are still in and did not create, which is never one they may change the members of,
 * see [memberActions]. Its creator never leaves, as nobody else could change its members.
 */
fun GroupWithMembers.isLeavable(): Boolean = canLeave

/**
 * Whether the member [userUuid] can be removed by the user [selfUuid], given the [actions] they
 * have: anybody but them, the group's creator, who never leaves it, as nobody else could change
 * its members.
 */
fun isRemovable(userUuid: String, selfUuid: String?, actions: MemberActions): Boolean =
    actions == MemberActions.AVAILABLE && userUuid != selfUuid

/**
 * The sheet of [member], a member of the group other than the user, see
 * [GroupSettingsViewModel.memberSheet]: what it offers, [actions], in the order it shows them,
 * whether a contact request to them is on its way to the server, [isSendingRequest], which keeps
 * the sheet open and every action disabled meanwhile, and why the last one sent from it was not,
 * [requestError].
 */
data class MemberSheetState(
    val member: GroupMemberWithFullName,
    val actions: List<MemberSheetAction>,
    val isSendingRequest: Boolean,
    val requestError: ContactRequestError?,
)

/** What the sheet of a member offers, below who they are, see [memberSheetActions]. */
enum class MemberSheetAction {
    /** Their profile, as one of the user's contacts, see [MemberRelation.CONTACT]. */
    VIEW_PROFILE,

    /** The contact requests, where theirs waits for the user's answer, see [MemberRelation.REQUEST_RECEIVED]. */
    ANSWER_REQUEST,

    /** Nothing but telling that the user's request waits for their answer, see [MemberRelation.REQUEST_SENT]. */
    REQUEST_SENT,

    /** A contact request to them, see [MemberRelation.NONE]. */
    SEND_REQUEST,

    /** Their removal from the group, once confirmed, see [isRemovable]. */
    REMOVE,
}

/**
 * What the sheet of a member offers, in the order it shows them, given where the user stands with
 * them, [relation], whether the group vouches for who its members are on this device,
 * [canVouchForMembers], and whether the member can be removed by the user, [isRemovable]: what
 * their relation calls for first, then their removal.
 *
 * No request is sent to them from a group that does not vouch for them, see
 * [GroupWithMembers.canVouchForMembers]: one the user is no longer in, brought back after a
 * log-out, or whose creator is blocked. Nothing else on this device vouches for who they are, and
 * the request would be refused each time. Their sheet then shows who they are alone, unless they
 * are a contact, or a request either way waits.
 */
fun memberSheetActions(
    relation: MemberRelation,
    canVouchForMembers: Boolean,
    isRemovable: Boolean,
): List<MemberSheetAction> =
    listOfNotNull(
        when (relation) {
            MemberRelation.CONTACT -> MemberSheetAction.VIEW_PROFILE
            MemberRelation.REQUEST_RECEIVED -> MemberSheetAction.ANSWER_REQUEST
            MemberRelation.REQUEST_SENT -> MemberSheetAction.REQUEST_SENT
            MemberRelation.NONE -> MemberSheetAction.SEND_REQUEST.takeIf { canVouchForMembers }
        },
        MemberSheetAction.REMOVE.takeIf { isRemovable },
    )

/**
 * Where the sheet tells the user stands with a member, [relation] as read, once a request sent
 * from it is out to them, [isRequestTaken], see [isRequestOut]: sent, while the read still tells
 * nobody the user asked. It catches up a moment after the request returns, recorded as stored, or
 * not while the sheet is open when the server refused it as one was out already that this device
 * kept no record of, a log-out having taken it. [isRequestTaken] is dropped as soon as the read
 * tells anything else, see [GroupSettingsViewModel.memberSheet], so that it hides nothing since.
 */
fun shownRelation(relation: MemberRelation, isRequestTaken: Boolean): MemberRelation =
    if (isRequestTaken && relation == MemberRelation.NONE) MemberRelation.REQUEST_SENT else relation

/**
 * Whether a request of the user's is out to a member once the one sent from their sheet is
 * answered, [error], null when the server took it: so it is too when it was refused as one was
 * out already, see [ContactRequestError.ALREADY_PENDING]. Offered again, the request would only
 * spend another of their key packages each time, for the same refusal.
 */
fun isRequestOut(error: ContactRequestError?): Boolean =
    error == null || error == ContactRequestError.ALREADY_PENDING

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
