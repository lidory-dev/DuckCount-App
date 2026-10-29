package database.data.repository

import authenticationUtils.SessionExpiry
import database.AppDatabase
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.UserEntity
import database.data.remote.UserApi
import database.domain.User
import database.withInMemoryDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import networking.DuckCountClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * The searches of the contact list, of a group's members and of a group expense, run on the
 * database itself, each filter taken without the whitespace around it: the search field holds it
 * as typed.
 *
 * Alice Martin and Bob Durand are contacts of the user's, and members of [GROUP].
 */
class UserRepositoryTest {

    /** As a space typed after a name, or a name pasted with a line after it. */
    @Test
    fun `a search with whitespace around it finds the same people`() = runBlocking<Unit> {
        withUsers { users ->
            listOf("Alice", "Alice ", "  Alice", "\tAlice\n").forEach { filter ->
                assertEquals(listOf(ALICE), users.getAllFiltered(filter = filter).uuids(), filter)
                assertEquals(listOf(ALICE), users.getInvitableFiltered(filter = filter).uuids(), filter)
                assertEquals(
                    listOf(ALICE),
                    users.getAllFromGroupFiltered(filter = filter, groupUuid = GROUP).uuids(),
                    filter,
                )
            }
        }
    }

    @Test
    fun `a search of nothing but whitespace finds everybody`() = runBlocking<Unit> {
        withUsers { users ->
            assertEquals(listOf(ALICE, BOB), users.getAllFiltered(filter = "  ").uuids())
            assertEquals(listOf(ALICE, BOB), users.getInvitableFiltered(filter = "\n").uuids())
            assertEquals(listOf(ALICE, BOB), users.getAllFromGroupFiltered(filter = " ", groupUuid = GROUP).uuids())
        }
    }

    /** Nothing inside it is collapsed. */
    @Test
    fun `a search keeps the whitespace inside it`() = runBlocking<Unit> {
        withUsers { users ->
            assertEquals(listOf(ALICE), users.getAllFiltered(filter = " Alice Martin ").uuids())
            assertTrue(users.getAllFiltered(filter = "Alice  Martin").uuids().isEmpty())
        }
    }

    /** Runs [test] on the users of a fresh database holding Alice and Bob. */
    private suspend fun withUsers(test: suspend (UserRepository) -> Unit) {
        withInMemoryDatabase { database ->
            database.seed()

            test(
                UserRepository(
                    userDao = database.userDao,
                    userApi = UserApi(
                        client = DuckCountClient(
                            sessionExpiry = SessionExpiry(),
                            localDataDao = database.localDataDao,
                            sessionLeftovers = emptyList(),
                        ),
                    ),
                ),
            )
        }
    }

    /** Puts Alice and Bob in the database, as contacts of the user's and members of [GROUP]. */
    private suspend fun AppDatabase.seed() {
        userDao.insert(UserEntity(uuid = ALICE, username = "alice", fullName = "Alice Martin"))
        userDao.insert(UserEntity(uuid = BOB, username = "bob", fullName = "Bob Durand"))

        listOf(ALICE, BOB).forEach {
            contactDao.add(userUuid = it, profileEncryptionKey = ByteArray(32), handedProfileKeyHash = null)
        }

        groupDao.insertMembers(
            listOf(ALICE, BOB).map { GroupMemberEntity(userUuid = it, groupUuid = GROUP, isAdmin = it == ALICE) },
        )
    }
}

/** The UUIDs of the users found, sorted. */
private suspend fun Flow<List<User>>.uuids(): List<String> =
    first().map { it.uuid }.sorted()
