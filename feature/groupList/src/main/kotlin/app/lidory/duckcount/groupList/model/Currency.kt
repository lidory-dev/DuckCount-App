package app.lidory.duckcount.groupList.model

/**
 * A currency of the catalogue the app ships with, under the UUID the catalogue gives it: what a
 * group is sealed with, and what every member looks it up by.
 */
data class Currency(
    val uuid: String,
    val name: String,
    val symbol: String,
    val hasSubunits: Boolean,
)
