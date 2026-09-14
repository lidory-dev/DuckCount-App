package mls

import database.data.local.dao.MlsRecordDao
import database.data.local.entity.MlsRecordEntity
import kotlinx.coroutines.runBlocking
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.MlsStore
import uniffi.lib_duckcount.MlsStoreException

/**
 * The storage `lib-duckcount` keeps its MLS state in: the local database, one row per
 * record.
 *
 * The library seals every value before handing it over and authenticates it on the
 * way back, so all this has to be is durable. It never interprets what it stores.
 *
 * The library calls in synchronously, on whichever thread called into it, while Room
 * only offers suspending queries outside Android. Each call therefore blocks until its
 * query has run, which puts two rules on every caller of the library:
 * - call it from `Dispatchers.IO`, never from the main thread;
 * - never from inside a Room transaction, whose connection the blocked query would
 *   wait for forever.
 */
class RoomMlsStore(
    private val mlsRecordDao: MlsRecordDao,
) : MlsStore {

    @Throws(MlsStoreException::class)
    override fun get(namespace_: MlsNamespace, key: ByteArray): ByteArray? = storing {
        mlsRecordDao.get(namespace = namespace_.columnValue(), key = key)
    }

    @Throws(MlsStoreException::class)
    override fun put(namespace_: MlsNamespace, key: ByteArray, value: ByteArray) {
        storing {
            mlsRecordDao.put(
                record = MlsRecordEntity(
                    namespace = namespace_.columnValue(),
                    key = key,
                    value = value,
                ),
            )
        }
    }

    @Throws(MlsStoreException::class)
    override fun delete(namespace_: MlsNamespace, key: ByteArray) {
        storing {
            mlsRecordDao.delete(namespace = namespace_.columnValue(), key = key)
        }
    }

    @Throws(MlsStoreException::class)
    override fun keys(namespace_: MlsNamespace): List<ByteArray> = storing {
        mlsRecordDao.keys(namespace = namespace_.columnValue())
    }

    /**
     * Runs [query] to completion, reporting a failure the only way the library accepts.
     *
     * The message names the exception type and nothing more: a database exception may
     * quote the statement's arguments, and those are MLS records.
     */
    private fun <T> storing(query: suspend () -> T): T =
        try {
            runBlocking { query() }
        } catch (exception: Exception) {
            throw MlsStoreException.Failed(v1 = exception::class.simpleName ?: "Exception")
        }
}

/**
 * What [MlsRecordEntity.namespace] holds for this namespace.
 *
 * Spelled out rather than taken from the enum's name, so a variant the library renames
 * or adds fails to compile here instead of silently orphaning the records stored under
 * the old name.
 */
private fun MlsNamespace.columnValue(): String = when (this) {
    MlsNamespace.GROUP_STATE -> "group_state"
    MlsNamespace.GROUP_EPOCH -> "group_epoch"
    MlsNamespace.KEY_PACKAGE -> "key_package"
    MlsNamespace.IDENTITY_PIN -> "identity_pin"
    MlsNamespace.SIGNER -> "signer"
    MlsNamespace.METADATA -> "metadata"
}
