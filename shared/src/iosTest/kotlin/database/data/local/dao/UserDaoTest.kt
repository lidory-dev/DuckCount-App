package database.data.local.dao

import database.AppDatabase
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.MemberProfileEntity
import database.data.local.entity.UserEntity
import database.withInMemoryDatabase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"
private const val ERIN = "55555555-5555-5555-5555-555555555555"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * The names the group and expense screens show, see [UserDao.getDisplayNameAsFlow], and who a
 * group expense can name, see [UserDao.getAllFromGroupFiltered], run on the database itself.
 *
 * Alice is a contact, Bob the user, Carol a member nobody but a group named, Erin a former contact
 * a group named since, Dave a former contact no group named, and Mallory a member nothing names.
 */
class UserDaoTest {

    @Test
    fun `a contact is named as the contact list names them before any group does`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.userDao.insert(UserEntity(uuid = ALICE, username = "alice", fullName = "Alice Martin"))
            database.memberProfileDao.upsert(memberProfileOf(ALICE, fullName = "Alice M."))

            assertEquals("Alice Martin", database.userDao.getDisplayNameAsFlow(uuid = ALICE).first())
        }
    }

    /** The name a former contact left is the one shown when they were removed, read before the group's. */
    @Test
    fun `a former contact is named as a group named them before as they were when removed`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.userDao.insert(UserEntity(uuid = ERIN, username = "", fullName = "Erin Old", isDeleted = true))
                database.memberProfileDao.upsert(memberProfileOf(ERIN, fullName = "Erin New"))

                assertEquals("Erin New", database.userDao.getDisplayNameAsFlow(uuid = ERIN).first())
            }
        }

    @Test
    fun `a former contact no group named is named as they were when removed`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.userDao.insert(UserEntity(uuid = DAVE, username = "", fullName = "Dave", isDeleted = true))
            database.memberProfileDao.upsert(memberProfileOf(DAVE, fullName = null))

            assertEquals("Dave", database.userDao.getDisplayNameAsFlow(uuid = DAVE).first())
        }
    }

    @Test
    fun `somebody nothing names has no name`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.memberProfileDao.upsert(memberProfileOf(MALLORY, fullName = null))

            assertNull(database.userDao.getDisplayNameAsFlow(uuid = MALLORY).first())
            assertNull(database.userDao.getDisplayNameAsFlow(uuid = CAROL).first())
        }
    }

    /** Read after the screen opened, as the sync reads the names once the groups handed the keys over. */
    @Test
    fun `the name shown comes in as a group's key is read`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            val key = ByteArray(32) { 3 }
            val shown = Channel<String?>(Channel.UNLIMITED)
            val showing = launch { database.userDao.getDisplayNameAsFlow(uuid = CAROL).collect { shown.send(it) } }

            assertNull(withTimeout(TIMEOUT_MILLIS) { shown.receive() })

            database.memberProfileDao.fillGap(userUuid = CAROL, profileEncryptionKey = key)
            database.memberProfileDao.setName(
                userUuid = CAROL,
                profileEncryptionKey = key,
                fullName = "Carol Blanc",
                nameKeyHash = "carol",
            )

            withTimeout(TIMEOUT_MILLIS) {
                while (shown.receive() != "Carol Blanc") continue
            }

            showing.cancel()
        }
    }

    @Test
    fun `a group expense can name every member with a name once under the name shown for them`() =
        runBlocking<Unit> {
            withInMemoryDatabase { database ->
                database.seedGroup()

                assertEquals(
                    listOf(
                        UserEntity(uuid = ALICE, username = "alice", fullName = "Alice Martin", isDeleted = false),
                        UserEntity(uuid = BOB, username = "bob", fullName = "Bob Durand", isDeleted = false),
                        UserEntity(uuid = CAROL, username = "", fullName = "Carol Blanc", isDeleted = true),
                        UserEntity(uuid = DAVE, username = "", fullName = "Dave", isDeleted = true),
                        UserEntity(uuid = ERIN, username = "", fullName = "Erin New", isDeleted = true),
                    ),
                    database.userDao.getAllFromGroupFiltered(filter = "", groupUuid = GROUP).first()
                        .sortedBy { it.uuid },
                )
            }
        }

    /** Erin is shown under the name a group named her with, not the one she left as a contact. */
    @Test
    fun `the filter applies to the name shown`() = runBlocking<Unit> {
        withInMemoryDatabase { database ->
            database.seedGroup()

            assertEquals(
                listOf(ERIN),
                database.userDao.getAllFromGroupFiltered(filter = "New", groupUuid = GROUP).first().map { it.uuid },
            )
            assertTrue(database.userDao.getAllFromGroupFiltered(filter = "Old", groupUuid = GROUP).first().isEmpty())
        }
    }

    /**
     * Puts Alice, Bob, Carol, Dave, Erin and Mallory in [GROUP], and Alice and Carol in
     * [OTHER_GROUP] too, each named as the class documentation says.
     */
    private suspend fun AppDatabase.seedGroup() {
        userDao.insert(UserEntity(uuid = ALICE, username = "alice", fullName = "Alice Martin"))
        userDao.insert(UserEntity(uuid = BOB, username = "bob", fullName = "Bob Durand"))
        userDao.insert(UserEntity(uuid = DAVE, username = "", fullName = "Dave", isDeleted = true))
        userDao.insert(UserEntity(uuid = ERIN, username = "", fullName = "Erin Old", isDeleted = true))

        memberProfileDao.upsert(memberProfileOf(ALICE, fullName = "Alice M."))
        memberProfileDao.upsert(memberProfileOf(CAROL, fullName = "Carol Blanc"))
        memberProfileDao.upsert(memberProfileOf(ERIN, fullName = "Erin New"))
        memberProfileDao.upsert(memberProfileOf(MALLORY, fullName = null))

        groupDao.insertMembers(
            listOf(ALICE, BOB, CAROL, DAVE, ERIN, MALLORY).map {
                GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE)
            } + listOf(ALICE, CAROL).map {
                GroupMemberEntity(userUuid = it, groupUuid = OTHER_GROUP, isAdmin = it == ALICE)
            },
        )
    }
}

/** How long a name may take to come in once written. */
private const val TIMEOUT_MILLIS = 5_000L

/** What a group handed over for [userUuid], with [fullName] read with it, if any. */
private fun memberProfileOf(userUuid: String, fullName: String?) =
    MemberProfileEntity(
        userUuid = userUuid,
        profileEncryptionKey = ByteArray(32) { userUuid.first().code.toByte() },
        isFromOwner = false,
        ownerKeySentAt = null,
        fullName = fullName,
        nameKeyHash = fullName?.let { userUuid },
        isBackedUp = true,
    )
