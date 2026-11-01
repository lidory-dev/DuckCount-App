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
import networking.UpdateRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAN = "44444444-4444-4444-4444-444444444444"
private const val EVE = "55555555-5555-5555-5555-555555555555"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * The searches of the contact list, of a group's members and of a group expense, run on the
 * database itself, each filter taken without the whitespace around it, the search field holding it
 * as typed, and matched as typed, a `%` or an `_` in it included.
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

    /** A `%` or an `_` would otherwise stand for any text or any one character, and list everybody. */
    @Test
    fun `a search of a wildcard finds nobody whose name holds none`() = runBlocking<Unit> {
        withUsers { users ->
            listOf("%", "_", "\\", "A_ice", "Al%e").forEach { filter ->
                assertTrue(users.getAllFiltered(filter = filter).uuids().isEmpty(), filter)
                assertTrue(users.getInvitableFiltered(filter = filter).uuids().isEmpty(), filter)
                assertTrue(users.getAllFromGroupFiltered(filter = filter, groupUuid = GROUP).uuids().isEmpty(), filter)
            }
        }
    }

    @Test
    fun `a name holding a percent sign an underscore or a backslash is found by it`() = runBlocking<Unit> {
        withUsers(
            UserEntity(uuid = CAROL, username = "carol", fullName = "Carol 100%"),
            UserEntity(uuid = DAN, username = "dan", fullName = "Dan_Lee"),
            UserEntity(uuid = EVE, username = "eve", fullName = "Eve\\Doe"),
        ) { users ->
            mapOf("%" to CAROL, "100%" to CAROL, "_" to DAN, "n_L" to DAN, "\\" to EVE).forEach { (filter, found) ->
                assertEquals(listOf(found), users.getAllFiltered(filter = filter).uuids(), filter)
                assertEquals(listOf(found), users.getInvitableFiltered(filter = filter).uuids(), filter)
                assertEquals(
                    listOf(found),
                    users.getAllFromGroupFiltered(filter = filter, groupUuid = GROUP).uuids(),
                    filter,
                )
            }
        }
    }

    /**
     * Runs [test] on the users of a fresh database holding Alice and Bob, and [others], each a
     * contact of the user's and a member of [GROUP] as they are.
     */
    private suspend fun withUsers(vararg others: UserEntity, test: suspend (UserRepository) -> Unit) {
        withInMemoryDatabase { database ->
            database.seed(others = others.toList())

            test(
                UserRepository(
                    userDao = database.userDao,
                    userApi = UserApi(
                        client = DuckCountClient(
                            sessionExpiry = SessionExpiry(),
                            localDataDao = database.localDataDao,
                            sessionLeftovers = emptyList(),
                            updateRequirement = UpdateRequirement(),
                        ),
                    ),
                ),
            )
        }
    }

    /** Puts Alice, Bob and [others] in the database, as contacts of the user's and members of [GROUP]. */
    private suspend fun AppDatabase.seed(others: List<UserEntity>) {
        val users = listOf(
            UserEntity(uuid = ALICE, username = "alice", fullName = "Alice Martin"),
            UserEntity(uuid = BOB, username = "bob", fullName = "Bob Durand"),
        ) + others

        users.forEach { userDao.insert(it) }

        users.forEach {
            contactDao.add(userUuid = it.uuid, profileEncryptionKey = ByteArray(32), handedProfileKeyHash = null)
        }

        groupDao.insertMembers(
            users.map { GroupMemberEntity(userUuid = it.uuid, groupUuid = GROUP, isAdmin = it.uuid == ALICE) },
        )
    }
}

/** The UUIDs of the users found, sorted. */
private suspend fun Flow<List<User>>.uuids(): List<String> =
    first().map { it.uuid }.sorted()
