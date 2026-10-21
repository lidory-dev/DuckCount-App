package database.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.ExpenseDao
import database.data.local.dao.OwedExpenseKeyDao
import database.data.local.entity.OneTimeExpenseRevisionEntity
import database.data.local.entity.OwedExpenseKeyEntity
import database.data.mappers.toBeneficiaryEntity
import database.data.mappers.toExpenseEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseRemoteMediator
import database.data.remote.SetOneTimeExpenseSettledRequestBody
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import expense.FIRST_REVISION
import expense.OneTimeExpenseHistoryError
import expense.OneTimeExpenseKeyRecord
import expense.OneTimeExpenseKeyRepository
import expense.OneTimeExpenseSync
import expense.OneTimeExpenseVersion
import expense.OneTimeExpenseWriteError
import expense.OpenedOneTimeExpense
import expense.ReplacedVersion
import expense.SettledMark
import expense.nextSettledMarkTime
import expense.oneTimeExpenseHistory
import expense.replacedAt
import expense.sealOneTime
import expense.sealSettledMark
import expense.unsealOneTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import uniffi.lib_duckcount.newOneTimeExpenseId
import util.EmptyResult
import util.NetworkError
import util.Result
import util.asEmptyDataResult
import util.nowEpochMilliseconds
import util.onSuccess
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import androidx.paging.map as pagingMap

class ExpenseRepository(
    private val expenseApi: ExpenseApi,
    private val expenseDao: ExpenseDao,
    private val owedExpenseKeyDao: OwedExpenseKeyDao,
    private val oneTimeExpenseKeyRepository: OneTimeExpenseKeyRepository,
    private val oneTimeExpenseSync: OneTimeExpenseSync,
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
     * Creates a one-time expense: seals and signs it under a key of its own, keeps the key
     * and notes that everyone the expense names is owed it, posts the expense with the
     * creditor's copy of that key, and hands the key over.
     *
     * The key, and who is owed it, are kept before the post and not after it. The expense
     * exists from the moment the server stores it, which can be before an answer that never
     * arrives. Until the next sync brings the creditor's copy back, a creditor without the
     * key drops every request for it, and a share known only in memory is never sent, see
     * [OwedExpenseKeyEntity]. A failed post is no proof that the expense was never stored,
     * so both stay either way: for an expense that does not exist, they cost a few rows and
     * nothing else.
     *
     * Only what comes before the post can fail the write. After the expense is on the server
     * it exists, and reporting a failure would invite a retry that creates a second one. A
     * beneficiary the key did not reach is handed it by a later sync, and the key went to the
     * server with the expense, so a device that lost it gets it back from there.
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
            nonce = id.nonce,
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

        oneTimeExpenseKeyRepository.store(record = record)
        owe(expenseUuid = id.uuid, recipientUuids = beneficiaryUuids - creditorUuid)

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

        // Stored over whatever is there: a refresh may have stored it already, from the change
        // the creation made in the user's own feed.
        expenseDao.setNewestRevision(OneTimeExpenseRevisionEntity(id.uuid, FIRST_REVISION))
        expenseDao.upsertOneTimeExpense(
            expense = synced.toExpenseWithBeneficiariesEntity(),
            revision = FIRST_REVISION,
        )

        // Best effort: whatever does not go out now is left to the next sync, booked for when
        // the server's limit on messages reopens if that is what held it back.
        oneTimeExpenseSync.shareOwedKeys()

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
     * Anybody newly named is owed the key from before the PUT, see [OwedExpenseKeyEntity],
     * and is handed it once the expense names them as this device shows it: right after the
     * edit, or once a sync shows the version that an edit with no answer landed. Newly named
     * is anybody the record and the expense as shown do not both name: being named by both
     * means an edit naming them landed, and owed them the key then. The record alone would
     * not do, since after a removal with no answer it goes on naming somebody the server has
     * dropped, who would then not be handed the key when added back. An edit the server
     * accepts settles it for anybody it no longer names, who is owed nothing.
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

        val named = record.beneficiaryUuids.toSet() intersect
                expenseDao.getBeneficiaryUuids(expenseUuid = expenseUuid).toSet()

        owe(expenseUuid = expenseUuid, recipientUuids = beneficiaryUuids - named - creditorUuid)

        oneTimeExpenseKeyRepository.store(
            record = record.withBeneficiaries(
                beneficiaryUuids = (record.beneficiaryUuids + beneficiaryUuids).distinct(),
            ),
        )

        val update = expenseApi.updateOneTimeExpense(uuid = expenseUuid, body = body)

        if (update is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Network(update.error))
        }

        expenseDao.upsertOneTimeExpense(
            expense = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity(),
            revision = revision,
        )

        oneTimeExpenseKeyRepository.store(record = updated)
        owedExpenseKeyDao.deleteNotIn(expenseUuid = expenseUuid, recipientUuids = beneficiaryUuids)

        // Best effort: whatever does not go out now is left to the next sync, booked for when
        // the server's limit on messages reopens if that is what held it back.
        oneTimeExpenseSync.shareOwedKeys()

        return Result.Success(Unit)
    }

    /**
     * The versions of the one-time expense [uuid]: the one this device shows, then each one from
     * before it that the server's history holds, newest first, see [oneTimeExpenseHistory].
     *
     * Each earlier version is opened and checked as one listed is, see [unsealOneTime]: under
     * the expense's key, and signed by its creditor with the key this device's record holds them
     * to. One that does not open, or that anybody else signed, is left out.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun getOneTimeExpenseHistory(
        uuid: String,
    ): Result<List<OneTimeExpenseVersion>, OneTimeExpenseHistoryError> {
        val shown = expenseDao.getOneTimeExpense(uuid = uuid)
            ?: return Result.Error(OneTimeExpenseHistoryError.Unreadable)

        val revision = shown.expense.revision
            ?: return Result.Error(OneTimeExpenseHistoryError.Unreadable)

        val record = oneTimeExpenseKeyRepository.openingRecord(expenseUuid = uuid)
            ?: return Result.Error(OneTimeExpenseHistoryError.Unreadable)

        val entries = when (val history = expenseApi.getOneTimeExpenseHistory(uuid = uuid)) {
            is Result.Success -> history.data
            is Result.Error -> return Result.Error(OneTimeExpenseHistoryError.Network(history.error))
        }

        val expense = shown.toExpenseWithBeneficiaries()

        val replaced = entries.mapNotNull { entry ->
            entry.unsealOneTime(expenseUuid = uuid, record = record)
                ?.let { ReplacedVersion(version = it, replacedAt = entry.replacedAt()) }
        }

        return Result.Success(
            oneTimeExpenseHistory(
                shown = OpenedOneTimeExpense(expense = expense, revision = revision),
                replaced = replaced,
                timeZone = TimeZone.currentSystemDefault(),
            ),
        )
    }

    /**
     * Marks the user's one-time expense [uuid] as paid back, or no longer, see
     * [ExpenseWithBeneficiaries.isSettled].
     *
     * No edit: it leaves the expense's versions as they are, spends none of the edits the
     * server allows, and may be done any number of times. The mark is signed apart, see
     * [SettledMark], as made later than any this device has shown, since participants refuse
     * an older one, see [nextSettledMarkTime]; the server takes it from nobody but the creditor,
     * and participants believe it from nobody but the creditor either, whose signature they
     * check.
     *
     * The mark is shown once the server took it. When no answer comes, the next sync shows
     * whichever mark the server holds, which is this one if it landed.
     */
    suspend fun setOneTimeExpenseSettled(
        uuid: String,
        isSettled: Boolean,
    ): EmptyResult<OneTimeExpenseWriteError> {
        val record = oneTimeExpenseKeyRepository.record(expenseUuid = uuid)
            ?: return Result.Error(OneTimeExpenseWriteError.KeyMissing)

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(OneTimeExpenseWriteError.Identity(opened.error))
        }

        val markedAt = nextSettledMarkTime(
            now = nowEpochMilliseconds(),
            newest = expenseDao.getSettledMark(expenseUuid = uuid)?.markedAt,
        )

        val mark = sealSettledMark(record = record, markedAt = markedAt, isSettled = isSettled, client = client)
            ?: return Result.Error(OneTimeExpenseWriteError.Crypto)

        val set = expenseApi.setOneTimeExpenseSettled(
            uuid = uuid,
            body = SetOneTimeExpenseSettledRequestBody(is_settled = mark),
        )

        if (set is Result.Error) {
            return Result.Error(OneTimeExpenseWriteError.Network(set.error))
        }

        expenseDao.storeSettledMarks(
            marks = listOf(SettledMark(expenseUuid = uuid, markedAt = markedAt, isSettled = isSettled).toEntity()),
        )

        return Result.Success(Unit)
    }

    suspend fun deleteOneTimeExpense(uuid: String): Result<Unit, NetworkError> =
        expenseApi.deleteOneTimeExpense(uuid = uuid)
            .onSuccess {
                expenseDao.hardDelete(expenseUuid = uuid)
                owedExpenseKeyDao.deleteFor(expenseUuid = uuid)

                // The server accepted the deletion, which is the only evidence that makes
                // dropping a key safe: it is the one thing here with no way back.
                oneTimeExpenseKeyRepository.forget(expenseUuid = uuid)
            }
            .asEmptyDataResult()

    /** Notes that each of [recipientUuids] is owed the key to [expenseUuid]. */
    private suspend fun owe(expenseUuid: String, recipientUuids: List<String>) {
        owedExpenseKeyDao.insertAll(
            keys = recipientUuids.map { OwedExpenseKeyEntity(expenseUuid = expenseUuid, recipientUuid = it) },
        )
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
        // A refresh brings the device's copy of the server's list up to date, see
        // ExpenseRemoteMediator; this only sizes local paging windows.
        const val ONE_TIME_PAGE_SIZE = 20
    }

    fun getCurrency(expenseUuid: String): Flow<Currency?> {
        return expenseDao.getCurrency(expenseUuid)
    }
}
