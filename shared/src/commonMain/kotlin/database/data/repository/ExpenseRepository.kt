package database.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import database.data.local.dao.ExpenseDao
import database.data.mappers.toBeneficiaryEntity
import database.data.mappers.toExpenseEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseRemoteMediator
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import expense.OneTimeExpenseKeyRecord
import expense.OneTimeExpenseKeyRepository
import expense.OneTimeExpenseSync
import expense.OneTimeExpenseWriteError
import expense.ExpenseKey
import expense.ExpenseKeyShare
import expense.sealOneTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import authenticationUtils.getAuthenticatedUserUuid
import message.DirectMessageSender
import message.DirectSendError
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.EmptyResult
import util.NetworkError
import util.Result
import util.asEmptyDataResult
import util.onSuccess
import androidx.paging.map as pagingMap
import database.data.remote.DirectMessageKindDto

class ExpenseRepository(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
    private val oneTimeExpenseKeyRepository: OneTimeExpenseKeyRepository,
    private val oneTimeExpenseSync: OneTimeExpenseSync,
    private val directMessageSender: DirectMessageSender,
) {

    @OptIn(ExperimentalPagingApi::class)
    fun getOneTimeExpensesPaged(): Flow<PagingData<ExpenseWithBeneficiaries>> =
        Pager(
            config = PagingConfig(
                pageSize = ONE_TIME_PAGE_SIZE,
                // The UI early-returns on null items, so placeholder rows would render as
                // empty gaps and skew the initial scroll position. We don't render them.
                enablePlaceholders = false,
            ),
            remoteMediator = ExpenseRemoteMediator(oneTimeExpenseSync = oneTimeExpenseSync),
            pagingSourceFactory = { expenseDao.pagingSource() },
        ).flow.map { pagingData ->
            pagingData.pagingMap { it.toExpenseWithBeneficiaries() }
        }

    /**
     * Creates a one-time expense: seals it under a key of its own, posts it, keeps the key,
     * and hands it to everyone the expense names.
     *
     * Only the first three can fail the write. After the expense is on the server it
     * exists, and reporting a failure would invite a retry that creates a second one.
     * Whatever did not get done, the vault copy or a beneficiary who was not reached, is
     * exactly what the sync looks for and fixes.
     */
    suspend fun createOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): EmptyResult<OneTimeExpenseWriteError> {
        val creditorUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseWriteError.NoSession)

        // Before anything sealed under it leaves this device: a part key that exists only
        // in memory seals an expense nobody can ever open if the write recording it is the
        // one that fails.
        val prepared = oneTimeExpenseKeyRepository.prepareForWrite()

        if (prepared is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Vault(prepared.error))
        }

        val expenseKey = generateSymmetricEncryptionKey()

        val body = expenseWithBeneficiaries.sealOneTime(expenseKey = expenseKey)
            ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        val expenseUuid = when (val created = expenseApi.createOneTimeExpense(body = body)) {
            is Result.Success -> created.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Network(created.error))
        }

        val beneficiaryUuids = expenseWithBeneficiaries.beneficiaries.map { it.userUuid }

        oneTimeExpenseKeyRepository.store(
            record = OneTimeExpenseKeyRecord(
                expenseUuid = expenseUuid,
                key = expenseKey,
                creditorUuid = creditorUuid,
                beneficiaryUuids = beneficiaryUuids,
            ),
            verified = true,
        )

        // The server owns the uuid; cache the row under it.
        val synced = expenseWithBeneficiaries.copy(
            uuid = expenseUuid,
            beneficiaries = expenseWithBeneficiaries.beneficiaries.map {
                it.copy(expenseUuid = expenseUuid)
            },
        )

        expenseDao.insertWithBeneficiaries(
            expenseWithBeneficiaries = synced.toExpenseWithBeneficiariesEntity(),
        )

        shareKey(
            expenseUuid = expenseUuid,
            expenseKey = expenseKey,
            beneficiaryUuids = beneficiaryUuids,
            recipients = beneficiaryUuids.filterNot { it == creditorUuid },
        )

        return Result.Success(Unit)
    }

    /**
     * Edits a one-time expense, re-sealing it under the key it already has.
     *
     * Never under a new one: every participant holds the old key and nothing tells them to
     * expect another, so a re-keyed expense would go unreadable on every other phone while
     * its amount quietly changed. An edit this device has no key for is refused outright.
     *
     * The server replaces the beneficiary list wholesale, so anyone newly named is handed
     * the key. Anyone dropped keeps it: they have already seen the expense, and there is no
     * taking a key back.
     */
    suspend fun updateOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): EmptyResult<OneTimeExpenseWriteError> {
        val creditorUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseWriteError.NoSession)

        val expenseUuid = expenseWithBeneficiaries.uuid

        val record = oneTimeExpenseKeyRepository.record(expenseUuid = expenseUuid)
            ?: return Result.Error(OneTimeExpenseWriteError.KeyMissing)

        val body = expenseWithBeneficiaries.sealOneTime(expenseKey = record.key)
            ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        val update = expenseApi.updateOneTimeExpense(uuid = expenseUuid, body = body)

        if (update is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Network(update.error))
        }

        expenseDao.upsertWithBeneficiaries(
            expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity(),
        )

        val beneficiaryUuids = expenseWithBeneficiaries.beneficiaries.map { it.userUuid }
        val added = beneficiaryUuids - record.beneficiaryUuids.toSet()

        oneTimeExpenseKeyRepository.store(
            record = OneTimeExpenseKeyRecord(
                expenseUuid = expenseUuid,
                key = record.key,
                creditorUuid = record.creditorUuid,
                beneficiaryUuids = beneficiaryUuids,
            ),
            verified = true,
        )

        shareKey(
            expenseUuid = expenseUuid,
            expenseKey = record.key,
            beneficiaryUuids = beneficiaryUuids,
            recipients = added.filterNot { it == creditorUuid },
        )

        return Result.Success(Unit)
    }

    suspend fun deleteOneTimeExpense(uuid: String): Result<Unit, NetworkError> =
        expenseApi.deleteOneTimeExpense(uuid = uuid)
            .onSuccess {
                expenseDao.hardDelete(expenseUuid = uuid)

                // The server accepted the deletion, which is the only evidence that makes
                // dropping a key safe: it is the one thing here with no way back.
                oneTimeExpenseKeyRepository.forget(expenseUuid = uuid)
            }
            .asEmptyDataResult()

    /**
     * Hands the key to [expenseUuid] to each of [recipients].
     *
     * Best effort, and deliberately one at a time: a user action is one message per person
     * named, and the server counts those against an allowance. Running past it only pushes
     * the count further out, so the first refusal stops the pass and the rest is left to
     * the sync, which asks for what never arrived.
     */
    private suspend fun shareKey(
        expenseUuid: String,
        expenseKey: ByteArray,
        beneficiaryUuids: List<String>,
        recipients: List<String>,
    ) {
        if (recipients.isEmpty()) return

        val payload = ExpenseKeyShare(
            keys = listOf(
                ExpenseKey(
                    expenseUuid = expenseUuid,
                    key = expenseKey,
                    beneficiaryUuids = beneficiaryUuids,
                ),
            ),
        ).serialize()

        for (recipient in recipients) {
            val sent = directMessageSender.send(
                recipientUserUuid = recipient,
                payload = payload,
                // So a locked phone can say an expense arrived rather than nothing.
                kind = DirectMessageKindDto.ONE_TIME_EXPENSE,
            )

            if (sent is Result.Error && sent.error == DirectSendError.RateLimited) return
        }
    }

    suspend fun insert(expense: Expense) {
        expenseDao.insert(expenseEntity = expense.toExpenseEntity())
    }

    suspend fun insertBeneficiaries(beneficiaries: List<Beneficiary>) {
        expenseDao.insertBeneficiaries(beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() })
    }

    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.insertWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    suspend fun update(expense: Expense) {
        expenseDao.update(expenseEntity = expense.toExpenseEntity())
    }

    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.updateWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    suspend fun delete(expense: Expense) {
        expenseDao.delete(expenseEntity = expense.toExpenseEntity())
    }

    suspend fun delete(expenseUuid: String) {
        expenseDao.delete(expenseUuid)
    }

    suspend fun restore(expenseUuid: String) {
        expenseDao.restore(expenseUuid)
    }

    /** Hard-deletes a local expense and its beneficiaries; there is no restoring it. */
    suspend fun hardDelete(expenseUuid: String) {
        expenseDao.hardDeleteWithBeneficiaries(expenseUuid = expenseUuid)
    }

    fun getAll(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithBeneficiariesAsFlow().map { expensesWithBeneficiariesEntities ->
            expensesWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    fun getAllFromGroupWithoutDeletedOnes(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithoutGroupAsFlow().map { expenseWithBeneficiariesEntities ->
            expenseWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseDao.getWithBeneficiariesAsFlow(uuid = uuid).map { expenseWithBeneficiariesEntity ->
            expenseWithBeneficiariesEntity?.toExpenseWithBeneficiaries()
        }
    }

    private companion object {
        // Server returns the full list at once; this only sizes local paging windows.
        const val ONE_TIME_PAGE_SIZE = 20
    }

    fun getCurrency(expenseUuid: String): Flow<Currency?> {
        return expenseDao.getCurrency(expenseUuid)
    }
}
