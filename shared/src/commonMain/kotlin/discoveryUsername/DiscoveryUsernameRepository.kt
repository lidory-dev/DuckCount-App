package discoveryUsername

import app.lidory.duckcount.normalizeUsername
import database.data.repository.UserRepository
import util.EmptyResult
import util.Result
import vault.VaultRepository
import authenticationUtils.getDiscoveryUsername as getCachedDiscoveryUsername
import authenticationUtils.setDiscoveryUsername as cacheDiscoveryUsername

/**
 * The handle the user reserved to be discoverable by.
 *
 * Three stores hold a piece of it and only one of them is authoritative:
 *
 * - the **server** holds the digest, which is what answers a contact lookup. It is
 *   one-way, so the server can never say what handle it stands for;
 * - the **vault** holds the handle itself, sealed. This is the source of truth for
 *   what the user reserved, and the only copy that survives a reinstall;
 * - the **client** keeps it in plaintext so the screens that show it can read it
 *   without a round trip. A cache, dropped on log out and refilled from the vault.
 */
class DiscoveryUsernameRepository(
    private val userRepository: UserRepository,
    private val vaultRepository: VaultRepository,
) {

    /**
     * Reserves [username], replacing whatever the user had reserved before.
     *
     * The three writes are ordered so that no store ends up claiming something another
     * one contradicts. The reservation goes first because it is the only one that can
     * be refused, the handle may already be somebody else's; the vault second, so the
     * device never names a handle the account does not hold; the local copy last.
     */
    suspend fun reserve(
        userUuid: String,
        username: String,
    ): EmptyResult<DiscoveryUsernameError> {
        val reservation = userRepository.reserveUsername(userUuid = userUuid, username = username)

        if (reservation is Result.Error) {
            return Result.Error(DiscoveryUsernameError.Reservation(reservation.error))
        }

        // The digest the server just stored is computed from the normalized form, so
        // that is the handle the vault has to name.
        val normalizedUsername = normalizeUsername(username = username)

        val write = vaultRepository.setDiscoveryUsername(username = normalizedUsername)

        if (write is Result.Error) {
            return Result.Error(DiscoveryUsernameError.Vault(write.error))
        }

        cacheDiscoveryUsername(username = normalizedUsername)

        return Result.Success(Unit)
    }

    /**
     * Brings this device and the vault into agreement, which is what makes the handle
     * survive a reinstall.
     *
     * The vault wins whenever it holds one, so a handle reserved on another device
     * lands here.
     */
    suspend fun syncDiscoveryUsername(): EmptyResult<DiscoveryUsernameError> {
        val stored = when (val read = vaultRepository.getDiscoveryUsername()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(DiscoveryUsernameError.Vault(read.error))
        }

        if (stored != null) {
            cacheDiscoveryUsername(username = stored)

            return Result.Success(Unit)
        }

        // The vault holds none, so the local copy is the only place a handle can be.
        // Nothing there either means the user has simply never reserved one.
        val cached = getCachedDiscoveryUsername() ?: return Result.Success(Unit)

        return when (val write = vaultRepository.setDiscoveryUsername(username = cached)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(DiscoveryUsernameError.Vault(write.error))
        }
    }
}
