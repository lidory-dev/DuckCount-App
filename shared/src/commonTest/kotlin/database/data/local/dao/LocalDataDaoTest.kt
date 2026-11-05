package database.data.local.dao

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalDataDaoTest {

    /**
     * The keys and names the user's groups handed over belong to the account signed in, and a
     * log-out takes them along: the next account to sign in has no business with them, and gets
     * its own back from its vault.
     */
    @Test
    fun `a log-out wipes the members' profile keys`() = runBlocking<Unit> {
        val wiped = Wiped()

        wiped.deleteAll()

        assertTrue("MemberProfileEntity" in wiped.tables, "left behind: ${wiped.tables}")
    }

    /**
     * Every generation of the key of every group, and the signature keys recorded for its members,
     * go with the groups: the next account to sign in gets its own keys back from its copies and
     * the chain, and reads its own groups' trees.
     */
    @Test
    fun `a log-out wipes every generation of the groups' keys and the members' signature keys`() =
        runBlocking<Unit> {
            val wiped = Wiped()

            wiped.deleteAll()

            assertTrue("GroupKeyEntity" in wiped.tables, "left behind: ${wiped.tables}")
            assertTrue("GroupMemberSignatureKeyEntity" in wiped.tables, "left behind: ${wiped.tables}")
        }

    /**
     * The listing of each group's expenses, what of it could not be read yet, and the newest version
     * and deletion flag of each expense shown go with the expenses: the next account to sign in
     * lists its own groups whole, and refuses nothing for the revisions another account was shown.
     */
    @Test
    fun `a log-out wipes the groups' listings and every expense's newest revision and flag`() = runBlocking<Unit> {
        val wiped = Wiped()

        wiped.deleteAll()

        assertTrue("ListedGroupExpenseEntity" in wiped.tables, "left behind: ${wiped.tables}")
        assertTrue("UnreadableGroupExpenseEntity" in wiped.tables, "left behind: ${wiped.tables}")
        assertTrue("ExpenseRevisionEntity" in wiped.tables, "left behind: ${wiped.tables}")
        assertTrue("GroupExpenseDeletionEntity" in wiped.tables, "left behind: ${wiped.tables}")
        assertTrue("ChangeFeedEntity" in wiped.tables, "left behind: ${wiped.tables}")
    }

    /**
     * Who the user blocked belongs to the account signed in: the next account to sign in has
     * blocked nobody, and gets its own blocks back from its vault.
     */
    @Test
    fun `a log-out wipes who the user blocked`() = runBlocking<Unit> {
        val wiped = Wiped()

        wiped.deleteAll()

        assertTrue("BlockedUserEntity" in wiped.tables, "left behind: ${wiped.tables}")
    }
}

/** Records which tables a log-out deletes, see [LocalDataDao.deleteAll]. */
private class Wiped : LocalDataDao {
    val tables = mutableListOf<String>()

    override suspend fun deleteUsers() { tables += "UserEntity" }
    override suspend fun deleteExpenses() { tables += "ExpenseEntity" }
    override suspend fun deleteCurrencies() { tables += "CurrencyEntity" }
    override suspend fun deleteBeneficiaries() { tables += "BeneficiaryEntity" }
    override suspend fun deleteGroups() { tables += "GroupEntity" }
    override suspend fun deleteGroupMembers() { tables += "GroupMemberEntity" }
    override suspend fun deleteGroupKeys() { tables += "GroupKeyEntity" }
    override suspend fun deleteGroupMemberSignatureKeys() { tables += "GroupMemberSignatureKeyEntity" }
    override suspend fun deletePendingGroupCreations() { tables += "PendingGroupCreationEntity" }
    override suspend fun deletePendingMembershipChanges() { tables += "PendingMembershipChangeEntity" }
    override suspend fun deleteDeclinedGroups() { tables += "DeclinedGroupEntity" }
    override suspend fun deleteContactRequests() { tables += "ContactRequestEntity" }
    override suspend fun deleteOutgoingContactRequests() { tables += "OutgoingContactRequestEntity" }
    override suspend fun deleteContacts() { tables += "ContactEntity" }
    override suspend fun deleteDirectChannels() { tables += "DirectChannelEntity" }
    override suspend fun deleteIdentityConflicts() { tables += "IdentityConflictEntity" }
    override suspend fun deleteBlockedUsers() { tables += "BlockedUserEntity" }
    override suspend fun deleteOneTimeExpenseKeys() { tables += "OneTimeExpenseKeyEntity" }
    override suspend fun deleteUnverifiedExpenseKeys() { tables += "UnverifiedExpenseKeyEntity" }
    override suspend fun deleteExpenseRevisions() { tables += "ExpenseRevisionEntity" }
    override suspend fun deleteOneTimeExpenseSettledMarks() { tables += "OneTimeExpenseSettledMarkEntity" }
    override suspend fun deleteGroupExpenseDeletions() { tables += "GroupExpenseDeletionEntity" }
    override suspend fun deletePendingExpenseKeys() { tables += "PendingExpenseKeyEntity" }
    override suspend fun deleteKeyRequestBudgets() { tables += "KeyRequestBudgetEntity" }
    override suspend fun deletePendingProfileKeys() { tables += "PendingProfileKeyEntity" }
    override suspend fun deleteProfileKeyRequestBudgets() { tables += "ProfileKeyRequestBudgetEntity" }
    override suspend fun deleteOwedExpenseKeys() { tables += "OwedExpenseKeyEntity" }
    override suspend fun deleteListedOneTimeExpenses() { tables += "ListedOneTimeExpenseEntity" }
    override suspend fun deleteListedGroupExpenses() { tables += "ListedGroupExpenseEntity" }
    override suspend fun deleteUnreadableGroupExpenses() { tables += "UnreadableGroupExpenseEntity" }
    override suspend fun deleteChangeFeeds() { tables += "ChangeFeedEntity" }
    override suspend fun deleteMlsRecords() { tables += "MlsRecordEntity" }
    override suspend fun deleteMemberProfiles() { tables += "MemberProfileEntity" }
}
