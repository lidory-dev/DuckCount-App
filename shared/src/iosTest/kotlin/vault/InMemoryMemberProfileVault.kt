package vault

import util.EmptyResult
import util.NetworkError
import util.Result

/**
 * Holds the keys the user's groups handed over for the length of a test, as the vault would, see
 * [MemberProfileBook]: written against the version read, a write another device made in between
 * refusing this one's.
 */
internal class InMemoryMemberProfileVault : MemberProfileVault {

    /** What the vault holds. */
    var book: MemberProfileBook = MemberProfileBook.EMPTY

    /** How many times the book was read to be restored. */
    var reads = 0
        private set

    /** How many writes landed. */
    var writes = 0
        private set

    /** How many times the signature keys of the groups' members were carried to the vault. */
    var memberKeyUpdates = 0
        private set

    /**
     * What other devices write ahead of this one's next writes, one each, every one of them
     * refusing this device's write as [VaultError.Contended].
     */
    val racingWrites = ArrayDeque<Map<String, VaultMemberProfile>>()

    /** Runs during each write, between its read and its landing. */
    var duringWrite: suspend () -> Unit = {}

    /** Whether reading fails, as a vault out of reach would. */
    var failsReading = false

    /** Whether writing fails, as a vault out of reach would. */
    var failsWriting = false

    override suspend fun getMemberProfileBook(): Result<MemberProfileBook, VaultError> {
        reads++

        if (failsReading) return Result.Error(VaultError.Network(NetworkError.UNREACHABLE))

        return Result.Success(reread())
    }

    override suspend fun updateMemberProfiles(added: Map<String, VaultMemberProfile>): EmptyResult<VaultError> {
        if (failsWriting) return Result.Error(VaultError.Network(NetworkError.UNREACHABLE))

        val read = reread()

        duringWrite()

        racingWrites.removeFirstOrNull()?.let { racing ->
            book = book.withChanges(added = racing)

            return Result.Error(VaultError.Contended)
        }

        book = read.withChanges(added = added)
        writes++

        return Result.Success(Unit)
    }

    override suspend fun updateGroupMemberKeys(
        changed: Map<String, Map<String, ByteArray>>,
        forgotten: Set<String>,
    ): EmptyResult<VaultError> {
        memberKeyUpdates++

        if (failsWriting) return Result.Error(VaultError.Network(NetworkError.UNREACHABLE))

        val read = reread()

        duringWrite()

        racingWrites.removeFirstOrNull()?.let { racing ->
            book = book.withChanges(added = racing)

            return Result.Error(VaultError.Contended)
        }

        val updated = read.withMemberKeys(changed = changed, forgotten = forgotten)

        if (updated.holdsSameAs(read)) return Result.Success(Unit)

        book = updated
        writes++

        return Result.Success(Unit)
    }

    /** The book as a device reading it gets it, through its plaintext. */
    private fun reread(): MemberProfileBook = checkNotNull(MemberProfileBook.parse(plaintext = book.serialize()))
}
