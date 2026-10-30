package app.lidory.duckcount.push

import expense.GroupExpenseUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * How the updates to the expenses of the user's groups one sync brought are told, group by group,
 * see [groupExpenseAnnouncementsOf], and what telling many of a group in one takes down, see
 * [groupExpenseTakedownOf].
 */
class GroupExpenseAnnouncementTest {

    @Test
    fun `three updates of a group are told one each under its heading`() {
        val updates = updates(count = 3)

        val announcement = groupExpenseAnnouncementsOf(updates = updates).single()

        assertEquals(GroupExpenseAnnouncement.OneEach(updates = updates), announcement)
        assertTrue((announcement as GroupExpenseAnnouncement.OneEach).hasHeading)
    }

    @Test
    fun `a single update has no heading`() {
        val announcement = groupExpenseAnnouncementsOf(updates = updates(count = 1)).single()

        assertFalse((announcement as GroupExpenseAnnouncement.OneEach).hasHeading)
    }

    @Test
    fun `four updates of a group are told in one naming it`() {
        val announcement = groupExpenseAnnouncementsOf(updates = updates(count = 4)).single()

        assertEquals(GroupExpenseAnnouncement.InOne(updates = updates(count = 4)), announcement)
        assertEquals(4, (announcement as GroupExpenseAnnouncement.InOne).count)
        assertEquals("Ski trip", announcement.groupTitle)
    }

    /** A phone back online after a weekend of a group's expenses entered while it was off. */
    @Test
    fun `many updates of a group are told in one`() {
        val announcement = groupExpenseAnnouncementsOf(updates = updates(count = 62)).single()

        assertEquals(62, (announcement as GroupExpenseAnnouncement.InOne).count)
    }

    /** Three of one group and one of another are four rows to read, not a heap to count. */
    @Test
    fun `updates are counted in each group on its own`() {
        val ski = updates(count = 3)
        val flat = updates(count = 1, groupUuid = "flat", groupTitle = "Flatshare")

        assertEquals(
            listOf(GroupExpenseAnnouncement.OneEach(updates = ski), GroupExpenseAnnouncement.OneEach(updates = flat)),
            groupExpenseAnnouncementsOf(updates = ski + flat),
        )
    }

    @Test
    fun `four of one group and three of another give one catch-up and three named`() {
        val ski = updates(count = 4)
        val flat = updates(count = 3, groupUuid = "flat", groupTitle = "Flatshare")

        assertEquals(
            listOf(GroupExpenseAnnouncement.InOne(updates = ski), GroupExpenseAnnouncement.OneEach(updates = flat)),
            groupExpenseAnnouncementsOf(updates = ski + flat),
        )
    }

    /** Nothing ever counts the expenses of several groups together. */
    @Test
    fun `several groups of many updates are told in one each`() {
        val ski = updates(count = 4)
        val flat = updates(count = 5, groupUuid = "flat", groupTitle = "Flatshare")
        val trip = updates(count = 12, groupUuid = "trip", groupTitle = "Road trip")

        val announcements = groupExpenseAnnouncementsOf(updates = ski + flat + trip)

        assertEquals(
            listOf(
                GroupExpenseAnnouncement.InOne(updates = ski),
                GroupExpenseAnnouncement.InOne(updates = flat),
                GroupExpenseAnnouncement.InOne(updates = trip),
            ),
            announcements,
        )
        assertEquals(listOf("ski-trip", "flat", "trip"), announcements.map { it.groupUuid })
    }

    @Test
    fun `each group of several updates has a heading of its own`() {
        val ski = updates(count = 2)
        val flat = updates(count = 3, groupUuid = "flat", groupTitle = "Flatshare")
        val trip = updates(count = 1, groupUuid = "trip", groupTitle = "Road trip")

        val announcements = groupExpenseAnnouncementsOf(updates = ski + flat + trip)
            .map { it as GroupExpenseAnnouncement.OneEach }

        assertEquals(listOf(true, true, false), announcements.map { it.hasHeading })
        assertEquals(listOf(2, 3, 1), announcements.map { it.updates.size })
        assertEquals(listOf("Ski trip", "Flatshare", "Road trip"), announcements.map { it.groupTitle })
    }

    /** The updates of a group arriving among another's are still all of their own group. */
    @Test
    fun `updates of a group interleaved with another's are told together`() {
        val ski = updates(count = 4)
        val flat = updates(count = 2, groupUuid = "flat", groupTitle = "Flatshare")

        val interleaved = listOf(ski[0], flat[0], ski[1], ski[2], flat[1], ski[3])

        assertEquals(
            listOf(GroupExpenseAnnouncement.InOne(updates = ski), GroupExpenseAnnouncement.OneEach(updates = flat)),
            groupExpenseAnnouncementsOf(updates = interleaved),
        )
    }

    /** Titles are whatever each group's owner chose, so two of them can read the same. */
    @Test
    fun `two groups of one title stay apart`() {
        val ski = updates(count = 2)
        val otherSki = updates(count = 2, groupUuid = "other-ski-trip")

        assertEquals(
            listOf(
                GroupExpenseAnnouncement.OneEach(updates = ski),
                GroupExpenseAnnouncement.OneEach(updates = otherSki),
            ),
            groupExpenseAnnouncementsOf(updates = ski + otherSki),
        )
    }

    /** One told on its own earlier says what the expense was then, which it may no longer be. */
    @Test
    fun `telling many in one takes down what was told of each of them`() {
        val updates = updates(count = 5)

        val takedown = groupExpenseTakedownOf(
            announcement = GroupExpenseAnnouncement.InOne(updates = updates),
            takeRecorded = takeFrom(recorded = mutableMapOf(SKI_TAG to setOf(idOf(updates[0]), idOf(updates[3])))),
        )

        assertEquals(SKI_TAG, takedown.tag)
        assertEquals(updates.mapTo(mutableSetOf()) { idOf(it) }, takedown.ids)
    }

    @Test
    fun `the heading goes with the last expense of its group recorded under it`() {
        val updates = updates(count = 5)

        val takedown = groupExpenseTakedownOf(
            announcement = GroupExpenseAnnouncement.InOne(updates = updates),
            takeRecorded = takeFrom(recorded = mutableMapOf(SKI_TAG to setOf(idOf(updates[0]), idOf(updates[3])))),
        )

        assertEquals(emptySet(), takedown.left)
        assertTrue(takedown.takesDownHeading)
    }

    /** Taking a heading down takes down what is under it, which nothing has replaced. */
    @Test
    fun `the heading stays over an expense of its group told before and not counted`() {
        val updates = updates(count = 5)
        val earlier = idOf(updates(count = 6).last())

        val takedown = groupExpenseTakedownOf(
            announcement = GroupExpenseAnnouncement.InOne(updates = updates),
            takeRecorded = takeFrom(recorded = mutableMapOf(SKI_TAG to setOf(idOf(updates[0]), earlier))),
        )

        assertFalse(earlier in takedown.ids)
        assertEquals(setOf(earlier), takedown.left)
        assertFalse(takedown.takesDownHeading)
    }

    /** Each group's expenses told one each go up and are recorded apart, whatever the groups are called. */
    @Test
    fun `two groups of one title are recorded under tags of their own`() {
        val ski = groupExpenseAnnouncementsOf(updates = updates(count = 2)).single()
        val otherSki = groupExpenseAnnouncementsOf(updates = updates(count = 2, groupUuid = "other-ski-trip")).single()

        assertNotEquals(groupExpenseTagOf(groupUuid = ski.groupUuid), groupExpenseTagOf(groupUuid = otherSki.groupUuid))
    }

    /** Another group's expenses told one each are none of what a catch-up counts, nor under its heading. */
    @Test
    fun `a catch-up takes down nothing of another group`() {
        val ski = updates(count = 5)
        val flat = updates(count = 2, groupUuid = "flat", groupTitle = "Flatshare")

        val flatTag = groupExpenseTagOf(groupUuid = "flat")
        val flatIds = flat.map { idOf(it) }.toSet()

        val recorded = mutableMapOf(SKI_TAG to setOf(idOf(ski[1])), flatTag to flatIds)

        val takedown = groupExpenseTakedownOf(
            announcement = GroupExpenseAnnouncement.InOne(updates = ski),
            takeRecorded = takeFrom(recorded = recorded),
        )

        assertTrue(flatIds.none { it in takedown.ids || it in takedown.left })
        assertTrue(takedown.takesDownHeading)
        assertEquals(mapOf(flatTag to flatIds), recorded)
    }

    /** A group called as another is still another group, whose expenses told one each are not its own. */
    @Test
    fun `a catch-up takes down nothing of another group of its title`() {
        val ski = updates(count = 5)
        val otherSki = updates(count = 2, groupUuid = "other-ski-trip")

        val otherSkiTag = groupExpenseTagOf(groupUuid = "other-ski-trip")

        val recorded = mutableMapOf(otherSkiTag to otherSki.map { idOf(it) }.toSet())

        val takedown = groupExpenseTakedownOf(
            announcement = GroupExpenseAnnouncement.InOne(updates = ski),
            takeRecorded = takeFrom(recorded = recorded),
        )

        assertNotEquals(otherSkiTag, takedown.tag)
        assertTrue(takedown.takesDownHeading)
        assertEquals(setOf(otherSkiTag), recorded.keys)
    }

    private fun idOf(update: GroupExpenseUpdate): Int = update.expenseUuid.hashCode()

    /**
     * Hands over the ids [recorded] holds under a tag, forgetting them, as [PostedNotifications.takeAll]
     * does, so that what is left in [recorded] is what was not read.
     */
    private fun takeFrom(recorded: MutableMap<String, Set<Int>>): (String) -> Set<Int> =
        { tag -> recorded.remove(tag).orEmpty() }

    private fun updates(
        count: Int,
        groupUuid: String = "ski-trip",
        groupTitle: String = "Ski trip",
    ): List<GroupExpenseUpdate> = List(count) { index ->
        GroupExpenseUpdate(
            groupUuid = groupUuid,
            groupTitle = groupTitle,
            expenseUuid = "$groupUuid-$index",
            title = "Expense $index",
            isSettlement = false,
            amount = "12.50",
            currencySymbol = "€",
            authorFullName = "Alice",
            kind = GroupExpenseUpdate.Kind.ADDED,
        )
    }

    private companion object {
        /** The tag the expenses of the group [updates] makes by default are recorded under. */
        val SKI_TAG = groupExpenseTagOf(groupUuid = "ski-trip")
    }
}
