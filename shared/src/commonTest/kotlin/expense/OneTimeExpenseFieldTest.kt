package expense

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneTimeExpenseFieldTest {

    @Test
    fun `a timestamp the app writes is one`() {
        listOf(
            LocalDateTime(2026, 6, 29, 10, 0),
            LocalDateTime(2026, 6, 29, 10, 0, 5),
            LocalDateTime(2026, 6, 29, 10, 0, 5, 123_000_000),
            LocalDateTime(2026, 6, 29, 10, 0, 5, 123_456_789),
        ).forEach { timestamp ->
            assertTrue(LocalDateTime.Formats.ISO.format(timestamp).isCanonicalTimestamp(), "$timestamp")
        }
    }

    @Test
    fun `a timestamp written any other way is not one even when it parses`() {
        // The first five parse, to a time the app writes differently. Only the app's own
        // spelling keeps a date's expenses together when sorted as text (a signed year sorts
        // away from its date), and the list relies on that to head each date once.
        listOf(
            "+02026-06-29T10:00:00",
            "+002026-06-29T10:00:00",
            "2026-06-29t10:00:00",
            "2026-06-29T10:00",
            "2026-06-29T10:00:05.100",
            "zzz",
            "",
        ).forEach { timestamp ->
            assertFalse(timestamp.isCanonicalTimestamp(), timestamp)
        }
    }

    @Test
    fun `a uuid is only one written lowercase with dashes`() {
        assertTrue("ce39145a-8f96-430e-9395-9cc3940ee374".isCanonicalUuid())

        listOf(
            "CE39145A-8F96-430E-9395-9CC3940EE374",
            "ce39145a8f96430e93959cc3940ee374",
            "{ce39145a-8f96-430e-9395-9cc3940ee374}",
            "date:newest",
            "ce39145a-8f96-430e-9395-9cc3940ee37\uD800",
            "",
        ).forEach { uuid ->
            assertFalse(uuid.isCanonicalUuid(), uuid)
        }
    }
}
