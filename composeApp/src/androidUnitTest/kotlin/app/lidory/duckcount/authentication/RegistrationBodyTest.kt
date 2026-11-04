package app.lidory.duckcount.authentication

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a registration sends the server, whose answer to anything else is a `422`, and reads back. */
class RegistrationBodyTest {

    // As DuckCountClient configures its own, leaving out a property holding its default.
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `the start names its challenge and its attestation as the server reads them`() {
        val body = RegistrationStartBody(
            bytes = "request",
            username = "digest",
            attestationChallenge = "challenge",
            attestation = DeviceAttestationBody(
                platform = ATTESTATION_PLATFORM,
                certificateChain = listOf("leaf", "batch", "intermediate", "root"),
            ),
        )

        assertEquals(
            """
            {"bytes":"request","username":"digest","attestation_challenge":"challenge",
            "attestation":{"platform":"android","certificate_chain":["leaf","batch","intermediate","root"]}}
            """.trimIndent().replace("\n", ""),
            json.encodeToString(RegistrationStartBody.serializer(), body),
        )
    }

    @Test
    fun `the finish carries no attestation`() {
        val body = RegistrationFinishBody(
            sessionId = "session",
            messageBytes = "message",
            rootKey = EncryptedFieldDto(ciphertext = "sealed", nonce = "nonce"),
        )

        assertEquals(
            """
            {"session_id":"session","message_bytes":"message",
            "root_key":{"ciphertext":"sealed","nonce":"nonce"}}
            """.trimIndent().replace("\n", ""),
            json.encodeToString(RegistrationFinishBody.serializer(), body),
        )
    }

    /** Padding and all: the start sends the string back as it came. */
    @Test
    fun `the challenge is read from the answer as it came`() {
        val answer = json.decodeFromString(
            RegistrationChallengeResponse.serializer(),
            """{"attestation_challenge":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="}""",
        )

        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", answer.attestationChallenge)
    }
}
