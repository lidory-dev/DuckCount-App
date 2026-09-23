package expense

import database.data.local.dao.OneTimeExpenseKeyDao
import database.data.local.entity.OneTimeExpenseKeyEntity
import util.EmptyResult
import util.Result
import vault.VaultEntryKey
import vault.VaultError
import vault.VaultRepository

/**
 * The keys that open the user's one-time expenses.
 *
 * Each key lives in two places and is the same bytes in both. The vault is the copy that
 * matters: it is the only one that survives a log-out, which wipes the local database and
 * with it every MLS group, so a key that only ever arrived in a message would be gone. The
 * local copy exists because opening an expense list must not be one network call per
 * expense.
 *
 * What is stored is a whole record and not the bare key, see [OneTimeExpenseKeyRecord]: for
 * the creditor it is also the only account of who the expense was meant for that the server
 * cannot rewrite, and handing the key to somebody asking for it is decided against that and
 * nothing else.
 *
 * A key that arrived in a message is kept unverified until it has been shown to open the
 * expense it names, and an unverified key never reaches the vault. Anyone who can reach the
 * user can send them thirty-two bytes, and a device that believed them would stop asking
 * for the real key and never open the expense again.
 */
class OneTimeExpenseKeyRepository(
    private val oneTimeExpenseKeyDao: OneTimeExpenseKeyDao,
    private val vaultRepository: VaultRepository,
) {

    /**
     * Makes sure the vault holds the key that seals this part, before anything sealed under
     * it is written anywhere.
     *
     * What a creditor calls before creating an expense. The part key is minted on first use,
     * and if it were minted while sealing the first expense it would exist only in memory
     * until the write that records it landed: a crash in between leaves an expense nobody,
     * the creditor included, can ever open, and which every beneficiary then asks the
     * creditor for forever.
     */
    suspend fun prepareForWrite(): EmptyResult<VaultError> =
        when (val key = vaultRepository.getOrCreatePartKey(part = VaultEntryKey.ONE_TIME_EXPENSE_KEY)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(key.error)
        }

    /** The record for [expenseUuid] as this device holds it, or null when it holds none. */
    suspend fun record(expenseUuid: String): OneTimeExpenseKeyRecord? =
        oneTimeExpenseKeyDao.get(expenseUuid = expenseUuid)?.toRecord()

    /**
     * Every key this device holds, keyed by expense, whether believed yet or not.
     *
     * One read for a whole expense list: the alternative is a lookup per expense, and the
     * list is the screen this is for.
     */
    suspend fun held(): Map<String, HeldKey> =
        oneTimeExpenseKeyDao.getAll().mapNotNull { entity ->
            entity.toRecord()?.let { HeldKey(record = it, isVerified = entity.isVerified) }
        }.associateBy { it.record.expenseUuid }

    /**
     * Stores [record], and writes it to the vault when it is [verified].
     *
     * A vault write that fails is not an error the caller has to act on: the record is on
     * the device, and [backUp] carries it over on any later sync. What matters is that it
     * is not lost between arriving and being believed.
     */
    suspend fun store(record: OneTimeExpenseKeyRecord, verified: Boolean) {
        val serialized = record.serialize()

        oneTimeExpenseKeyDao.upsert(
            OneTimeExpenseKeyEntity(
                expenseUuid = record.expenseUuid,
                record = serialized,
                isVerified = verified,
                isBackedUp = false,
            ),
        )

        if (!verified) return

        val write = vaultRepository.writeCollectionItem(
            part = VaultEntryKey.ONE_TIME_EXPENSE_KEY,
            itemId = record.expenseUuid,
            plaintext = serialized,
        )

        if (write is Result.Success) {
            oneTimeExpenseKeyDao.markBackedUp(expenseUuids = listOf(record.expenseUuid))
        }
    }

    /**
     * Stores a key that arrived in a message, unless this device already holds one it has
     * seen work.
     *
     * Unverified, always: anyone who can reach the user can send them thirty-two bytes, and
     * a device that believed them would stop asking for the real key and never open the
     * expense again. It becomes believed when it opens the expense it names, and not before.
     */
    suspend fun storeUnverified(record: OneTimeExpenseKeyRecord) {
        val held = oneTimeExpenseKeyDao.get(expenseUuid = record.expenseUuid)

        // A key that has been shown to work is never displaced by one that has not.
        if (held?.isVerified == true) return

        store(record = record, verified = false)
    }

    /**
     * Records that a key held unverified has been shown to open its expense, which is when
     * it becomes worth keeping.
     */
    suspend fun markVerified(record: OneTimeExpenseKeyRecord) {
        store(record = record, verified = true)
    }

    /** Carries to the vault every verified key it does not hold yet. */
    suspend fun backUp(): EmptyResult<VaultError> {
        val pending = oneTimeExpenseKeyDao.getNotBackedUp()

        // Most calls find nothing, and a write is a request.
        if (pending.isEmpty()) return Result.Success(Unit)

        val written = mutableListOf<String>()
        var failure: VaultError? = null

        pending.forEach { key ->
            val write = vaultRepository.writeCollectionItem(
                part = VaultEntryKey.ONE_TIME_EXPENSE_KEY,
                itemId = key.expenseUuid,
                plaintext = key.record,
            )

            when (write) {
                is Result.Success -> written += key.expenseUuid
                is Result.Error -> failure = failure ?: write.error
            }
        }

        if (written.isNotEmpty()) oneTimeExpenseKeyDao.markBackedUp(expenseUuids = written)

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Brings back the keys the vault holds, which a log-out or a reinstall took off this
     * device.
     *
     * They come back verified: they were verified before they were written, and the vault is
     * sealed under a key derived from the user's own password.
     *
     * A record whose slot and contents disagree is dropped rather than trusted: that is what
     * a record moved from one expense to another looks like.
     */
    suspend fun restore(): EmptyResult<VaultError> {
        val items = when (
            val read = vaultRepository.readCollection(part = VaultEntryKey.ONE_TIME_EXPENSE_KEY)
        ) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        if (items.isEmpty()) return Result.Success(Unit)

        val restored = items.mapNotNull { (expenseUuid, plaintext) ->
            // Parsed only to check that what sat in the slot is a record about the expense
            // the slot names. The bytes are stored as they were read.
            OneTimeExpenseKeyRecord.parse(plaintext = plaintext, expenseUuid = expenseUuid)
                ?: return@mapNotNull null

            OneTimeExpenseKeyEntity(
                expenseUuid = expenseUuid,
                record = plaintext,
                isVerified = true,
                isBackedUp = true,
            )
        }

        oneTimeExpenseKeyDao.upsertAll(keys = restored)

        return Result.Success(Unit)
    }

    /**
     * Drops the key to an expense that is gone, from this device and from the vault.
     *
     * Only ever called on the user's own deletion of their own expense, which the server
     * accepted. An expense simply missing from a listing is not grounds for this: a key is
     * the one thing here with no way back, and a truncated or stale listing would destroy
     * keys to expenses that still exist.
     */
    suspend fun forget(expenseUuid: String) {
        oneTimeExpenseKeyDao.delete(expenseUuid = expenseUuid)

        vaultRepository.deleteCollectionItem(
            part = VaultEntryKey.ONE_TIME_EXPENSE_KEY,
            itemId = expenseUuid,
        )
    }

    private fun OneTimeExpenseKeyEntity.toRecord(): OneTimeExpenseKeyRecord? =
        OneTimeExpenseKeyRecord.parse(plaintext = record, expenseUuid = expenseUuid)
}

/** A key this device holds, and whether it has been shown to open its expense. */
class HeldKey(
    val record: OneTimeExpenseKeyRecord,
    val isVerified: Boolean,
)
