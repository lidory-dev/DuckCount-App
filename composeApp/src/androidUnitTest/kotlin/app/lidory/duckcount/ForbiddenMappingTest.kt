package app.lidory.duckcount

import app.lidory.duckcount.contactList.toAddContactError
import app.lidory.duckcount.contactList.toAnswerContactRequestError
import app.lidory.duckcount.contactList.toContactVerification
import app.lidory.duckcount.contactList.toRemoveContactError
import app.lidory.duckcount.expenseList.toExpenseWriteError
import app.lidory.duckcount.groupList.toContactRequestError
import app.lidory.duckcount.groupList.toGroupEditError
import app.lidory.duckcount.settings.toAccountDeletionError
import app.lidory.duckcount.settings.toPasswordChangeError
import app.lidory.duckcount.settings.toProfileSaveError
import app.lidory.duckcount.settings.toReserveUsernameError
import contact.SendContactRequestError
import mls.MlsIdentityError
import profile.ProfileError
import util.NetworkError
import vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import account.AccountDeletionError as SharedAccountDeletionError
import account.PasswordChangeError as SharedPasswordChangeError
import contact.AnswerContactRequestError as SharedAnswerContactRequestError
import contact.ContactVerification as SharedContactVerification
import contact.ContactVerificationError as SharedContactVerificationError
import contact.RemoveContactError as SharedRemoveContactError
import group.GroupEditError as SharedGroupEditError

/**
 * What a `403` tells the user off the one route that gives it a meaning, the start of a registration:
 * what any answer the client does not know tells them, never what a body refused does.
 */
class ForbiddenMappingTest {

    private fun <T> assertReadsAsUnknown(mapped: (NetworkError) -> T) {
        assertEquals(mapped(NetworkError.UNKNOWN), mapped(NetworkError.FORBIDDEN))
    }

    @Test
    fun `reserving a username`() = assertReadsAsUnknown { it.toReserveUsernameError(isOnline = true) }

    @Test
    fun `saving a profile`() = assertReadsAsUnknown { ProfileError.Network(it).toProfileSaveError(isOnline = true) }

    @Test
    fun `deleting the account`() =
        assertReadsAsUnknown { SharedAccountDeletionError.Network(it).toAccountDeletionError(isOnline = true) }

    @Test
    fun `changing the password`() =
        assertReadsAsUnknown { SharedPasswordChangeError.Network(it).toPasswordChangeError(isOnline = true) }

    @Test
    fun `writing an expense`() = assertReadsAsUnknown { it.toExpenseWriteError(isOnline = true) }

    @Test
    fun `adding a contact`() =
        assertReadsAsUnknown { SendContactRequestError.Network(it).toAddContactError(isOnline = true) }

    @Test
    fun `answering a contact request`() =
        assertReadsAsUnknown { SharedAnswerContactRequestError.Network(it).toAnswerContactRequestError(isOnline = true) }

    @Test
    fun `removing a contact`() =
        assertReadsAsUnknown { SharedRemoveContactError.Network(it).toRemoveContactError(isOnline = true) }

    @Test
    fun `verifying a contact`() = assertReadsAsUnknown {
        SharedContactVerification.Unavailable(
            error = SharedContactVerificationError.Identity(MlsIdentityError.Vault(VaultError.Network(it))),
        ).toContactVerification(isOnline = true)
    }

    @Test
    fun `changing a group`() =
        assertReadsAsUnknown { SharedGroupEditError.Network(it).toGroupEditError(isOnline = true) }

    @Test
    fun `asking a group member to be a contact`() =
        assertReadsAsUnknown { SendContactRequestError.Network(it).toContactRequestError(isOnline = true) }
}
