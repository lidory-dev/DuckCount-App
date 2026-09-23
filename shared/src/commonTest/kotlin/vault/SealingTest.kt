package vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The binding half of [sealBound] and [unsealBound], which is where their security
 * property lives. The sealing half is AES-GCM in `lib-duckcount` and has its own tests
 * there.
 */
class SealingTest {

    @Test
    fun `what was bound under a context comes back under that context`() {
        val bound = bind(context = "amount", plaintext = "12.34".encodeToByteArray())!!

        assertContentEquals(
            expected = "12.34".encodeToByteArray(),
            actual = unbind(context = "amount", bound = bound),
        )
    }

    @Test
    fun `an empty plaintext round trips`() {
        val bound = bind(context = "amount", plaintext = ByteArray(0))!!

        assertContentEquals(expected = ByteArray(0), actual = unbind(context = "amount", bound = bound))
    }

    @Test
    fun `what was bound under one context does not come back under another`() {
        val bound = bind(context = "amount", plaintext = "12.34".encodeToByteArray())!!

        assertNull(unbind(context = "timestamp", bound = bound))
    }

    /**
     * The swap the binding exists to stop: two beneficiaries' shares of one expense are
     * sealed under the same key, so only the context tells them apart.
     */
    @Test
    fun `a share bound for one beneficiary does not come back as another's`() {
        val bob = "share:11111111-1111-1111-1111-111111111111"
        val carol = "share:22222222-2222-2222-2222-222222222222"

        val bound = bind(context = bob, plaintext = "40.00".encodeToByteArray())!!

        assertNull(unbind(context = carol, bound = bound))
        assertContentEquals(expected = "40.00".encodeToByteArray(), actual = unbind(context = bob, bound = bound))
    }

    /** A context another one starts with must not pass for it. */
    @Test
    fun `a context that prefixes another does not open it`() {
        val bound = bind(context = "share:abc", plaintext = "40.00".encodeToByteArray())!!

        assertNull(unbind(context = "share", bound = bound))
    }

    @Test
    fun `bytes too short to carry a context do not open`() {
        assertNull(unbind(context = "amount", bound = ByteArray(1)))
        assertNull(unbind(context = "amount", bound = ByteArray(0)))
    }

    @Test
    fun `a context too long to describe is refused rather than truncated`() {
        assertNull(bind(context = "c".repeat(n = 0x10000), plaintext = ByteArray(0)))
    }
}
