package push

import util.Error
import util.NetworkError

/** Why this device could not be registered for, or unregistered from, push signals. */
sealed interface PushError : Error {

    /** The server could not be reached, or refused the call. */
    data class Network(val error: NetworkError) : PushError
}
