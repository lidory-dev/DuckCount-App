package group

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs [block] as the only thing changing which groups this device holds: creating one, see
 * [GroupCreator], leaving one, see [GroupLeaver], and the group sync, which sends the creations
 * still pending, joins the groups the user was invited to and puts up the copies of their keys.
 *
 * Each reads what is stored, talks to the server and writes back what it heard, and two at once
 * would each act on what the other is halfway through: a creation still waiting on its answer
 * sent again alongside it, or the copy of a group's key put up just after a leave took it down,
 * see [GroupLeaver]: a log-out before the server hears the leave would bring the group back with
 * it, as one the user is still in. An edit of a group's information takes it as well, see
 * [GroupEditor]: a key rotation built alongside it would put back the version it replaced.
 *
 * Process-wide, like the store the groups live in: one MLS client is opened per process, see
 * [mls.MlsIdentityRepository]. Not reentrant, so nothing called under it may take it again,
 * [GroupCreationSender.send] included, which is why it takes none itself, nor does
 * [GroupMembershipChanger.removeUnderGroupsLock], which the sync removes members with.
 */
internal suspend fun <T> withGroupsLock(block: suspend () -> T): T = groupsLock.withLock { block() }

/**
 * Runs [block] as the only thing between the library storing a group the user was added to and
 * its being saved as one of their groups, see [GroupSync], or between a direct message being
 * checked against those groups and its being read, see [message.DirectMessageSync].
 *
 * A direct message naming one of the user's groups is left unread, and which those are is what
 * the database says, see [database.data.local.dao.GroupDao.hasMlsGroup]. A group joined is stored
 * by the library first and saved to the database after, so a direct message read in between would
 * be read in it, and, from a member who is a contact, have it become the channel to them. A group
 * the user creates needs none of this: its id comes from a UUID nobody else knows until the
 * creation is sent, which is only once it is saved.
 *
 * Narrower than [withGroupsLock], which a group sync holds across its requests to the server, so
 * that reading direct messages never waits on those. Process-wide for the same reason. The group
 * sync takes it under [withGroupsLock], and nothing takes that one under this. Not reentrant
 * either.
 */
internal suspend fun <T> withGroupJoinLock(block: suspend () -> T): T = groupJoinLock.withLock { block() }

/** See [withGroupsLock]. */
private val groupsLock = Mutex()

/** See [withGroupJoinLock]. */
private val groupJoinLock = Mutex()
