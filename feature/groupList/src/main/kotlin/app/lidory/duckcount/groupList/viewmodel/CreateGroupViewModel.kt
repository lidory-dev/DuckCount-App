package app.lidory.duckcount.groupList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateGroupViewModel(
    val groupUuid: String?,
    val groupListCallBacks: GroupListCallBacks,
) : ViewModel() {

    // TODO: remove when currencyRepository is implemented
    @OptIn(ExperimentalUuidApi::class)
    val currencies =
        listOf(
            Currency(uuid = Uuid.random().toString(), "EUR", "€", true),
            Currency(uuid = Uuid.random().toString(), "USD", "$", true),
            Currency(uuid = Uuid.random().toString(), "JPY", "¥", false),
        )

    private val _state = MutableStateFlow<CreateGroupState>(CreateGroupState.Loading)
    val state: StateFlow<CreateGroupState> = _state.onStart {
        val authenticatedUserUuid = getAuthenticatedUserUuid() ?: ""

        groupUuid?.let { uuid ->
            val group = groupListCallBacks.getGroupWithMembers(uuid).first()

            _state.value = CreateGroupState.Content(
                title = group.title,
                description = group.description,
                currency = Currency(
                    name = group.currency.name,
                    symbol = group.currency.symbol,
                    hasSubunits = group.currency.hasSubunits,
                ),
                members = emptyList(),
                authenticatedUserUuid = authenticatedUserUuid,
            )
            updateMembers(group.members)
        } ?: run {
            val defaultCurrency = currencies.first()

            _state.value = CreateGroupState.Content(
                title = "",
                description = "",
                currency = Currency(
                    name = defaultCurrency.name,
                    symbol = defaultCurrency.symbol,
                    hasSubunits = defaultCurrency.hasSubunits,
                ),
                members = emptyList(),
                authenticatedUserUuid = authenticatedUserUuid,
            )
            updateMembers(
                listOf(
                    GroupMember(
                        userUuid = authenticatedUserUuid,
                        groupUuid = "",
                        isAdmin = true,
                    ),
                ),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = CreateGroupState.Loading,
    )

    private val content: CreateGroupState.Content?
        get() = _state.value as? CreateGroupState.Content

    private fun updateContent(transform: (CreateGroupState.Content) -> CreateGroupState.Content) {
        _state.update { current ->
            if (current is CreateGroupState.Content) transform(current) else current
        }
    }

    val maxTitleLength: Int = groupListCallBacks.maxTitleLength
    val maxDescriptionLength: Int = groupListCallBacks.maxDescriptionLength

    private var _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    var isMemberDialogShown by mutableStateOf(false)

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

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

    fun getAuthenticatedUserUuid(): String? {
        return groupListCallBacks.getAuthenticatedUserUuid()
    }

    fun isOwnerInMembers(): Boolean {
        return content?.members?.any { it.member.userUuid == getAuthenticatedUserUuid() } == true
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
        updateContent {
            it.copy(title = title)
        }
    }

    fun updateDescription(description: String) {
        updateContent {
            it.copy(description = description)
        }
    }

    fun updateMembers(members: List<GroupMember>) {
        viewModelScope.launch {
            val currentMembers = content?.members.orEmpty()
            val existingUuids = currentMembers.map { it.member.userUuid }.toSet()
            val newUuids = members.map { it.userUuid }.toSet()

            val kept = currentMembers.filter { it.member.userUuid in newUuids }
            val added = coroutineScope {
                members
                    .filterNot { it.userUuid in existingUuids }
                    .map { member ->
                        async {
                            val fullName = groupListCallBacks.getUserFullName(member.userUuid).first()
                                ?: member.userUuid
                            GroupMemberWithFullName(member, fullName = fullName)
                        }
                    }
                    .awaitAll()
            }

            updateContent { it.copy(members = kept + added) }
            _selectedUuids.update { newUuids }
        }
    }

    fun removeMember(member: GroupMember) {
        updateContent {
            it.copy(
                members = it.members.filterNot { memberWithFullName -> memberWithFullName.member.userUuid == member.userUuid },
            )
        }
        _selectedUuids.update { selected ->
            if (member.userUuid in selected) selected - member.userUuid else selected + member.userUuid
        }
    }

    fun createGroup() {
        val content = content ?: return

        viewModelScope.launch {
            groupListCallBacks.createGroup(
                title = content.title,
                description = content.description,
                members = content.members.map { it.member },
                currency = content.currency,
            )
        }
    }

    fun updateGroup() = viewModelScope.launch {
        val content = content ?: return@launch

        groupUuid?.let {
            groupListCallBacks.updateGroup(
                GroupWithMembers(
                    uuid = groupUuid,
                    title = content.title,
                    description = content.description,
                    members = content.members.map {
                        it.member.copy(groupUuid = groupUuid)
                    },
                    currency = content.currency,
                ),
            )
        }
    }

    fun updateCurrency(name: String, symbol: String, hasSubUnits: Boolean) {
        updateContent {
            it.copy(
                currency = Currency(
                    name = name, symbol = symbol, hasSubunits = hasSubUnits,
                ),
            )
        }
    }
}

sealed interface CreateGroupState {
    data object Loading : CreateGroupState
    data class Content(
        val title: String,
        val description: String,
        val currency: Currency,
        val members: List<GroupMemberWithFullName>,
        val authenticatedUserUuid: String,
    ) : CreateGroupState
}

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
