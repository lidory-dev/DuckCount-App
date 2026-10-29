package app.lidory.duckcount.groupList.model

/** Why the member picker lists nobody, whatever is searched, see `viewmodel.nobodyToPick`. */
enum class NobodyToPick {
    /**
     * The user has no contact they can put in a group: none at all, or only blocked ones, whom the
     * contacts tab lists all the same, so the picker does not say they have none.
     */
    NO_CONTACTS,

    /**
     * Every contact the user can put in a group is in it already: a blocked one may not be, so the
     * picker does not say that all of them are.
     */
    ALL_MEMBERS,
}
