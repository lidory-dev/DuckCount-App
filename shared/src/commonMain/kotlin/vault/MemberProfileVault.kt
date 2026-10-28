package vault

import util.EmptyResult
import util.Result

/**
 * The keys to the profiles of the members of the user's groups, as the vault keeps them, see
 * [MemberProfileBook]: what [MemberProfileRepository][group.MemberProfileRepository] needs of the
 * vault, and nothing more. [VaultRepository] is the one there is.
 *
 * Its own interface so that what the backup and the restore do with the book can be checked
 * without a server.
 */
interface MemberProfileVault {

    /** See [VaultRepository.getMemberProfileBook]. */
    suspend fun getMemberProfileBook(): Result<MemberProfileBook, VaultError>

    /** See [VaultRepository.updateMemberProfiles]. */
    suspend fun updateMemberProfiles(added: Map<String, VaultMemberProfile>): EmptyResult<VaultError>
}
