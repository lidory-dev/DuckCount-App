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
    override suspend fun deletePendingGroupCreations() { tables += "PendingGroupCreationEntity" }
    override suspend fun deletePendingMembershipChanges() { tables += "PendingMembershipChangeEntity" }
    override suspend fun deleteDeclinedGroups() { tables += "DeclinedGroupEntity" }
    override suspend fun deleteContactRequests() { tables += "ContactRequestEntity" }
    override suspend fun deleteOutgoingContactRequests() { tables += "OutgoingContactRequestEntity" }
    override suspend fun deleteContacts() { tables += "ContactEntity" }
    override suspend fun deleteDirectChannels() { tables += "DirectChannelEntity" }
    override suspend fun deleteIdentityConflicts() { tables += "IdentityConflictEntity" }
    override suspend fun deleteOneTimeExpenseKeys() { tables += "OneTimeExpenseKeyEntity" }
    override suspend fun deleteUnverifiedExpenseKeys() { tables += "UnverifiedExpenseKeyEntity" }
    override suspend fun deleteOneTimeExpenseRevisions() { tables += "OneTimeExpenseRevisionEntity" }
    override suspend fun deleteOneTimeExpenseSettledMarks() { tables += "OneTimeExpenseSettledMarkEntity" }
    override suspend fun deletePendingExpenseKeys() { tables += "PendingExpenseKeyEntity" }
    override suspend fun deleteKeyRequestBudgets() { tables += "KeyRequestBudgetEntity" }
    override suspend fun deletePendingProfileKeys() { tables += "PendingProfileKeyEntity" }
    override suspend fun deleteProfileKeyRequestBudgets() { tables += "ProfileKeyRequestBudgetEntity" }
    override suspend fun deleteOwedExpenseKeys() { tables += "OwedExpenseKeyEntity" }
    override suspend fun deleteListedOneTimeExpenses() { tables += "ListedOneTimeExpenseEntity" }
    override suspend fun deleteChangeFeeds() { tables += "ChangeFeedEntity" }
    override suspend fun deleteMlsRecords() { tables += "MlsRecordEntity" }
    override suspend fun deleteMemberProfiles() { tables += "MemberProfileEntity" }
}
