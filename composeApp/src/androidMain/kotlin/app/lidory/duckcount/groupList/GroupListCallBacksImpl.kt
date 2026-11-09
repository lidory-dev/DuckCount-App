package app.lidory.duckcount.groupList

import android.util.Log
import app.lidory.duckcount.GroupLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.GroupLimits.MAX_MEMBERS
import app.lidory.duckcount.GroupLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupCreationResult
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.push.ContactNotifier
import app.lidory.duckcount.push.ExpenseNotifier
import app.lidory.duckcount.push.GroupNotifier
import contact.ContactArrivals
import contact.ContactSync
import database.data.repository.ExpenseRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import database.domain.CurrencyCatalog
import database.domain.currencyDisplayName
import expense.GroupExpenseUpdates
import group.GroupCreator
import group.GroupEditor
import group.GroupExpenseSync
import group.GroupSync
import group.GroupSyncError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import util.Result
import group.GroupHistory as SharedGroupHistory

private const val TAG = "GroupListCallBacksImpl"

class GroupListCallBacksImpl(
    val groupRepository: GroupRepository,
    val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
    private val groupCreator: GroupCreator,
    private val groupEditor: GroupEditor,
    private val groupHistory: SharedGroupHistory,
    private val groupSync: GroupSync,
    private val groupExpenseSync: GroupExpenseSync,
    private val groupExpenseUpdates: GroupExpenseUpdates,
    private val groupNotifier: GroupNotifier,
    private val expenseNotifier: ExpenseNotifier,
    private val contactSync: ContactSync,
    private val contactArrivals: ContactArrivals,
    private val contactNotifier: ContactNotifier,
    private val networkConnectivity: NetworkConnectivity,
) : GroupListCallBacks {

    override val maxTitleLength: Int = MAX_TITLE_LENGTH

    override val maxDescriptionLength: Int = MAX_DESCRIPTION_LENGTH

    override val maxMembers: Int = MAX_MEMBERS

    override fun getAllGroups(): Flow<List<Group>> {
        return groupRepository.getAll()
            .map { groups ->
                groups.map { group ->
                    group.toGroupListModel()
                }
            }
    }

    override fun getGroup(uuid: String): Flow<Group> {
        return groupRepository.get(uuid)
            .map { group ->
                group.toGroupListModel()
            }
    }

    override fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers?> {
        return groupRepository.getWithMembers(uuid).map { groupWithMembers ->
            groupWithMembers?.toGroupListModel()
        }
    }

    override fun getInvitableUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getInvitableFiltered(filter)
            .map { users ->
                users.map { user ->
                    user.toGroupListModel()
                }
            }
    }

    override fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>> {
        return userRepository.getAllFromGroupFiltered(filter, groupUuid)
            .map { users ->
                users.map { user ->
                    user.toGroupListModel()
                }
            }
    }

    override fun getUserFullName(uuid: String): Flow<String?> {
        return userRepository.getFullName(uuid)
    }

    override fun getCurrencies(): List<Currency> =
        CurrencyCatalog.defaultCurrencies.map { it.toGroupListModel() }

    override fun getDefaultCurrency(): Currency = CurrencyCatalog.defaultCurrency.toGroupListModel()

    override fun getCurrencyDisplayName(code: String): String = currencyDisplayName(code)

    override suspend fun createGroup(
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMember>,
    ): GroupCreationResult {
        // The form lists the user among the members, who creates the group rather than being invited.
        val selfUuid = getAuthenticatedUserUuid()
        val inviteeUuids = members.map { it.userUuid }.filter { it != selfUuid }

        return when (
            val creation = groupCreator.create(
                title = title,
                description = description,
                currency = currency.toDb(),
                inviteeUuids = inviteeUuids.toSet(),
            )
        ) {
            is Result.Success -> GroupCreationResult.Created(groupUuid = creation.data)

            is Result.Error -> {
                Log.w(TAG, "createGroup: could not create the group: ${creation.error}")

                // Hidden or not: a member who deleted their account has been forgotten for it by
                // now, which hides them and keeps their name, see
                // `message.DirectMessageSender.receiveAccountDeletions`.
                GroupCreationResult.Failed(
                    creation.error.toGroupCreationError(
                        isOnline = networkConnectivity.isOnline(),
                        memberUuids = inviteeUuids,
                        fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
                    ),
                )
            }
        }
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun updateGroup(uuid: String, title: String, description: String): GroupEditError? =
        when (val edit = groupEditor.edit(groupUuid = uuid, title = title, description = description)) {
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "updateGroup: could not edit the group: ${edit.error}")

                edit.error.toGroupEditError(isOnline = networkConnectivity.isOnline())
            }
        }

    override suspend fun getGroupHistory(uuid: String): GroupHistory =
        when (val history = groupHistory.versions(groupUuid = uuid)) {
            is Result.Success -> GroupHistory.Versions(versions = history.data.map { it.toGroupListModel() })

            is Result.Error -> {
                Log.w(TAG, "getGroupHistory: could not read the history: ${history.error}")

                GroupHistory.Unavailable(
                    error = history.error.toGroupHistoryError(isOnline = networkConnectivity.isOnline()),
                )
            }
        }

    override suspend fun refreshGroups() {
        val contactsBefore = contactArrivals.snapshot()

        // The contacts first, as the push sync takes them: a group is joined only from a contact,
        // so one this device had yet to take in would have their group declined for good.
        val contacts = contactSync.sync()

        contacts.failure?.let { error ->
            Log.w(TAG, "refreshGroups: could not sync the contacts: $error")
        }

        // Nothing is announced about the groups, the user is looking at them. The contacts are
        // not in front of them though, so what this sync took in of them is announced as the push
        // sync would have, and what it undid taken down.
        contactNotifier.notify(arrivals = contactArrivals.since(snapshot = contactsBefore))
        contactNotifier.forget(userUuids = contactArrivals.departures(snapshot = contactsBefore))

        // Nothing to tell the user about a failure: they keep seeing the groups they already
        // had, and the next refresh, or push, tries again.
        val groups = groupSync.sync(contactsTakenIn = contacts.isTakenIn)

        groups.failure?.let { failure ->
            Log.w(TAG, "refreshGroups: could not sync the groups: $failure")
        }

        // The device locked as the groups were synced: their expenses could only throw now, and
        // wait for the next refresh, or push, as in the push sync.
        if (groups.failure == GroupSyncError.DeviceLocked) return

        // Then their expenses, the groups having read the Commits that give the key of every epoch
        // they are sealed in: the first time after a sign-in, every group's, from the server.
        // Those are not in front of the user either, the list showing none, so what concerns them
        // is announced as the push sync would have. Telling what does reads the user's UUID, which the device
        // locking as the sync ran leaves unreadable too: nothing is announced then, a locked phone
        // posting nothing, and what that pass stored is not told about later either.
        val updates = unlessLocked {
            val groupExpenses = groupExpenseSync.sync()

            groupExpenses.failure?.let { failure ->
                Log.w(TAG, "refreshGroups: could not sync the groups' expenses: $failure")
            }

            groupExpenseUpdates.of(changes = groupExpenses.changes)
        } ?: return

        expenseNotifier.notifyGroupExpenses(updates = updates)
    }

    override suspend fun refreshGroupExpenses(uuid: String) {
        // Nothing announced: the user is looking at them. A failure is not reported either, as for
        // the groups: they keep seeing what they had.
        unlessLocked { groupExpenseSync.syncGroup(groupUuid = uuid) }?.failure?.let { failure ->
            Log.w(TAG, "refreshGroupExpenses: could not sync the group's expenses: $failure")
        }
    }

    override fun getUnreadableExpenseCount(uuid: String): Flow<Int> =
        expenseRepository.getUnreadableGroupExpenseCount(groupUuid = uuid)

    override fun groupsSeen() = groupNotifier.clearGroups()
}

/**
 * What [sync] comes to, or null when the device locked as it ran: what this device stores is read
 * with keys a locked device cannot use, and reading it throws an [IllegalStateException] then, see
 * [GroupSyncError.DeviceLocked], which would take the app down from the screen's scope. What it did
 * not get to is read by the next refresh, or push.
 */
private inline fun <T> unlessLocked(sync: () -> T): T? =
    try {
        sync()
    } catch (exception: CancellationException) {
        // The screen going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        Log.w(TAG, "unlessLocked: the device locked as the groups' expenses were synced")

        null
    }
