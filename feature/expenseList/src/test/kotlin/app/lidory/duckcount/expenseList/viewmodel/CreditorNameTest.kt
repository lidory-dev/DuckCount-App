package app.lidory.duckcount.expenseList.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The name the expense form shows for the creditor, see [creditorNames], as they are picked and as names are read. */
class CreditorNameTest {

    private val aliceName = MutableStateFlow<String?>("Alice")
    private val bobName = MutableStateFlow<String?>(null)

    private fun nameOf(userUuid: String): Flow<String?> = when (userUuid) {
        "alice" -> aliceName
        "bob" -> bobName
        else -> error("No such user $userUuid")
    }

    @Test
    fun `a name read after the form opened shows`() = runBlocking {
        val creditor = MutableStateFlow("bob")
        val names = Channel<String>(Channel.UNLIMITED)
        val collection = launch { creditorNames(creditorUuids = creditor, nameOf = ::nameOf).collect { names.send(it) } }

        withTimeout(5_000) {
            // Until it is read, see the TODO.
            assertEquals("bob", names.receive())

            bobName.value = "Bob"

            assertEquals("Bob", names.receive())
        }

        collection.cancel()
    }

    @Test
    fun `the name of a creditor picked before never comes back`() = runBlocking {
        val creditor = MutableStateFlow("alice")
        val names = Channel<String>(Channel.UNLIMITED)
        val collection = launch { creditorNames(creditorUuids = creditor, nameOf = ::nameOf).collect { names.send(it) } }

        withTimeout(5_000) {
            assertEquals("Alice", names.receive())

            bobName.value = "Bob"
            creditor.value = "bob"

            assertEquals("Bob", names.receive())

            // Told before the one after it, were Alice's name still read.
            aliceName.value = "Alice Liddell"
            bobName.value = "Robert"

            assertEquals("Robert", names.receive())
        }

        collection.cancel()
    }
}
