package mls

import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.MlsStore
import uniffi.lib_duckcount.MlsStoreException
import kotlin.io.encoding.Base64

/** Holds an MLS client's state for the length of a test. */
internal class InMemoryMlsStore : MlsStore {
    private val values = HashMap<Pair<MlsNamespace, String>, ByteArray>()

    /**
     * The namespaces in which every call fails, as it would with a failing database, see
     * [RoomMlsStore]. One namespace fails one step a client takes and leaves the others, which a
     * test can tell apart.
     */
    var failing: Set<MlsNamespace> = emptySet()

    @Throws(MlsStoreException::class)
    override fun get(namespace_: MlsNamespace, key: ByteArray): ByteArray? {
        failIfFailing(namespace_)

        return values[namespace_ to key.encoded()]
    }

    @Throws(MlsStoreException::class)
    override fun put(namespace_: MlsNamespace, key: ByteArray, value: ByteArray) {
        failIfFailing(namespace_)

        values[namespace_ to key.encoded()] = value
    }

    @Throws(MlsStoreException::class)
    override fun delete(namespace_: MlsNamespace, key: ByteArray) {
        failIfFailing(namespace_)

        values.remove(namespace_ to key.encoded())
    }

    @Throws(MlsStoreException::class)
    override fun keys(namespace_: MlsNamespace): List<ByteArray> {
        failIfFailing(namespace_)

        return values.keys.filter { it.first == namespace_ }.map { Base64.Default.decode(it.second) }
    }

    private fun failIfFailing(namespace: MlsNamespace) {
        if (namespace in failing) throw MlsStoreException.Failed(v1 = "test")
    }

    private fun ByteArray.encoded() = Base64.Default.encode(this)
}
