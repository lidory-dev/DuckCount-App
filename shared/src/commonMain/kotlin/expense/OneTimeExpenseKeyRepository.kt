package expense

import database.data.local.dao.OneTimeExpenseKeyDao
import database.data.local.entity.OneTimeExpenseKeyEntity
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseApi
import database.data.remote.ExpenseDto
import util.Result
import vault.VaultEntryKey
import vault.VaultError
import vault.VaultRepository
import vault.seal
import vault.unseal

/**
 * The keys that open the user's one-time expenses.
 *
 * Each key lives in two places, as the same record. The server holds the copy that survives
 * a log-out, which wipes the local database and with it every MLS group: each participant's
 * own copy, stored with the expense and sealed under their [VaultEntryKey.ONE_TIME_EXPENSE_KEY]
 * key, which only their key ring holds. The device holds the copy a sync opens expenses with,
 * and the keys that arrived in a message and are not verified yet.
 *
 * What is stored is a whole record and not the bare key, see [OneTimeExpenseKeyRecord]: for
 * the creditor it is also the only account of who the expense was meant for that the server
 * cannot rewrite, and handing the key to somebody asking for it is decided against that and
 * nothing else.
 *
 * A key that arrived in a message is kept unverified until the expense it names has been
 * shown to open under it, signed by the creditor it came from, and an unverified key is never
 * sent to the server. Anyone who can reach the user can send them thirty-two bytes, and a
 * device that trusted them would stop asking for the real key and never open the expense
 * again.
 */
class OneTimeExpenseKeyRepository(
    private val oneTimeExpenseKeyDao: OneTimeExpenseKeyDao,
    private val vaultRepository: VaultRepository,
    private val expenseApi: ExpenseApi,
) {

    /** The record for [expenseUuid] as this device holds it, or null when it holds none. */
    suspend fun record(expenseUuid: String): OneTimeExpenseKeyRecord? =
        oneTimeExpenseKeyDao.get(expenseUuid = expenseUuid)?.toRecord()

    /**
     * Every key this device holds, keyed by expense, whether verified yet or not.
     *
     * One read for a whole expense list: the alternative is a lookup per expense, and the
     * list is the screen this is for.
     */
    suspend fun held(): Map<String, HeldKey> =
        oneTimeExpenseKeyDao.getAll().mapNotNull { entity ->
            entity.toRecord()?.let { HeldKey(record = it, isVerified = entity.isVerified) }
        }.associateBy { it.record.expenseUuid }

    /** Seals [record] as the user's copy for the server to keep with its expense. */
    suspend fun sealCopy(record: OneTimeExpenseKeyRecord): Result<EncryptedFieldDto, VaultError> {
        val sealingKey = when (val key = copySealingKey()) {
            is Result.Success -> key.data
            is Result.Error -> return Result.Error(key.error)
        }

        return seal(key = sealingKey, plaintext = record.serialize())
            ?.let { Result.Success(it) }
            ?: Result.Error(VaultError.Crypto)
    }

    /** Stores [record] on this device. */
    suspend fun store(record: OneTimeExpenseKeyRecord, isVerified: Boolean) {
        oneTimeExpenseKeyDao.upsert(
            OneTimeExpenseKeyEntity(
                expenseUuid = record.expenseUuid,
                record = record.serialize(),
                isVerified = isVerified,
            ),
        )
    }

    /**
     * Stores a key that arrived in a message, unless this device already holds one it has
     * seen work.
     *
     * Unverified, always: anyone who can reach the user can send them thirty-two bytes, and
     * a device that trusted them would stop asking for the real key and never open the
     * expense again. It becomes verified when it opens the expense it names, and not before.
     */
    suspend fun storeUnverified(record: OneTimeExpenseKeyRecord) {
        val held = oneTimeExpenseKeyDao.get(expenseUuid = record.expenseUuid)

        // A key that has been shown to work is never displaced by one that has not.
        if (held?.isVerified == true) return

        store(record = record, isVerified = false)
    }

    /**
     * Records that a key held unverified has been shown to open its expense, which is when
     * it becomes worth keeping, and [backUp] sends the server a copy of it.
     */
    suspend fun markVerified(record: OneTimeExpenseKeyRecord) {
        store(record = record, isVerified = true)
    }

    /**
     * Brings back the keys a log-out or a reinstall took off this device, from the user's
     * own copies the server lists with [expenses].
     *
     * They come back verified: they were verified before they were sealed, under a key only
     * this user holds. A copy naming another expense than the one it came with is dropped
     * rather than trusted: that is what a copy moved from one expense to another looks like.
     *
     * A copy that could not be opened is left for the next listing, which carries it again.
     */
    suspend fun restore(expenses: List<ExpenseDto>) {
        val verified = oneTimeExpenseKeyDao.getAll()
            .filter { it.isVerified }
            .mapTo(HashSet()) { it.expenseUuid }

        val copies = expenses.mapNotNull { expense ->
            expense.key_copy?.takeIf { expense.uuid !in verified }?.let { expense.uuid to it }
        }

        // Most syncs find nothing to bring back, and reading the key ring is a request.
        if (copies.isEmpty()) return

        val sealingKey = (copySealingKey() as? Result.Success)?.data ?: return

        val restored = copies.mapNotNull { (expenseUuid, copy) ->
            val plaintext = unseal(key = sealingKey, content = copy) ?: return@mapNotNull null

            // Parsed only to check that the copy is a record about the expense it came
            // with. The bytes are stored as they were read.
            OneTimeExpenseKeyRecord.parse(plaintext = plaintext, expenseUuid = expenseUuid)
                ?: return@mapNotNull null

            OneTimeExpenseKeyEntity(expenseUuid = expenseUuid, record = plaintext, isVerified = true)
        }

        oneTimeExpenseKeyDao.upsertAll(keys = restored)
    }

    /**
     * Sends the server the user's copy of every key this device has verified and [expenses]
     * shows it holding none of: one just verified, or one the server dropped, as it does
     * when the user is taken off an expense and later put back.
     *
     * The listing decides rather than a record of what was sent, which the server can make
     * stale, and the device keeps its key whatever the server answers: a status code is only
     * the server's word, and a key it never held has no other way back. A copy that did not
     * go up is sent again after the next listing, which still shows it missing.
     */
    suspend fun backUp(expenses: List<ExpenseDto>) {
        val missing = expenses.filter { it.key_copy == null }.mapTo(HashSet()) { it.uuid }

        val keys = oneTimeExpenseKeyDao.getAll().filter { it.isVerified && it.expenseUuid in missing }

        // Most syncs find nothing to send, and reading the key ring is a request.
        if (keys.isEmpty()) return

        val sealingKey = (copySealingKey() as? Result.Success)?.data ?: return

        keys.forEach { key ->
            val copy = seal(key = sealingKey, plaintext = key.record) ?: return@forEach

            expenseApi.saveKeyCopy(uuid = key.expenseUuid, keyCopy = copy)
        }
    }

    /**
     * Drops the key to the user's own expense, which they deleted and the server accepted.
     * The server dropped its copies along with the expense.
     *
     * An expense simply missing from a listing is not grounds for this: a key is the one
     * thing here with no way back, and a truncated or stale listing would destroy keys to
     * expenses that still exist.
     */
    suspend fun forget(expenseUuid: String) {
        oneTimeExpenseKeyDao.delete(expenseUuid = expenseUuid)
    }

    /**
     * The key the user's copies are sealed under, created and written to the key ring the
     * first time, before any copy sealed under it can leave the device.
     */
    private suspend fun copySealingKey(): Result<ByteArray, VaultError> =
        vaultRepository.getOrCreatePartKey(part = VaultEntryKey.ONE_TIME_EXPENSE_KEY)

    private fun OneTimeExpenseKeyEntity.toRecord(): OneTimeExpenseKeyRecord? =
        OneTimeExpenseKeyRecord.parse(plaintext = record, expenseUuid = expenseUuid)
}

/** A key this device holds, and whether it has been shown to open its expense. */
class HeldKey(
    val record: OneTimeExpenseKeyRecord,
    val isVerified: Boolean,
)
