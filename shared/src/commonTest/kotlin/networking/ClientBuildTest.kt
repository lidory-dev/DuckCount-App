package networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val URL = "https://api.duckcount.test/expenses"

/**
 * What every request says of the build, and what the server refusing that build does, see
 * [nameClientBuild].
 */
class ClientBuildTest {
    private val updateRequirement = UpdateRequirement()

    private fun engineAnswering(status: HttpStatusCode) = MockEngine { respond(content = "", status = status) }

    private fun clientOn(engine: MockEngine) = HttpClient(engine) {
        nameClientBuild(updateRequirement = updateRequirement)
    }

    @Test
    fun `a request names the build`() = runBlocking<Unit> {
        val engine = engineAnswering(HttpStatusCode.OK)

        clientOn(engine).get(URL)

        assertEquals(clientBuild, engine.requestHistory.single().headers[CLIENT_BUILD_HEADER])
    }

    @Test
    fun `a 426 requires the update and still reaches the caller`() = runBlocking<Unit> {
        val response = clientOn(engineAnswering(HttpStatusCode.UpgradeRequired)).get(URL)

        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        assertTrue(updateRequirement.isRequired.value)
    }

    @Test
    fun `no other answer requires the update`() = runBlocking<Unit> {
        listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError).forEach { status ->
            clientOn(engineAnswering(status)).get(URL)

            assertFalse(updateRequirement.isRequired.value, "after a $status")
        }
    }

    /** Nothing has to handle it request by request, the screen asking for the update doing it all. */
    @Test
    fun `a 426 comes out of a call as an unknown error`() = runBlocking<Unit> {
        val client = object : Client() {
            override val httpClient = clientOn(engineAnswering(HttpStatusCode.UpgradeRequired))
        }

        assertEquals(Result.Error(NetworkError.UNKNOWN), client.get<Unit>(route = URL))
        assertTrue(updateRequirement.isRequired.value)
    }
}
