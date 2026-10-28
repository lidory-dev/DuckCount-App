package app.lidory.duckcount.groupList.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The names this device holds, as the database tells them: null for somebody it has none for, until
 * one is [set], which whoever observes them is told of.
 */
class FakeNames(vararg names: Pair<String, String?>) {

    private val flows = HashMap<String, MutableStateFlow<String?>>()

    init {
        names.forEach { (userUuid, name) -> set(userUuid, name) }
    }

    fun of(userUuid: String): Flow<String?> = nameFlow(userUuid)

    operator fun set(userUuid: String, name: String?) {
        nameFlow(userUuid).value = name
    }

    private fun nameFlow(userUuid: String) = flows.getOrPut(userUuid) { MutableStateFlow(null) }
}

/**
 * Runs [block] with what [flow] emits, in order, as it is collected meanwhile, then stops collecting
 * it: a flow of names never ends.
 */
fun <T> collecting(flow: Flow<T>, block: suspend (emissions: ReceiveChannel<T>) -> Unit): Unit = runBlocking {
    val emissions = Channel<T>(Channel.UNLIMITED)
    val collection = launch { flow.collect { emissions.send(it) } }

    try {
        withTimeout(5_000) { block(emissions) }
    } finally {
        collection.cancel()
    }
}
