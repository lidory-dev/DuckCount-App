package group

import app.lidory.duckcount.GroupLimits
import app.lidory.duckcount.calculateNetDebtsPerUser
import app.lidory.duckcount.getTransactionForGroup
import authentication.NetworkConnectivity
import authenticationUtils.getAuthenticatedUserUuid
import database.data.repository.ContactRequestRepository
import database.data.repository.ExpenseRepository
import database.data.repository.UserRepository
import database.data.repository.GroupRepository
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.Group
import database.domain.GroupWithMembers
import database.domain.User
import expense.currentTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import sync.ForegroundSync
import util.Result

/** Who owes whom in a group, as its balance and settle-up tabs show it. */
class GroupBalancesSnapshot(
    /** What each person comes to, by UUID, as the library writes a signed amount: positive when owed. */
    val netAmounts: Map<String, String>,

    /** The group's current members, everybody else in [netAmounts] being a former member. */
    val memberUuids: List<String>,

    /** The repayments that would settle the group, as few as the library can make them. */
    val debts: List<GroupDebt>,

    val currencySymbol: String,
)

/** One repayment that settles a group: [debtorUuid] pays [creditorUuid] [amount]. */
class GroupDebt(val debtorUuid: String, val creditorUuid: String, val amount: String)

/**
 * What the iOS group screens run, with the same shared classes and in the same order as Android's
 * `GroupListCallBacksImpl`, `GroupSettingsCallBacksImpl`, `GroupBalanceCallBacksImpl` and
 * `GroupSettleUpCallBacksImpl`, which live in the Android app, and with their failures told as
 * those tell them, see [GroupFailure].
 *
 * Nothing here throws: on iOS, a Kotlin exception reaching Swift ends the app. The keychain throws
 * an [IllegalStateException] once the device locks, which some of the shared classes catch and some
 * do not, and a few check what the screens never ask for with `require`; each write catches both,
 * and tells them as unknown. Android's screens have the same preconditions, which its view models
 * meet, and a locked phone shows none of them.
 *
 * Unlike Android, every refresh syncs the groups themselves, see [ForegroundSync]: no push keeps
 * them current, and a group's expenses cannot be read past an epoch whose Commit was not.
 */
class GroupManagement(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
    private val contactRequestRepository: ContactRequestRepository,
    private val groupCreator: GroupCreator,
    private val groupEditor: GroupEditor,
    private val groupHistory: GroupHistory,
    private val groupMembershipChanger: GroupMembershipChanger,
    private val groupLeaver: GroupLeaver,
    private val groupDeleter: GroupDeleter,
    private val foregroundSync: ForegroundSync,
    private val networkConnectivity: NetworkConnectivity,
) {

    val maxTitleLength: Int = GroupLimits.MAX_TITLE_LENGTH

    val maxDescriptionLength: Int = GroupLimits.MAX_DESCRIPTION_LENGTH

    /** The most members a group may have, the user included. */
    val maxMembers: Int = GroupLimits.MAX_MEMBERS

    /** The currencies a group can be created with. */
    val currencies: List<Currency> = CurrencyCatalog.defaultCurrencies

    /** The currency a new group starts in. */
    val defaultCurrency: Currency = CurrencyCatalog.defaultCurrency

    /** Localized, human-readable name for an ISO 4217 currency [code]. */
    fun currencyDisplayName(code: String): String = database.domain.currencyDisplayName(code)

    /** The user's UUID, or null when there is none or the device is locked. */
    fun authenticatedUserUuid(): String? = guarded("authenticatedUserUuid") { getAuthenticatedUserUuid() }

    /**
     * Every group on the device, left and deleted ones included, in the order Android lists them,
     * the order they are stored in, see `GroupDao.getAllAsFlow`.
     */
    fun groups(): Flow<List<Group>> = groupRepository.getAll()

    /**
     * The group [uuid] with its members and what the user may do with it, as it changes, or null once
     * there is no such group on the device. Read lazily: the repository reads the user's UUID as it is
     * asked for the flow, which a locked device would throw from.
     */
    fun group(uuid: String): Flow<GroupWithMembers?> =
        flow { emitAll(groupRepository.getWithMembers(uuid = uuid)) }
            .catch { failure ->
                // A locked device ends the stream rather than the app; the screen keeps what it shows.
                if (failure !is IllegalStateException) throw failure
                warn("group: could not read the group: $failure")
            }

    /** How many of the group [groupUuid]'s expenses this device cannot read yet, which the balances leave out. */
    fun unreadableExpenseCount(groupUuid: String): Flow<Int> =
        expenseRepository.getUnreadableGroupExpenseCount(groupUuid = groupUuid)

    /**
     * The user's contacts whose name contains [filter], whom a group can be created with or a member
     * added from, see [UserRepository.getInvitableFiltered].
     */
    fun invitableContacts(filter: String): Flow<List<User>> = userRepository.getInvitableFiltered(filter = filter)

    /**
     * The name to show for each of [userUuids] that this device holds one for, as they change: a
     * contact's, or the one a member of a shared group gave their profile, which reaches the device
     * as its group sync reads it.
     */
    fun fullNames(userUuids: List<String>): Flow<Map<String, String>> {
        val distinct = userUuids.distinct()

        if (distinct.isEmpty()) return flowOf(emptyMap())

        val names = distinct.map { uuid -> userRepository.getDisplayName(uuid).map { name -> uuid to name } }

        return combine(names) { pairs -> pairs.mapNotNull { (uuid, name) -> name?.let { uuid to it } }.toMap() }
            .distinctUntilChanged()
    }

    /** Where the user stands with [userUuid], a member of one of their groups. */
    fun relation(userUuid: String): Flow<MemberRelation> =
        contactRequestRepository.relationTo(userUuid = userUuid).map { it.toMemberRelation() }

    /**
     * Who owes whom in the group [groupUuid], from its members and its expenses that are not deleted,
     * as the library works it out, as Android's balance and settle-up tabs do, or null when it cannot:
     * the group is gone, or the library refused what the device holds. Worked out off the main thread.
     *
     * The currency comes with the group's members rather than from `GroupRepository.get`, which waits
     * forever for a group the device no longer holds.
     */
    fun balances(groupUuid: String): Flow<GroupBalancesSnapshot?> =
        combine(group(groupUuid), expenseRepository.getAllFromGroupWithoutDeletedOnes(groupUuid = groupUuid)) { group, expenses ->
            group ?: return@combine null

            try {
                GroupBalancesSnapshot(
                    netAmounts = calculateNetDebtsPerUser(
                        currency = group.currency,
                        members = group.groupMembers,
                        expenses = expenses,
                    ),
                    memberUuids = group.groupMembers.map { it.userUuid },
                    debts = getTransactionForGroup(
                        currency = group.currency,
                        members = group.groupMembers,
                        expenses = expenses,
                    ).map { GroupDebt(debtorUuid = it.debtorUuid, creditorUuid = it.creditorUuid, amount = it.amount) },
                    currencySymbol = group.currency.symbol,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                warn("balances: the library could not work out the balances: $exception")

                null
            }
        }.flowOn(Dispatchers.Default)

    /**
     * Brings the contacts, the groups and their expenses up to date with the server, see
     * [ForegroundSync]. Failures are not reported: the device keeps showing what it has.
     */
    suspend fun refresh() {
        foregroundSync.sync()
    }

    /** Brings the group [groupUuid] and its expenses up to date, see [ForegroundSync.syncGroup]. */
    suspend fun refreshGroup(groupUuid: String) {
        foregroundSync.syncGroup(groupUuid = groupUuid)
    }

    /**
     * Creates a group with the user and [memberUuids], in the order the form lists them, see
     * [GroupCreator.create]: kept on the device once created, and sent until the server takes it.
     * Whoever the server refused is named, by the names this device shows for them, hidden or not.
     */
    suspend fun create(title: String, description: String, currency: Currency, memberUuids: List<String>): GroupCreationOutcome =
        guarded("create", GroupCreationOutcome.Failed(GroupFailure.UNKNOWN)) {
            val selfUuid = getAuthenticatedUserUuid()
            val inviteeUuids = memberUuids.filter { it != selfUuid }.distinct()

            // What GroupCreator requires, which the form ensures: told as unknown rather than thrown.
            if (inviteeUuids.isEmpty() || inviteeUuids.size >= GroupLimits.MAX_MEMBERS) {
                return@guarded GroupCreationOutcome.Failed(GroupFailure.UNKNOWN)
            }

            when (
                val creation = groupCreator.create(
                    title = title.trim(),
                    description = description.trim(),
                    currency = currency,
                    inviteeUuids = inviteeUuids.toSet(),
                )
            ) {
                is Result.Success -> GroupCreationOutcome.Created(groupUuid = creation.data)

                is Result.Error -> {
                    warn("create: could not create the group: ${creation.error}")

                    creation.error.toOutcome(
                        isOnline = networkConnectivity.isOnline(),
                        memberUuids = inviteeUuids,
                        fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
                    )
                }
            }
        }

    /** Gives the group [uuid] [title] and [description], see [GroupEditor.edit]: null once the server took it. */
    suspend fun edit(uuid: String, title: String, description: String): GroupEditFailure? =
        guarded("edit", GroupEditFailure.UNKNOWN) {
            when (val edit = groupEditor.edit(groupUuid = uuid, title = title.trim(), description = description.trim())) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("edit: could not edit the group: ${edit.error}")

                    edit.error.toFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    /** The versions of the group [uuid]'s title and description, read from the server, see [GroupHistory.versions]. */
    suspend fun history(uuid: String): GroupHistoryOutcome =
        guarded("history", GroupHistoryOutcome.Unavailable(GroupHistoryFailure.DEVICE_LOCKED)) {
            when (val history = groupHistory.versions(groupUuid = uuid)) {
                is Result.Success -> GroupHistoryOutcome.Versions(versions = history.data)

                is Result.Error -> {
                    warn("history: could not read the history: ${history.error}")

                    GroupHistoryOutcome.Unavailable(history.error.toFailure(isOnline = networkConnectivity.isOnline()))
                }
            }
        }

    /** Adds [userUuids], in the order the picker lists them, to the group [groupUuid], see [GroupMembershipChanger.add]. */
    suspend fun addMembers(groupUuid: String, userUuids: List<String>): MemberChangeOutcome =
        guarded("addMembers", MemberChangeOutcome.Failed(GroupFailure.UNKNOWN)) {
            if (userUuids.isEmpty()) return@guarded MemberChangeOutcome.Failed(GroupFailure.UNKNOWN)

            groupMembershipChanger.add(groupUuid = groupUuid, userUuids = userUuids.toSet())
                .toOutcome(memberUuids = userUuids, action = "addMembers")
        }

    /** Removes [userUuid], never the user, from the group [groupUuid], see [GroupMembershipChanger.remove]. */
    suspend fun removeMember(groupUuid: String, userUuid: String): MemberChangeOutcome =
        guarded("removeMember", MemberChangeOutcome.Failed(GroupFailure.UNKNOWN)) {
            // Nobody removes themselves from a group, which the changer requires.
            if (userUuid == getAuthenticatedUserUuid()) return@guarded MemberChangeOutcome.Failed(GroupFailure.UNKNOWN)

            groupMembershipChanger.remove(groupUuid = groupUuid, userUuids = setOf(userUuid))
                .toOutcome(memberUuids = listOf(userUuid), action = "removeMember")
        }

    /**
     * Leaves the group [groupUuid], see [GroupLeaver.leave]: null once this device recorded it,
     * whatever the server did, or else nothing written, for a reason the user cannot act on.
     */
    suspend fun leave(groupUuid: String): GroupFailure? =
        guarded("leave", GroupFailure.UNKNOWN) {
            when (val left = groupLeaver.leave(groupUuid = groupUuid)) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("leave: could not leave the group: ${left.error}")

                    GroupFailure.UNKNOWN
                }
            }
        }

    /** Deletes the group [groupUuid] for everybody, on the server first, see [GroupDeleter.delete]. */
    suspend fun delete(groupUuid: String): GroupFailure? =
        guarded("delete", GroupFailure.UNKNOWN) {
            when (val deleted = groupDeleter.delete(groupUuid = groupUuid)) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("delete: could not delete the group: ${deleted.error}")

                    deleted.error.toFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    /** Deletes the group [groupUuid], which the user is out of, from this device only, see [GroupDeleter.forgetLeft]. */
    suspend fun forget(groupUuid: String): GroupFailure? =
        guarded("forget", GroupFailure.UNKNOWN) {
            when (val forgotten = groupDeleter.forgetLeft(groupUuid = groupUuid)) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("forget: could not delete the group from this device: ${forgotten.error}")

                    forgotten.error.toFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    /**
     * Sends [userUuid], a member of the group [groupUuid], a contact request, the group vouching for
     * who they are, see [ContactRequestRepository.sendContactRequest].
     */
    suspend fun sendContactRequest(groupUuid: String, userUuid: String): MemberContactRequestFailure? =
        guarded("sendContactRequest", MemberContactRequestFailure.UNKNOWN) {
            when (val request = contactRequestRepository.sendContactRequest(userUuid = userUuid, groupUuid = groupUuid)) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("sendContactRequest: could not send the request: ${request.error}")

                    request.error.toMemberFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    /**
     * Records that [debtorUuid] paid the user [amount], as Android's settle-up tab does: a settlement
     * paid by the debtor to the user, who alone records it, dated now as every device reads it back.
     */
    suspend fun recordSettlement(groupUuid: String, debtorUuid: String, amount: String): GroupFailure? =
        guarded("recordSettlement", GroupFailure.UNKNOWN) {
            val recorded = expenseRepository.recordGroupSettlement(
                groupUuid = groupUuid,
                payerUuid = debtorUuid,
                amount = amount,
                timestamp = currentTimestamp(),
            )

            when (recorded) {
                is Result.Success -> null

                is Result.Error -> {
                    warn("recordSettlement: could not record the settlement: ${recorded.error}")

                    recorded.error.toGroupFailure(isOnline = networkConnectivity.isOnline())
                }
            }
        }

    private suspend fun Result<MembershipChangeOutcome, GroupMembershipError>.toOutcome(
        memberUuids: List<String>,
        action: String,
    ): MemberChangeOutcome = when (this) {
        is Result.Success -> data.toOutcome()

        is Result.Error -> {
            warn("$action: could not change the members: $error")

            // Hidden or not: a member who deleted their account has been forgotten for it by now,
            // which hides them and keeps their name.
            error.toOutcome(
                isOnline = networkConnectivity.isOnline(),
                memberUuids = memberUuids,
                fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
            )
        }
    }

    /**
     * Runs [block], or tells what it threw as [fallback]: a locked device, or a precondition of the
     * shared layer's, either of which reaching Swift would end the app.
     */
    private inline fun <T> guarded(caller: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IllegalStateException) {
            warn("$caller: the device locked, or a check failed: $exception")
            fallback
        } catch (exception: IllegalArgumentException) {
            warn("$caller: a precondition failed: $exception")
            fallback
        }

    private inline fun <T> guarded(caller: String, block: () -> T?): T? = guarded(caller, null, block)

    private fun warn(message: String) = println("$TAG: $message")

    private companion object {
        const val TAG = "GroupManagement"
    }
}
