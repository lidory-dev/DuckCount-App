package database.data.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FinishAccountDeletionRequestBodyTest {

    /** As the client encodes every body, leaving out what is left at its default. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Nothing of the user's groups: their members' devices remove the user's leaf, see `group.GroupSync`. */
    @Test
    fun `the deletion carries the finalization alone`() {
        val body = json.encodeToString(FinishAccountDeletionRequestBody(credentialFinalizationBytes = "AAEC"))

        assertEquals("""{"credential_finalization_bytes":"AAEC"}""", body)
    }
}
