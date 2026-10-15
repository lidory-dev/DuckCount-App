package mls

import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.MlsStore
import kotlin.io.encoding.Base64

/** Holds an MLS client's state for the length of a test. */
internal class InMemoryMlsStore : MlsStore {
    private val values = HashMap<Pair<MlsNamespace, String>, ByteArray>()

    override fun get(namespace_: MlsNamespace, key: ByteArray): ByteArray? = values[namespace_ to key.encoded()]

    override fun put(namespace_: MlsNamespace, key: ByteArray, value: ByteArray) {
        values[namespace_ to key.encoded()] = value
    }

    override fun delete(namespace_: MlsNamespace, key: ByteArray) {
        values.remove(namespace_ to key.encoded())
    }

    override fun keys(namespace_: MlsNamespace): List<ByteArray> =
        values.keys.filter { it.first == namespace_ }.map { Base64.Default.decode(it.second) }

    private fun ByteArray.encoded() = Base64.Default.encode(this)
}
