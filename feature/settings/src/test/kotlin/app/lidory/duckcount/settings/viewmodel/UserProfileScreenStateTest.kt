package app.lidory.duckcount.settings.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SAVED = "Ada Lovelace"

/** What the profile form lets through, see [UserProfileScreenState.Content.canSave]. */
class UserProfileScreenStateTest {

    private val unchanged = UserProfileScreenState.Content(
        authenticatedUserUuid = "a6f0c1d2-7b3e-4c5a-9d8f-0e1b2c3d4e5f",
        fullName = SAVED,
        draftFullName = SAVED,
        isSaving = false,
        error = null,
    )

    @Test
    fun `the saved name is nothing to save`() {
        assertFalse(unchanged.canSave)
    }

    /** The profile is saved without the whitespace around the name: it would be the same one. */
    @Test
    fun `a name changed only by the whitespace around it is nothing to save`() {
        val state = unchanged.copy(draftFullName = " $SAVED ")

        assertEquals(SAVED, state.trimmedDraftFullName)
        assertFalse(state.canSave)
    }

    @Test
    fun `a new name typed with whitespace around it can be saved`() {
        val state = unchanged.copy(draftFullName = "  Ada King ")

        assertEquals("Ada King", state.trimmedDraftFullName)
        assertTrue(state.canSave)
    }

    @Test
    fun `a blank name cannot be saved`() {
        assertFalse(unchanged.copy(draftFullName = "").canSave)
        assertFalse(unchanged.copy(draftFullName = "   ").canSave)
    }

    @Test
    fun `a save under way cannot be started again`() {
        assertFalse(unchanged.copy(draftFullName = "Ada King", isSaving = true).canSave)
    }
}
