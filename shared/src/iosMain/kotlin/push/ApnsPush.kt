package push

import util.Result

/** The transport an iOS client's endpoint speaks, as the server names it: an APNs device token. */
const val APNS_TRANSPORT = "apns"

/**
 * Who may wake the user, kept where the app's notification service extension reads it, see
 * [WakeList]: the keychain group the two share, readable once the device has been unlocked since it
 * started, as Android's vault entries written without the unlocked-device requirement are.
 *
 * Implemented by the app, in Swift: the extension reads the same item with no Kotlin in it, the few
 * megabytes iOS gives it leaving no room to load this framework, so the format lives in one place,
 * the `PushSignals` package.
 */
interface SharedWakeList {
    fun isContact(userUuid: String): Boolean

    fun isRequested(userUuid: String): Boolean

    fun isCoMember(userUuid: String): Boolean

    fun isBlocked(userUuid: String): Boolean

    /** Replaces the whole list, written before this returns. */
    fun replace(contacts: Set<String>, requested: Set<String>, coMembers: Set<String>, blocked: Set<String>)

    /** Empties it, for good by the time this returns. */
    fun clear()
}

/** [WakeListStore] on iOS: the list the app shares with its extension, see [SharedWakeList]. */
internal class SharedWakeListStore(private val shared: SharedWakeList) : WakeListStore {
    override fun isContact(userUuid: String): Boolean = shared.isContact(userUuid = userUuid)

    override fun isRequested(userUuid: String): Boolean = shared.isRequested(userUuid = userUuid)

    override fun isCoMember(userUuid: String): Boolean = shared.isCoMember(userUuid = userUuid)

    override fun isBlocked(userUuid: String): Boolean = shared.isBlocked(userUuid = userUuid)

    override suspend fun replace(
        contacts: Set<String>,
        requested: Set<String>,
        coMembers: Set<String>,
        blocked: Set<String>,
    ) = shared.replace(contacts = contacts, requested = requested, coMembers = coMembers, blocked = blocked)

    override suspend fun clear() = shared.clear()
}

/**
 * The iOS half of push: an APNs device token, which iOS hands the app once it registers, and the
 * keys the server encrypts each signal to, which the app makes and keeps for its extension, see the
 * `PushSignals` package. Apple carries the signal and reads nothing of it, as a Web Push service
 * reads nothing of Android's.
 */
class ApnsPushRegistrar(
    private val pushRepository: PushRepository,
) : PushRegistrar {

    /**
     * Tells the server to wake this device at [deviceToken], in lowercase hex, encrypting to the
     * public key [p256dh] and the secret [auth], both base64url without padding. Whether the server
     * took it: one that did not, offline or refusing APNs, is asked again at the next launch, iOS
     * handing the token over each time the app registers.
     *
     * Nothing is sent when it is what this device registered last, see [PushRepository.register].
     */
    suspend fun register(deviceToken: String, p256dh: String, auth: String): Boolean =
        when (
            val registration = pushRepository.register(
                transport = APNS_TRANSPORT,
                endpoint = deviceToken,
                p256dh = p256dh,
                auth = auth,
            )
        ) {
            is Result.Success -> true

            is Result.Error -> {
                println("ApnsPushRegistrar: could not register: ${registration.error}")
                false
            }
        }

    /**
     * Tells the server to forget this device, before the session's tokens go. The device's own side,
     * its token and its keys, the app drops as it starts over, see `AppSession`.
     */
    override suspend fun tearDown() {
        (pushRepository.unregister() as? Result.Error)?.let {
            println("ApnsPushRegistrar: could not unregister: ${it.error}")
        }
    }
}
