package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import database.data.local.entity.DirectChannelEntity

@Dao
interface DirectChannelDao {
    /** The channel to [peerUuid], or null when this device has never had one. */
    @Query("SELECT * FROM DirectChannelEntity WHERE peerUuid = :peerUuid")
    suspend fun get(peerUuid: String): DirectChannelEntity?

    /**
     * Records a channel whole, which is the only way it is ever written.
     *
     * The group and the Welcome that lets the peer into it are one fact: a Welcome left
     * attached to a group it does not open would be sent with every message and never
     * work, and a group with its Welcome dropped could never be joined at all.
     */
    @Upsert
    suspend fun upsert(channel: DirectChannelEntity)

    /** Records that the peer is in the group, so nothing more has to carry the Welcome. */
    @Query(
        """UPDATE DirectChannelEntity
        SET pendingWelcome = NULL, pendingWelcomeSince = NULL, welcomeAttachCount = 0
        WHERE peerUuid = :peerUuid""",
    )
    suspend fun markJoined(peerUuid: String)

    /** Records that one more message carried the pending Welcome. */
    @Query("UPDATE DirectChannelEntity SET welcomeAttachCount = welcomeAttachCount + 1 WHERE peerUuid = :peerUuid")
    suspend fun countWelcomeAttachment(peerUuid: String)

    /**
     * Drops the groups of the channel to [peerUuid], keeping the row.
     *
     * The row stays because the peer may still need reaching: a removed contact can still
     * hold the key to an expense the two of them share, and the next send simply builds a
     * new group. Deleting the MLS state the ids name is the caller's business.
     */
    @Query(
        """UPDATE DirectChannelEntity
        SET mlsGroupId = NULL, previousMlsGroupId = NULL, pendingWelcome = NULL,
            pendingWelcomeSince = NULL, welcomeAttachCount = 0
        WHERE peerUuid = :peerUuid""",
    )
    suspend fun clear(peerUuid: String)
}
