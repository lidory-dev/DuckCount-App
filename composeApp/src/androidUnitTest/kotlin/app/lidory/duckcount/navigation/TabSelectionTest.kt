package app.lidory.duckcount.navigation

import androidx.navigation3.runtime.NavKey
import app.lidory.duckcount.navigation.key.ContactListScreenKey
import app.lidory.duckcount.navigation.key.ExpenseListScreenKey
import app.lidory.duckcount.navigation.key.GroupListScreenKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the dashboard's back stack holds as its tabs are picked, the expenses tab coming first. */
class TabSelectionTest {

    private val backStack = mutableListOf<NavKey>(ExpenseListScreenKey)

    @Test
    fun `another tab goes on top of the first one`() {
        backStack.selectTab(GroupListScreenKey)

        assertEquals(listOf<NavKey>(ExpenseListScreenKey, GroupListScreenKey), backStack)
    }

    /** Back from the contacts then leads to the expenses, not to the groups picked before them. */
    @Test
    fun `a tab takes the place of the one it is picked from`() {
        backStack.selectTab(GroupListScreenKey)
        backStack.selectTab(ContactListScreenKey)

        assertEquals(listOf<NavKey>(ExpenseListScreenKey, ContactListScreenKey), backStack)
    }

    @Test
    fun `the first tab leaves itself alone`() {
        backStack.selectTab(ContactListScreenKey)
        backStack.selectTab(ExpenseListScreenKey)

        assertEquals(listOf<NavKey>(ExpenseListScreenKey), backStack)
    }

    @Test
    fun `picking the tab already shown changes nothing`() {
        backStack.selectTab(GroupListScreenKey)
        backStack.selectTab(GroupListScreenKey)

        assertEquals(listOf<NavKey>(ExpenseListScreenKey, GroupListScreenKey), backStack)
    }
}
