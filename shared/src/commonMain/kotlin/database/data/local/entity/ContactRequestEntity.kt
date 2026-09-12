package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact request waiting for the user, cached so the screens that count them can
 * read them without a round trip.
 *
 * [ciphertext] is kept even though nothing can unseal it yet: the row is then a
 * faithful copy of the server's, and the screen that accepts requests will need it.
 */
@Entity
data class ContactRequestEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val senderUserUuid: String,
    val ciphertext: String,
    val createdAt: String,
)
