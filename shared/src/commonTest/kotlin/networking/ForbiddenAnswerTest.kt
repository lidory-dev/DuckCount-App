package networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals

private const val URL = "https://api.duckcount.test/auth/registrations"

/** What a `403` comes to, the answer of a phone the server will not register among others. */
class ForbiddenAnswerTest {
    private var sessionEndings = 0

    private val client = object : Client() {
        override val httpClient = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) })

        override suspend fun onUnauthorized() {
            sessionEndings += 1
        }
    }

    @Test
    fun `a 403 comes out of a call as forbidden`() = runBlocking<Unit> {
        assertEquals(Result.Error(NetworkError.FORBIDDEN), client.get<Unit>(route = URL))
    }

    /** Refusing a request says nothing of the session, which a `401` would end. */
    @Test
    fun `a 403 leaves the session alone`() = runBlocking<Unit> {
        client.get<Unit>(route = URL)

        assertEquals(0, sessionEndings)
    }
}
