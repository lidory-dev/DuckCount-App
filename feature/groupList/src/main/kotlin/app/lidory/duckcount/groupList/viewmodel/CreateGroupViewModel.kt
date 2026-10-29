package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.android.ui.text.sortedByName
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.NobodyToPick
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
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

    // Snapshot state rather than a flow, as the search is: what the user types has to come back to
    // its field in the same frame as the edit. A flow collected by the screen hands it back a frame
    // late, the field meanwhile taking back the text it had, and its cursor with it.
    var state by mutableStateOf<CreateGroupState>(CreateGroupState.Loading)
        private set

    private val content: CreateGroupState.Content?
        get() = state as? CreateGroupState.Content

    private fun updateContent(transform: (CreateGroupState.Content) -> CreateGroupState.Content) {
        content?.let { state = transform(it) }
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
    // refused, see GroupListCallBacks.getGroupWithMembers, or the user is no longer in it, removed
    // or left, which leaves it read-only. The screen closes then, as once a group is created: there is nothing
    // left to edit.
    private val _isGone = MutableStateFlow(false)
    val isGone: StateFlow<Boolean> = _isGone.asStateFlow()

    // True while the group is being created, or what the user edited of it saved, so that the
    // confirm button can show an in-place spinner and stay disabled until it is.
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Emitted with the reason when the creation failed, so that the screen can tell the user why,
    // and who it was about.
    private val _showError = Channel<GroupCreationError>(Channel.BUFFERED)
    val showError: Flow<GroupCreationError> = _showError.receiveAsFlow()

    // Emitted with the reason when what the user edited was not saved, the form keeping what they
    // typed for them to save again.
    private val _showEditError = Channel<GroupEditError>(Channel.BUFFERED)
    val showEditError: Flow<GroupEditError> = _showEditError.receiveAsFlow()

    var searchQuery by mutableStateOf("")
        private set

    var isMemberDialogShown by mutableStateOf(false)

    // Who is picked to be in the group besides the user, who is in it whatever is picked, so never
    // among them: the picker lists only the people the user can put in a group, see
    // GroupListCallBacks.getInvitableUsersFiltered, which they are not.
    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

    @OptIn(ExperimentalCoroutinesApi::class)
    val usersState: StateFlow<UserListState> = combine(
        snapshotFlow { searchQuery }.flatMapLatest { query ->
            groupListCallBacks.getInvitableUsersFiltered(filter = query)
        },
        // Whatever is searched, to tell a user who has no contact to put in the group, see
        // nobodyToPick.
        groupListCallBacks.getInvitableUsersFiltered(filter = ""),
    ) { users, contacts ->
        UserListState(
            users = users.sortedByName(selfUuid = null, uuidOf = { it.uuid }, nameOf = { it.fullName }),
            nobodyToPick = nobodyToPick(contacts = contacts, memberUuids = emptySet()),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UserListState(),
    )

    init {
        // Once, and not whenever the form is collected again: coming back to the app after a few
        // seconds away would otherwise read the group back over what the user typed.
        viewModelScope.launch {
            val authenticatedUserUuid = getAuthenticatedUserUuid() ?: ""

            state = groupUuid?.let { uuid ->
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
                    shown = GroupInformation(title = group.title, description = group.description),
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

            // Read once, for the form to keep what the user typed, and watched from then on for
            // what the group shows, which the form saves nothing but a change of, see
            // CreateGroupState.Content.shown, and for the group going, or the user being out of
            // it, see isGone.
            if (groupUuid != null) {
                groupListCallBacks.getGroupWithMembers(groupUuid)
                    .onEach { group ->
                        if (group != null) {
                            updateContent {
                                it.copy(shown = GroupInformation(title = group.title, description = group.description))
                            }
                        }
                    }
                    .first { it == null || it.isLeft }

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
        searchQuery = ""
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
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

    /**
     * Lists the user and [uuids], the members picked besides them, in the form, the user first and
     * the others by name, see [sortedByName].
     */
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

            updateContent {
                it.copy(
                    members = (kept + added).sortedByName(
                        selfUuid = content.authenticatedUserUuid,
                        uuidOf = { member -> member.member.userUuid },
                        nameOf = { member -> member.fullName },
                    ),
                )
            }
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
        if (_isSaving.value || _isSaved.value) return@launch

        val content = content ?: return@launch

        _isSaving.value = true

        try {
            val error = groupListCallBacks.createGroup(
                title = content.title,
                description = content.description,
                members = content.members.map { it.member },
                currency = content.currency,
            )

            if (error == null) _isSaved.value = true else _showError.send(error)
        } finally {
            _isSaving.value = false
        }
    }

    /**
     * Saves the title and the description the user gave the group being edited, see [isSaved], or
     * tells why not, see [showEditError].
     */
    fun updateGroup() = viewModelScope.launch {
        // Not once saved either: a second one would be the next edit of the group, made over the
        // one just saved.
        if (_isSaving.value || _isSaved.value) return@launch

        val uuid = groupUuid ?: return@launch
        val content = content ?: return@launch

        _isSaving.value = true

        try {
            val error = groupListCallBacks.updateGroup(
                uuid = uuid,
                title = content.title,
                description = content.description,
            )

            if (error == null) _isSaved.value = true else _showEditError.send(error)
        } finally {
            _isSaving.value = false
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

        /**
         * The title and description the group being edited shows now, which the form saves
         * nothing but a change of, see [canConfirm]: read again each time they change, as when an
         * edit refused as outdated shows the version the server holds, or a sync an earlier edit
         * of the user's whose answer was lost. Null for a group to create.
         */
        val shown: GroupInformation? = null,
    ) : CreateGroupState {
        /**
         * [title] without the whitespace around it, as the group is saved with it, which the form
         * is checked by, see [canConfirm]. The field holds it as typed: trimmed as it is typed, a
         * space typed after a word would be swallowed, and the cursor moved back over it.
         */
        val trimmedTitle: String = title.trim()

        /** [description] without the whitespace around it, as [trimmedTitle] is [title]. */
        val trimmedDescription: String = description.trim()
    }
}

/** A group's title and description, see [CreateGroupState.Content.shown]. */
data class GroupInformation(
    val title: String,
    val description: String,
)

/**
 * Whom the member picker lists, [users], and why it lists nobody whatever is searched,
 * [nobodyToPick]: null while somebody can be picked, and while the lists are still being read.
 */
data class UserListState(
    val users: List<User> = emptyList(),
    val nobodyToPick: NobodyToPick? = null,
)

/**
 * Whether [this] holds a group to confirm, as it is saved, without the whitespace around its title
 * and description, see [CreateGroupState.Content.trimmedTitle]: a title and a description within
 * [maxTitleLength] and [maxDescriptionLength], and for a group to create, the user and somebody
 * else in it. A group being edited keeps its members, which its settings change, see [members],
 * and is confirmed only with a title or a description other than those it shows, see
 * [CreateGroupState.Content.shown]: an edit that changes nothing would still wake every other
 * member, and take a place in the group's history.
 */
fun CreateGroupState.Content.canConfirm(maxTitleLength: Int, maxDescriptionLength: Int): Boolean =
    when (shown) {
        // A group to create.
        null -> members.size >= 2
        else -> trimmedTitle != shown.title || trimmedDescription != shown.description
    } &&
        trimmedTitle.length in (1..maxTitleLength) &&
        trimmedDescription.length in (0..maxDescriptionLength)

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
 * [selfUuid]: the user, whatever is picked, and whoever is still picked. Whoever is picked and not
 * listed yet is added to them, the form listing them all by name, see
 * [CreateGroupViewModel.onMembersSelected].
 */
fun keptMembers(
    members: List<GroupMemberWithFullName>,
    selfUuid: String,
    selectedUuids: Set<String>,
): List<GroupMemberWithFullName> =
    members.filter { it.member.userUuid == selfUuid || it.member.userUuid in selectedUuids }
