package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import database.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(userEntity: UserEntity)

    @Update
    suspend fun update(userEntity: UserEntity)

    @Delete
    suspend fun delete(userEntity: UserEntity)

    @Query("UPDATE UserEntity SET isDeleted = true WHERE uuid = :userUuid")
    suspend fun remove(userUuid: String)

    @Query("SELECT * FROM UserEntity WHERE isDeleted = false")
    fun getAllAsFlow(): Flow<List<UserEntity>>

    @Query("SELECT * FROM UserEntity WHERE fullName LIKE '%' ||:filter|| '%' AND isDeleted = false")
    fun getAllFiltered(filter: String): Flow<List<UserEntity>>

    /**
     * Who the user can put in a group they create, whose name contains [filter]: their contacts,
     * removed and blocked ones aside, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity], with a name to
     * show for them. Never the user themself, who is nobody's contact.
     *
     * A contact this device pinned no identity for is listed all the same, which only the MLS
     * client can tell, and the creation names them, see `group.GroupCreationError.NotContacts`.
     */
    @Query(
        """SELECT UserEntity.* FROM UserEntity
        INNER JOIN ContactEntity ON ContactEntity.userUuid = UserEntity.uuid
        WHERE UserEntity.fullName LIKE '%' ||:filter|| '%'
        AND UserEntity.isDeleted = false
        AND ContactEntity.isRemoved = false
        AND UserEntity.uuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)""",
    )
    fun getInvitableFiltered(filter: String): Flow<List<UserEntity>>

    @Transaction
    @Query("SELECT * FROM UserEntity WHERE uuid = :uuid")
    fun getUserAsFlow(uuid: String): Flow<UserEntity?>

    @Query("SELECT fullName FROM UserEntity WHERE uuid = :uuid")
    fun getUserFullNameAsFlow(uuid: String): Flow<String?>

    /** The same name, read once, for a caller that is not observing a screen. */
    @Query("SELECT fullName FROM UserEntity WHERE uuid = :uuid AND isDeleted = false")
    suspend fun getFullName(uuid: String): String?

    /** The name the expenses show for [uuid], read once: theirs whether they are hidden or not. */
    @Query("SELECT fullName FROM UserEntity WHERE uuid = :uuid")
    suspend fun getFullNameEvenIfHidden(uuid: String): String?

    /**
     * Emits the name the group and expense screens show for [uuid] as it changes, or null while
     * this device has none for them.
     *
     * The user's own, or a contact's, first: what the contact list shows, see
     * [ContactRepository.resolveNames][database.data.repository.ContactRepository.resolveNames].
     * Then the one read with the key a group handed over, see
     * [MemberProfileEntity][database.data.local.entity.MemberProfileEntity], which names a member
     * who is no contact, a former contact among them. Last, the one a former contact left, see
     * [remove], for one whose name no group handed the key to.
     *
     * The other name queries stay as they are: what tells whether a notification names somebody,
     * see [getFullName], is whether they are a contact.
     */
    @Query(
        """SELECT COALESCE(
        (SELECT fullName FROM UserEntity WHERE uuid = :uuid AND isDeleted = false),
        (SELECT fullName FROM MemberProfileEntity WHERE userUuid = :uuid),
        (SELECT fullName FROM UserEntity WHERE uuid = :uuid AND isDeleted = true)
        ) AS fullName""",
    )
    fun getDisplayNameAsFlow(uuid: String): Flow<String?>

    /**
     * Emits who a group expense of the group [groupUuid] can name, as payer or beneficiary, as they
     * change: each of its members this device has a name for, see [getDisplayNameAsFlow], once,
     * under that name, whose name contains [filter]. A member it has none for is left out, there
     * being nothing to show for them.
     *
     * A member who is a contact, or the user, comes with the row the contact list shows. Anybody
     * else comes as nobody's contact, hidden from the contact list, see [remove], with no username,
     * which only the user's own is known by.
     */
    @Query(
        """SELECT uuid, username, fullName, isDeleted FROM (
            SELECT GroupMemberEntity.userUuid AS uuid,
            COALESCE(UserEntity.username, '') AS username,
            COALESCE(
                CASE WHEN UserEntity.isDeleted = false THEN UserEntity.fullName END,
                MemberProfileEntity.fullName,
                UserEntity.fullName
            ) AS fullName,
            COALESCE(UserEntity.isDeleted, true) AS isDeleted
            FROM GroupMemberEntity
            LEFT JOIN UserEntity ON UserEntity.uuid = GroupMemberEntity.userUuid
            LEFT JOIN MemberProfileEntity ON MemberProfileEntity.userUuid = GroupMemberEntity.userUuid
            WHERE GroupMemberEntity.groupUuid = :groupUuid
        )
        WHERE fullName LIKE '%' ||:filter|| '%'""",
    )
    fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<UserEntity>>

    @Query("UPDATE UserEntity SET fullName = :fullName WHERE uuid = :userUuid")
    suspend fun updateFullName(userUuid: String, fullName: String)
}