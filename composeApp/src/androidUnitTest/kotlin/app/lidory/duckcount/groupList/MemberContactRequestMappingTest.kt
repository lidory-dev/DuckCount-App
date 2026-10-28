package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.MemberRelation
import contact.ContactRelation
import contact.ContactSyncError
import contact.SendContactRequestError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the sheet of a member of the group is told of where the user stands with them, see
 * [toMemberRelation], and of a contact request to them that was not sent, see
 * [toContactRequestError].
 */
class MemberContactRequestMappingTest {

    @Test
    fun `every relation is told as the one of the same name`() {
        ContactRelation.entries.forEach { relation ->
            assertEquals(relation.name, relation.toMemberRelation().name)
        }

        assertEquals(ContactRelation.entries.size, MemberRelation.entries.size)
    }

    @Test
    fun `the refusals of the request are told as such`() {
        mapOf(
            SendContactRequestError.NotFound to ContactRequestError.NOT_FOUND,
            SendContactRequestError.AlreadyContact to ContactRequestError.ALREADY_CONTACT,
            SendContactRequestError.AlreadyPending to ContactRequestError.ALREADY_PENDING,
            SendContactRequestError.RateLimited to ContactRequestError.RATE_LIMITED,
            SendContactRequestError.IdentityChanged to ContactRequestError.IDENTITY_CHANGED,
            SendContactRequestError.NotInGroup to ContactRequestError.NOT_IN_GROUP,
        ).forEach { (error, expected) ->
            assertEquals(expected, error.toContactRequestError(isOnline = true))
        }
    }

    @Test
    fun `an unreachable server while offline is no internet`() {
        assertEquals(
            ContactRequestError.NO_INTERNET,
            SendContactRequestError.Network(NetworkError.UNREACHABLE).toContactRequestError(isOnline = false),
        )
    }

    @Test
    fun `an unreachable server while online is told as such`() {
        assertEquals(
            ContactRequestError.SERVER_UNREACHABLE,
            SendContactRequestError.Network(NetworkError.UNREACHABLE).toContactRequestError(isOnline = true),
        )
    }

    @Test
    fun `the server taking too long is a timeout`() {
        assertEquals(
            ContactRequestError.TIMEOUT,
            SendContactRequestError.Network(NetworkError.REQUEST_TIMEOUT).toContactRequestError(isOnline = true),
        )
    }

    /** Restoring the contacts reads the vault, where being offline shows first. */
    @Test
    fun `the vault offline while restoring the contacts is no internet`() {
        assertEquals(
            ContactRequestError.NO_INTERNET,
            SendContactRequestError.Contacts(
                ContactSyncError.Vault(VaultError.Network(NetworkError.NO_INTERNET)),
            ).toContactRequestError(isOnline = false),
        )
    }

    /** Opening the MLS client for the first time reads the vault too. */
    @Test
    fun `the vault timing out while opening the client is a timeout`() {
        assertEquals(
            ContactRequestError.TIMEOUT,
            SendContactRequestError.Identity(
                MlsIdentityError.Vault(VaultError.Network(NetworkError.REQUEST_TIMEOUT)),
            ).toContactRequestError(isOnline = true),
        )
    }

    @Test
    fun `the user's own key material failing is unknown`() {
        listOf(
            SendContactRequestError.Crypto,
            SendContactRequestError.Vault(VaultError.Crypto),
            SendContactRequestError.Identity(MlsIdentityError.MissingSession),
            SendContactRequestError.Contacts(ContactSyncError.Storage),
        ).forEach { error ->
            assertEquals(ContactRequestError.UNKNOWN, error.toContactRequestError(isOnline = true))
        }
    }

    @Test
    fun `the server refusing the request is unknown`() {
        assertEquals(
            ContactRequestError.UNKNOWN,
            SendContactRequestError.Network(NetworkError.BAD_REQUEST).toContactRequestError(isOnline = true),
        )
    }

    /** A member is addressed by their UUID and the user has no sheet of their own. */
    @Test
    fun `what a member's sheet never meets is unknown`() {
        listOf(SendContactRequestError.InvalidUsername, SendContactRequestError.Self).forEach { error ->
            assertEquals(ContactRequestError.UNKNOWN, error.toContactRequestError(isOnline = true))
        }
    }
}
