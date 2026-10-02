package database.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.entity.OneTimeExpenseRevisionEntity
import database.data.mappers.toBeneficiaryEntity
import database.data.mappers.toExpenseEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.DirectMessageKindDto
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseRemoteMediator
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import expense.ExpenseKey
import expense.ExpenseKeyShare
import expense.FIRST_REVISION
import expense.OneTimeExpenseKeyRecord
import expense.OneTimeExpenseKeyRepository
import expense.OneTimeExpenseSync
import expense.OneTimeExpenseWriteError
import expense.sealOneTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import message.DirectMessageSender
import message.DirectSendError
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newOneTimeExpenseId
import util.EmptyResult
import util.NetworkError
import util.Result
import util.asEmptyDataResult
import util.onSuccess
import kotlin.io.encoding.Base64
import androidx.paging.map as pagingMap

class ExpenseRepository(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
    private val oneTimeExpenseKeyRepository: OneTimeExpenseKeyRepository,
    private val oneTimeExpenseSync: OneTimeExpenseSync,
    private val directMessageSender: DirectMessageSender,
    private val mlsIdentityRepository: MlsIdentityRepository,
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
     * Creates a one-time expense: seals and signs it under a key of its own, keeps the key,
     * posts the expense with the creditor's copy of that key, and hands it to everyone the
     * expense names.
     *
     * The key is kept before the post and not after it. The expense exists from the moment
     * the server stores it, which can be before an answer that never arrives, and until the
     * next sync brings the creditor's copy back, a creditor without the key drops every
     * request for it. A failed post is no proof that the expense was never stored, so the
     * key stays either way: one to an expense that does not exist costs a row and nothing
     * else.
     *
     * Only what comes before the post can fail the write. After the expense is on the server
     * it exists, and reporting a failure would invite a retry that creates a second one. A
     * beneficiary who was not reached is what the sync looks for and fixes, and the key went
     * to the server with the expense, so a device that lost it gets it back from there.
     */
    suspend fun createOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): EmptyResult<OneTimeExpenseWriteError> {
        val creditorUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseWriteError.NoSession)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Identity(opened.error))
        }

        // Known before the post, so that the copy of the key sent with it can name it.
        val id = newOneTimeExpenseId(creditorUuid = creditorUuid)
            ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        val expenseKey = generateSymmetricEncryptionKey()
        val beneficiaryUuids = expenseWithBeneficiaries.beneficiaries.map { it.userUuid }

        val record = OneTimeExpenseKeyRecord(
            expenseUuid = id.uuid,
            key = expenseKey,
            creditorUuid = creditorUuid,
            creditorSignatureKey = client.signaturePublicKey(),
            beneficiaryUuids = beneficiaryUuids,
        )

        val keyCopy = when (val sealed = oneTimeExpenseKeyRepository.sealCopy(record = record)) {
            is Result.Success -> sealed.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Vault(sealed.error))
        }

        val body = expenseWithBeneficiaries.sealOneTime(
            expenseKey = expenseKey,
            revision = FIRST_REVISION,
            client = client,
            keyCopy = keyCopy,
        ) ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        oneTimeExpenseKeyRepository.store(record = record, isVerified = true)

        val created = expenseApi.createOneTimeExpense(
            body = body.toCreateBody(nonce = Base64.Default.encode(id.nonce)),
        )

        if (created is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Network(created.error))
        }

        val synced = expenseWithBeneficiaries.copy(
            uuid = id.uuid,
            beneficiaries = expenseWithBeneficiaries.beneficiaries.map {
                it.copy(expenseUuid = id.uuid)
            },
        )

        expenseDao.setNewestRevision(OneTimeExpenseRevisionEntity(id.uuid, FIRST_REVISION))
        expenseDao.insertWithBeneficiaries(
            expenseWithBeneficiaries = synced.toExpenseWithBeneficiariesEntity(),
        )

        shareKey(
            expenseUuid = id.uuid,
            expenseKey = expenseKey,
            beneficiaryUuids = beneficiaryUuids,
            recipients = beneficiaryUuids.filterNot { it == creditorUuid },
        )

        return Result.Success(Unit)
    }

    /**
     * Edits a one-time expense, re-sealing it under the key it already has and signing it as
     * a newer version than any this device has shown or signed.
     *
     * Never under a new one: every participant holds the old key and nothing tells them to
     * expect another, so a re-keyed expense would go unreadable on every other phone while
     * its amount quietly changed. An edit this device has no key for is refused outright.
     *
     * The server replaces the beneficiary list wholesale, so anyone newly named is handed
     * the key, and the creditor's copy of it names the new list. Anyone dropped keeps it:
     * they have already seen the expense, and there is no taking a key back.
     *
     * Until the server answers, the record this device keeps names everybody on either list.
     * The server holds the new list from the moment the PUT lands, which can be before an
     * answer that never arrives, and a sync never replaces a verified record with the
     * server's copy, so a record still naming only the old list would refuse the newly named
     * until another edit or a log-out. Once the server accepts the edit, the record is
     * narrowed to the new list. When no answer comes, it keeps naming both, since either may
     * be the one the server holds.
     *
     * Who is newly named is decided against the beneficiaries this device last saw the server
     * list, and not against the record, which after an edit that got no answer names people
     * who may never have been handed the key. Retrying that edit hands it to them, unless a
     * sync has meanwhile shown the version it landed, which makes them look listed already.
     * The server's account is good enough for this, because it only decides who is handed
     * the key unasked, never who may have it. Everybody it goes to is somebody the creditor
     * has just picked, and anybody it is not handed to gets it by asking, which the record
     * answers.
     */
    suspend fun updateOneTimeExpense(
        expenseWithBeneficiaries: ExpenseWithBeneficiaries,
    ): EmptyResult<OneTimeExpenseWriteError> {
        val creditorUuid = getAuthenticatedUserUuid()
            ?: return Result.Error(OneTimeExpenseWriteError.NoSession)

        val expenseUuid = expenseWithBeneficiaries.uuid

        val record = oneTimeExpenseKeyRepository.record(expenseUuid = expenseUuid)
            ?: return Result.Error(OneTimeExpenseWriteError.KeyMissing)

        // Participants who have seen a version refuse anything older as a rollback, so an
        // edit is signed above every version this device knows of.
        val revision = expenseDao.reserveNextRevision(expenseUuid = expenseUuid)
            ?: return Result.Error(OneTimeExpenseWriteError.KeyMissing)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Identity(opened.error))
        }

        val beneficiaryUuids = expenseWithBeneficiaries.beneficiaries.map { it.userUuid }

        val updated = record.withBeneficiaries(beneficiaryUuids = beneficiaryUuids)

        val keyCopy = when (val sealed = oneTimeExpenseKeyRepository.sealCopy(record = updated)) {
            is Result.Success -> sealed.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Vault(sealed.error))
        }

        val body = expenseWithBeneficiaries.sealOneTime(
            expenseKey = record.key,
            revision = revision,
            client = client,
            keyCopy = keyCopy,
        ) ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        // Read before the PUT: once it lands, a sync that sees the new version can put the
        // newly named in the rows before they have been handed anything.
        val added = beneficiaryUuids - expenseDao.getBeneficiaryUuids(expenseUuid = expenseUuid).toSet()

        oneTimeExpenseKeyRepository.store(
            record = record.withBeneficiaries(
                beneficiaryUuids = (record.beneficiaryUuids + beneficiaryUuids).distinct(),
            ),
            isVerified = true,
        )

        val update = expenseApi.updateOneTimeExpense(uuid = expenseUuid, body = body)

        if (update is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Network(update.error))
        }

        expenseDao.upsertWithBeneficiaries(
            expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity(),
        )

        oneTimeExpenseKeyRepository.store(record = updated, isVerified = true)

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
