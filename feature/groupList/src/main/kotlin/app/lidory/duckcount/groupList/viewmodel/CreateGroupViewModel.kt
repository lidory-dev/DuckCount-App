package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateGroupViewModel(
    val groupUuid: String?,
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    val currencies: List<Currency> = groupListCallBacks.getCurrencies()

    /** Localized display name for an ISO 4217 currency code (e.g. "USD" -> "US Dollar"). */
    fun currencyDisplayName(code: String): String = groupListCallBacks.getCurrencyDisplayName(code)

    private val _state = MutableStateFlow<CreateGroupState>(CreateGroupState.Loading)
    val state: StateFlow<CreateGroupState> = _state.asStateFlow()

    private val content: CreateGroupState.Content?
        get() = _state.value as? CreateGroupState.Content

    private fun updateContent(transform: (CreateGroupState.Content) -> CreateGroupState.Content) {
        _state.update { current ->
            if (current is CreateGroupState.Content) transform(current) else current
        }
    }

    val maxTitleLength: Int = groupListCallBacks.maxTitleLength
    val maxDescriptionLength: Int = groupListCallBacks.maxDescriptionLength

    /** The most members the group may have, the user included: see [GroupListCallBacks.maxMembers]. */
    val maxMembers: Int = groupListCallBacks.maxMembers

    // True once the group is created, or what the user edited of it saved. The screen must wait for
    // this before navigating: navigating away clears this ViewModel and cancels viewModelScope,
    // which would otherwise cut the creation, or the saving, short. A state rather than an event:
    // the creation can complete while the app is in the background, where the screen cannot
    // navigate, and the screen then closes once the user is back.
    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    // True once the group being edited is gone, deleted under the screen by a creation the server
    // refused, see GroupListCallBacks.getGroupWithMembers, or the user was removed from it, which
    // leaves it read-only. The screen closes then, as once a group is created: there is nothing
    // left to edit.
    private val _isGone = MutableStateFlow(false)
    val isGone: StateFlow<Boolean> = _isGone.asStateFlow()

    // True while the group is being created, so that the confirm button can show an in-place
    // spinner and stay disabled until it is.
    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    // Emitted with the reason when the creation failed, so that the screen can tell the user why,
    // and who it was about.
    private val _showError = Channel<GroupCreationError>(Channel.BUFFERED)
    val showError: Flow<GroupCreationError> = _showError.receiveAsFlow()

    private var _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    var isMemberDialogShown by mutableStateOf(false)

    // Who is picked to be in the group besides the user, who is in it whatever is picked, so never
    // among them: the picker lists only the people the user can put in a group, see
    // GroupListCallBacks.getInvitableUsersFiltered, which they are not.
    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = _searchQuery
        .flatMapLatest { query ->
            groupListCallBacks.getInvitableUsersFiltered(filter = query)
        }
        .map { users ->
            UserListState(users)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserListState(),
        )

    init {
        // Once, and not whenever the form is collected again: coming back to the app after a few
        // seconds away would otherwise read the group back over what the user typed.
        viewModelScope.launch {
            val authenticatedUserUuid = getAuthenticatedUserUuid() ?: ""

            _state.value = groupUuid?.let { uuid ->
                // Gone already, the screen opened from one still showing it.
                val group = groupListCallBacks.getGroupWithMembers(uuid).first() ?: run {
                    _isGone.value = true

                    return@launch
                }

                CreateGroupState.Content(
                    title = group.title,
                    description = group.description,
                    currency = group.currency,
                    // Changed from the group's settings, by its creator alone, never here.
                    members = emptyList(),
                    authenticatedUserUuid = authenticatedUserUuid,
                )
            } ?: run {
                CreateGroupState.Content(
                    title = "",
                    description = "",
                    // As the catalogue holds it: the group is sealed with the currency's UUID alone,
                    // which every member looks up in theirs.
                    currency = groupListCallBacks.getDefaultCurrency(),
                    members = withFullNames(
                        listOf(
                            GroupMember(
                                userUuid = authenticatedUserUuid,
                                groupUuid = "",
                                isAdmin = true,
                            ),
                        ),
                    ),
                    authenticatedUserUuid = authenticatedUserUuid,
                )
            }

            // Read once, for the form to keep what the user typed, and watched from then on for the
            // group going, or the user being removed from it, see isGone.
            if (groupUuid != null) {
                groupListCallBacks.getGroupWithMembers(groupUuid).first { it == null || it.isLeft }

                _isGone.value = true
            }
        }
    }

    fun getAuthenticatedUserUuid(): String? {
        return groupListCallBacks.getAuthenticatedUserUuid()
    }

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

    fun toggleSelectedUser(uuid: String) {
        _selectedUuids.update { selected ->
            toggledSelection(selected = selected, uuid = uuid, maxMembers = maxMembers)
        }
    }

    fun updateTitle(title: String) {
        updateContent {
            it.copy(title = title)
        }
    }

    fun updateDescription(description: String) {
        updateContent {
            it.copy(description = description)
        }
    }

    /** Lists the user and [uuids], the members picked besides them, in the form. */
    fun onMembersSelected(uuids: Set<String>) {
        viewModelScope.launch {
            val content = content ?: return@launch

            val kept = keptMembers(
                members = content.members,
                selfUuid = content.authenticatedUserUuid,
                selectedUuids = uuids,
            )
            val keptUuids = kept.mapTo(HashSet()) { it.member.userUuid }

            val added = withFullNames(
                (uuids - keptUuids).map { GroupMember(userUuid = it, groupUuid = "", isAdmin = false) },
            )

            updateContent { it.copy(members = kept + added) }
        }
    }

    fun removeMember(member: GroupMember) {
        updateContent {
            it.copy(
                members = it.members.filterNot { memberWithFullName -> memberWithFullName.member.userUuid == member.userUuid },
            )
        }
        _selectedUuids.update { selected -> selected - member.userUuid }
    }

    private suspend fun withFullNames(members: List<GroupMember>): List<GroupMemberWithFullName> = coroutineScope {
        members
            .map { member ->
                async {
                    GroupMemberWithFullName(
                        member = member,
                        fullName = groupListCallBacks.getUserFullName(member.userUuid).first(),
                    )
                }
            }
            .awaitAll()
    }

    fun createGroup() = viewModelScope.launch {
        // Not once created either: each attempt is a group of its own, so a second one would
        // create the same group twice.
        if (_isCreating.value || _isSaved.value) return@launch

        val content = content ?: return@launch

        _isCreating.value = true

        try {
            val error = groupListCallBacks.createGroup(
                title = content.title,
                description = content.description,
                members = content.members.map { it.member },
                currency = content.currency,
            )

            if (error == null) _isSaved.value = true else _showError.send(error)
        } finally {
            _isCreating.value = false
        }
    }

    /** Saves the title and the description the user gave the group being edited, see [isSaved]. */
    fun updateGroup() = viewModelScope.launch {
        val content = content ?: return@launch

        groupUuid?.let {
            groupListCallBacks.updateGroup(
                uuid = groupUuid,
                title = content.title,
                description = content.description,
            )

            _isSaved.value = true
        }
    }

    /** Picks [currency], one of [currencies], kept as the catalogue holds it, as the default one is. */
    fun updateCurrency(currency: Currency) {
        updateContent {
            it.copy(currency = currency)
        }
    }
}

sealed interface CreateGroupState {
    data object Loading : CreateGroupState
    data class Content(
        val title: String,
        val description: String,
        val currency: Currency,

        /** Who the group to create is made of, the user first: nobody for a group being edited. */
        val members: List<GroupMemberWithFullName>,
        val authenticatedUserUuid: String,
    ) : CreateGroupState
}

data class UserListState(
    val users: List<User> = emptyList(),
)

/**
 * Whether [this] holds a group to confirm: a title and a description within [maxTitleLength] and
 * [maxDescriptionLength], and for a group to create, [isNewGroup], the user and somebody else in
 * it. A group being edited keeps its members, which its settings change, see [members].
 */
fun CreateGroupState.Content.canConfirm(
    maxTitleLength: Int,
    maxDescriptionLength: Int,
    isNewGroup: Boolean,
): Boolean =
    (!isNewGroup || members.size >= 2) &&
        title.length in (1..maxTitleLength) &&
        description.length in (0..maxDescriptionLength)

/**
 * How many members the group has with [selectedUuids] picked besides the [currentMemberCount] it
 * has already: the user alone for a group they create, who is in every group of theirs, so counts
 * against the limit as much as anybody picked.
 */
fun memberCount(selectedUuids: Set<String>, currentMemberCount: Int = 1): Int =
    selectedUuids.size + currentMemberCount

/**
 * Whether [uuid] can be picked, or unpicked: always once picked, so that the selection can still
 * shrink once full, and otherwise while [selectedUuids] leaves room for one more within
 * [maxMembers], the [currentMemberCount] members included, see [memberCount].
 */
fun isSelectable(uuid: String, selectedUuids: Set<String>, maxMembers: Int, currentMemberCount: Int = 1): Boolean =
    uuid in selectedUuids || memberCount(selectedUuids, currentMemberCount) < maxMembers

/**
 * [selected] with [uuid] taken out, or added when that keeps the group within [maxMembers], the
 * [currentMemberCount] members included: past the limit, picking one more does nothing, and the
 * picker shows that card disabled, see [isSelectable].
 */
fun toggledSelection(
    selected: Set<String>,
    uuid: String,
    maxMembers: Int,
    currentMemberCount: Int = 1,
): Set<String> = when {
    uuid in selected -> selected - uuid
    memberCount(selected, currentMemberCount) < maxMembers -> selected + uuid
    else -> selected
}

/**
 * The members of [members] the form keeps once [selectedUuids] are picked besides the user,
 * [selfUuid], in the order it listed them: the user, whatever is picked, and whoever is still
 * picked. Whoever is picked and not listed yet comes after them.
 */
fun keptMembers(
    members: List<GroupMemberWithFullName>,
    selfUuid: String,
    selectedUuids: Set<String>,
): List<GroupMemberWithFullName> =
    members.filter { it.member.userUuid == selfUuid || it.member.userUuid in selectedUuids }
