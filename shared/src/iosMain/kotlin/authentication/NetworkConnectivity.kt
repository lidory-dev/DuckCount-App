package authentication

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Reports whether the device has a usable network, to tell "offline" from "the server is down"
 * once a request could not connect, as Android's `NetworkConnectivity` does: Ktor's Darwin engine
 * reports both as a connection that failed, see [toAuthenticationError].
 *
 * Watches the network for as long as the process lives, so that the answer is at hand when a
 * request fails. Until the first update, which comes as soon as the monitor starts, it reads as
 * online, which leaves a failure the unreachable server it says it is.
 */
@OptIn(ExperimentalForeignApi::class)
class NetworkConnectivity {

    @Volatile
    private var isSatisfied = true

    // Kept for the monitor to live as long as this does.
    private val monitor = nw_path_monitor_create().also { monitor ->
        nw_path_monitor_set_update_handler(monitor) { path ->
            isSatisfied = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("app.lidory.duckcount.connectivity", null))
        nw_path_monitor_start(monitor)
    }

    /** True if the device currently has a network it can reach the internet through. */
    fun isOnline(): Boolean = isSatisfied
}
