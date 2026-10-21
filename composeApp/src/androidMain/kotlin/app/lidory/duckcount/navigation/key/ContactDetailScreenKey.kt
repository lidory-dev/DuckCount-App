package app.lidory.duckcount.navigation.key

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * [instanceId] makes each opening of the contact an entry of its own. Entries with equal keys
 * share their ViewModel, and a configuration change that cuts short the closing of a removed
 * contact's detail keeps its ViewModel: opening the same person again once they are a contact
 * anew would hand it that ViewModel, and it would close at once.
 */
@Serializable
data class ContactDetailScreenKey(
    val userUuid: String,
    val instanceId: String,
) : NavKey {
    constructor(userUuid: String) : this(userUuid, instanceId = Uuid.random().toString())
}
