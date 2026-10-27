package app.lidory.duckcount.groupList

import android.util.Log
import app.lidory.duckcount.GroupLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.GroupLimits.MAX_MEMBERS
import app.lidory.duckcount.GroupLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.groupList.callBacks.GroupListCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import app.lidory.duckcount.networking.NetworkConnectivity
import app.lidory.duckcount.push.ContactNotifier
import app.lidory.duckcount.push.GroupNotifier
import contact.ContactArrivals
import contact.ContactSync
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import database.domain.CurrencyCatalog
import database.domain.currencyDisplayName
import group.GroupCreator
import group.GroupSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import util.Result

private const val TAG = "GroupListCallBacksImpl"

class GroupListCallBacksImpl(
    val groupRepository: GroupRepository,
    val userRepository: UserRepository,
    private val groupCreator: GroupCreator,
    private val groupSync: GroupSync,
    private val groupNotifier: GroupNotifier,
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
    ): GroupCreationError? {
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
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "createGroup: could not create the group: ${creation.error}")

                // Hidden or not: a member who deleted their account has been forgotten for it by
                // now, which hides them and keeps their name, see
                // `message.DirectMessageSender.receiveAccountDeletions`.
                creation.error.toGroupCreationError(
                    isOnline = networkConnectivity.isOnline(),
                    memberUuids = inviteeUuids,
                    fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
                )
            }
        }
    }

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun updateGroup(uuid: String, title: String, description: String) {
        groupRepository.updateTitleAndDescription(uuid = uuid, title = title, description = description)
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
        groupSync.sync(contactsTakenIn = contacts.isTakenIn).failure?.let { failure ->
            Log.w(TAG, "refreshGroups: could not sync the groups: $failure")
        }
    }

    override fun groupsSeen() = groupNotifier.clearGroups()
}
