package app.lidory.duckcount.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Reports real device connectivity, used to disambiguate a transport-level
 * [util.NetworkError.UNREACHABLE]. Airplane mode can surface as "couldn't connect" rather than
 * "unresolved address", so callers resolve UNREACHABLE against this check to tell "offline"
 * apart from "server is down".
 */
class NetworkConnectivity(private val context: Context) {

    /** True if the device currently has a network with internet capability. */
    fun isOnline(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)

        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
