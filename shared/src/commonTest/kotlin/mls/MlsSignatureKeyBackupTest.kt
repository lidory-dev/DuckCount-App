package mls

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MlsSignatureKeyBackupTest {

    private val privateKey = byteArrayOf(1, 2, 3, 4)
    private val publicKey = byteArrayOf(5, 6, 7)

    @Test
    fun `a backup survives being sealed and read back`() {
        val backup = MlsSignatureKeyBackup(privateKey = privateKey, publicKey = publicKey)

        val reread = MlsSignatureKeyBackup.parse(plaintext = backup.serialize())

        assertNotNull(reread)
        assertContentEquals(privateKey, reread.privateKey)
        assertContentEquals(publicKey, reread.publicKey)
    }

    @Test
    fun `the two halves are never swapped`() {
        val plaintext = """{"public_key":"BQYH","private_key":"AQIDBA=="}"""

        val reread = MlsSignatureKeyBackup.parse(plaintext = plaintext.encodeToByteArray())

        assertNotNull(reread)
        assertContentEquals(privateKey, reread.privateKey)
        assertContentEquals(publicKey, reread.publicKey)
    }

    @Test
    fun `bytes that are not a backup read as none`() {
        assertNull(MlsSignatureKeyBackup.parse(plaintext = "not a backup".encodeToByteArray()))
        assertNull(MlsSignatureKeyBackup.parse(plaintext = """{"private_key":"AQIDBA=="}""".encodeToByteArray()))
    }
}
