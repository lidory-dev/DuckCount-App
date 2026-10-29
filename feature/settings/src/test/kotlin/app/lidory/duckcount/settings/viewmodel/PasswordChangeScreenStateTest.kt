package app.lidory.duckcount.settings.viewmodel

import app.lidory.duckcount.settings.model.NewPasswordInputError
import app.lidory.duckcount.settings.model.PasswordChangeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CURRENT = "correct horse battery staple"
private const val NEW = "tremble glacier marmalade oboe"

/** What the password change form points out and lets through, see [PasswordChangeScreenState]. */
class PasswordChangeScreenStateTest {

    private val empty = PasswordChangeScreenState(minimumLength = 15)

    private val filled = empty
        .withCurrentPassword(CURRENT)
        .withNewPassword(NEW)
        .withNewPasswordConfirmation(NEW)

    @Test
    fun `nothing typed yet is no mistake and nothing to confirm`() {
        assertNull(empty.newPasswordInputError)
        assertFalse(empty.isConfirmationMismatched)
        assertFalse(empty.canConfirm)
    }

    @Test
    fun `a filled in form can be confirmed`() {
        assertNull(filled.newPasswordInputError)
        assertFalse(filled.isConfirmationMismatched)
        assertTrue(filled.canConfirm)
    }

    @Test
    fun `a new password shorter than the minimum is too short`() {
        val state = filled.withNewPassword("too short").withNewPasswordConfirmation("too short")

        assertEquals(NewPasswordInputError.TOO_SHORT, state.newPasswordInputError)
        assertFalse(state.canConfirm)
    }

    /** As long as the minimum or longer: no form would log in with it, registration refusing it too. */
    @Test
    fun `a new password of nothing but spaces is refused`() {
        val blank = " ".repeat(20)
        val state = filled.withNewPassword(blank).withNewPasswordConfirmation(blank)

        assertEquals(NewPasswordInputError.BLANK, state.newPasswordInputError)
        assertFalse(state.canConfirm)
    }

    /** A password is taken as typed, never trimmed: the spaces around it are part of it. */
    @Test
    fun `a new password with spaces around it can be set`() {
        val spaced = " $NEW "
        val state = filled.withNewPassword(spaced).withNewPasswordConfirmation(spaced)

        assertNull(state.newPasswordInputError)
        assertTrue(state.canConfirm)
    }

    /** No account holds one, so it is no current password to send. */
    @Test
    fun `a current password of nothing but spaces cannot be sent`() {
        assertFalse(filled.withCurrentPassword(" ".repeat(20)).canConfirm)
    }

    @Test
    fun `a new password that is the current one must differ`() {
        val state = filled.withNewPassword(CURRENT).withNewPasswordConfirmation(CURRENT)

        assertEquals(NewPasswordInputError.SAME_AS_CURRENT, state.newPasswordInputError)
        assertFalse(state.canConfirm)
    }

    @Test
    fun `a confirmation that does not match is pointed out once typed`() {
        val state = filled.withNewPasswordConfirmation("$NEW!")

        assertTrue(state.isConfirmationMismatched)
        assertFalse(state.canConfirm)
    }

    @Test
    fun `a change under way cannot be confirmed again`() {
        assertFalse(filled.copy(isChanging = true).canConfirm)
    }

    /** Sending it again would fail the same way. */
    @Test
    fun `a failure shown on a field keeps the form from being confirmed`() {
        assertFalse(filled.copy(error = PasswordChangeError.WRONG_PASSWORD).canConfirm)
        assertFalse(filled.copy(error = PasswordChangeError.LEAKED).canConfirm)
    }

    @Test
    fun `a failure the snackbar shows leaves the form to be confirmed again`() {
        assertTrue(filled.copy(error = PasswordChangeError.TIMEOUT).canConfirm)
    }

    @Test
    fun `editing the current password clears its failure and not the new one's`() {
        assertNull(filled.copy(error = PasswordChangeError.WRONG_PASSWORD).withCurrentPassword("$CURRENT.").error)
        assertEquals(
            PasswordChangeError.LEAKED,
            filled.copy(error = PasswordChangeError.LEAKED).withCurrentPassword("$CURRENT.").error,
        )
    }

    @Test
    fun `editing the new password clears its failure and not the current one's`() {
        assertNull(filled.copy(error = PasswordChangeError.LEAKED).withNewPassword("$NEW.").error)
        assertEquals(
            PasswordChangeError.WRONG_PASSWORD,
            filled.copy(error = PasswordChangeError.WRONG_PASSWORD).withNewPassword("$NEW.").error,
        )
    }

    @Test
    fun `editing the confirmation keeps the failures shown on the other fields`() {
        PasswordChangeError.entries.filter { it.isFieldError }.forEach { error ->
            assertEquals(error, filled.copy(error = error).withNewPasswordConfirmation("$NEW.").error)
        }
    }

    @Test
    fun `any edit clears a failure the snackbar shows`() {
        val failed = filled.copy(error = PasswordChangeError.UNCONFIRMED)

        assertNull(failed.withCurrentPassword(CURRENT).error)
        assertNull(failed.withNewPassword(NEW).error)
        assertNull(failed.withNewPasswordConfirmation(NEW).error)
    }
}
