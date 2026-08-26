package networking

import authenticationUtils.getLongLivedToken
import authenticationUtils.getShortLivedToken
import authenticationUtils.setTokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.clearAuthTokens
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Hard cap on a call to our own backend.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

class DuckCountClient : Client() {
    override val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
        install(Logging) {
            logger = httpLogger
            level = LogLevel.ALL
        }
        install(ContentNegotiation) {
            json(
                json = Json {
                    ignoreUnknownKeys = true
                },
            )
        }
        install(Auth) {
            bearer {
                loadTokens {
                    getShortLivedToken()?.let { shortLivedToken ->
                        BearerTokens(
                            accessToken = shortLivedToken,
                            refreshToken = getLongLivedToken(),
                        )
                    }
                }
                refreshTokens {
                    val refreshToken = getLongLivedToken()

                    val accessTokens = client.post(
                        urlString = DuckCountRoutes.Auth.TOKEN,
                    ) {
                        setBody(hashMapOf("long_lived_token" to refreshToken))
                        contentType(ContentType.Application.Json)
                        markAsRefreshTokenRequest()
                    }.body<TokenResponse>()

                    setTokens(
                        accessTokens.shortLivedToken,
                        accessTokens.longLivedToken,
                    )

                    BearerTokens(
                        accessToken = accessTokens.shortLivedToken,
                        refreshToken = accessTokens.longLivedToken,
                    )
                }
            }
        }
    }

    /**
     * Drops the bearer tokens the [Auth] plugin holds in memory.
     *
     * They are read once through `loadTokens` and then cached until a `401` forces a
     * refresh. Clearing the stored tokens is therefore not enough: without this, the
     * requests that follow a log out would still carry the previous session's token,
     * and the next user to log in on this device would be sending somebody else's
     * credentials.
     */
    fun clearAuthenticationCache() {
        httpClient.clearAuthTokens()
    }
}
