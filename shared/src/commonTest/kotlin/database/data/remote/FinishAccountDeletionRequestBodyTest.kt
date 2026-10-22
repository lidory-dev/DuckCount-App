package database.data.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FinishAccountDeletionRequestBodyTest {

    /** As the client encodes every body, leaving out what is left at its default. */
    private val json = Json { ignoreUnknownKeys = true }

    /** The server refuses a body without them, none being still something it has to be told. */
    @Test
    fun `the group commits go out empty`() {
        val body = json.encodeToString(FinishAccountDeletionRequestBody(credentialFinalizationBytes = "AAEC"))

        assertEquals("""{"credential_finalization_bytes":"AAEC","group_commits":[]}""", body)
    }
}
