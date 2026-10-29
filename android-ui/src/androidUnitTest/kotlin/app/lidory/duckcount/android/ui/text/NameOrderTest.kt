package app.lidory.duckcount.android.ui.text

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** How every list of people in the app orders them, see [sortedByName] and [nameOrder]. */
class NameOrderTest {

    private data class Person(val uuid: String, val name: String?)

    private fun List<Person>.sorted(selfUuid: String? = null, locale: Locale = Locale.ENGLISH) =
        sortedByName(selfUuid = selfUuid, uuidOf = { it.uuid }, nameOf = { it.name }, locale = locale)

    @Test
    fun `people are listed by name`() {
        val cleo = Person(uuid = "c", name = "Cleo")
        val alex = Person(uuid = "a", name = "Alex")
        val bruno = Person(uuid = "b", name = "Bruno")

        assertEquals(listOf(alex, bruno, cleo), listOf(cleo, alex, bruno).sorted())
    }

    /** As the user's own card always came first. */
    @Test
    fun `the user comes first whatever their name`() {
        val alex = Person(uuid = "a", name = "Alex")
        val zoe = Person(uuid = "self", name = "Zoé")
        val bruno = Person(uuid = "b", name = "Bruno")

        assertEquals(listOf(zoe, alex, bruno), listOf(bruno, zoe, alex).sorted(selfUuid = "self"))
    }

    @Test
    fun `case and accents do not take a name out of its letter`() {
        val emile = Person(uuid = "e", name = "Émile")
        val alice = Person(uuid = "a", name = "alice")
        val zoe = Person(uuid = "z", name = "Zoé")
        val bruno = Person(uuid = "b", name = "Bruno")

        assertEquals(listOf(alice, bruno, emile, zoe), listOf(zoe, emile, alice, bruno).sorted(locale = Locale.FRENCH))
        assertEquals(listOf(alice, bruno, emile, zoe), listOf(zoe, emile, alice, bruno).sorted(locale = Locale.ENGLISH))
    }

    /** Shown as "Someone else", which is not their name. */
    @Test
    fun `people with no name come last`() {
        val unknown = Person(uuid = "u", name = null)
        val zoe = Person(uuid = "z", name = "Zoé")
        val alex = Person(uuid = "a", name = "Alex")

        assertEquals(listOf(alex, zoe, unknown), listOf(unknown, zoe, alex).sorted())
    }

    @Test
    fun `two people of the same name keep their places`() {
        val first = Person(uuid = "1", name = "Alex")
        val second = Person(uuid = "2", name = "Alex")

        assertEquals(listOf(first, second), listOf(second, first).sorted())
        assertEquals(listOf(first, second), listOf(first, second).sorted())
    }
}
