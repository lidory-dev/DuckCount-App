package networking

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.serialization.SerializationException
import util.NetworkError
import util.Result
import kotlin.coroutines.cancellation.CancellationException

abstract class Client {
    abstract val httpClient: HttpClient

    suspend inline fun <reified T> get(
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        queryParams: Map<String, String> = hashMapOf(),
        headers: Map<String, String> = emptyMap(),
    ): Result<T, NetworkError> {
        return executeMethod(
            method = Method.GET,
            route = route,
            pathParams = pathParams,
            queryParams = queryParams,
            headers = headers,
            body = Unit,
        )
    }

    suspend inline fun <reified T, D> post(
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: D,
        contentType: ContentType = ContentType.Application.Json,
    ): Result<T, NetworkError> {
        return executeMethod(
            method = Method.POST,
            route = route,
            pathParams = pathParams,
            headers = headers,
            body = body,
            contentType = contentType,
        )
    }

    suspend inline fun <reified T, D> put(
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: D,
        contentType: ContentType = ContentType.Application.Json,
    ): Result<T, NetworkError> {
        return executeMethod(
            method = Method.PUT,
            route = route,
            pathParams = pathParams,
            headers = headers,
            body = body,
            contentType = contentType,
        )
    }

    suspend inline fun <reified T, D> patch(
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: D,
        contentType: ContentType = ContentType.Application.Json,
    ): Result<T, NetworkError> {
        return executeMethod(
            method = Method.PATCH,
            route = route,
            pathParams = pathParams,
            headers = headers,
            body = body,
            contentType = contentType,
        )
    }

    suspend inline fun <reified T> delete(
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Result<T, NetworkError> {
        return executeMethod(
            method = Method.DELETE,
            route = route,
            pathParams = pathParams,
            headers = headers,
            body = Unit,
        )
    }

    suspend inline fun <reified T, reified D> executeMethod(
        method: Method,
        route: String,
        pathParams: Map<String, String> = emptyMap(),
        queryParams: Map<String, String> = hashMapOf(),
        headers: Map<String, String> = emptyMap(),
        body: D? = null,
        contentType: ContentType? = null,
    ): Result<T, NetworkError> {
        val routeWithPathParameters = pathParams.entries.fold(initial = route) { accumulator, (key, value) ->
            accumulator.replace(oldValue = ":$key", newValue = value)
        }

        return execute {
            when (method) {
                Method.GET -> {
                    httpClient.get(
                        urlString = routeWithPathParameters,
                    ) {
                        queryParams.onEach { param ->
                            parameter(key = param.key, value = param.value)
                        }
                        headers.onEach { (name, value) ->
                            header(key = name, value = value)
                        }
                    }
                }

                Method.POST -> {
                    httpClient.post(
                        urlString = routeWithPathParameters,
                    ) {
                        setBody(body)
                        contentType?.let {
                            contentType(it)
                        }
                        headers.onEach { (name, value) ->
                            header(key = name, value = value)
                        }
                    }
                }

                Method.PUT -> {
                    httpClient.put(
                        urlString = routeWithPathParameters,
                    ) {
                        setBody(body)
                        contentType?.let {
                            contentType(it)
                        }
                        headers.onEach { (name, value) ->
                            header(key = name, value = value)
                        }
                    }
                }

                Method.PATCH -> {
                    httpClient.patch(
                        urlString = routeWithPathParameters,
                    ) {
                        setBody(body)
                        contentType?.let {
                            contentType(it)
                        }
                        headers.onEach { (name, value) ->
                            header(key = name, value = value)
                        }
                    }
                }

                Method.DELETE -> {
                    httpClient.delete(
                        urlString = routeWithPathParameters,
                    ) {
                        headers.onEach { (name, value) ->
                            header(key = name, value = value)
                        }
                    }
                }
            }
        }
    }

    suspend inline fun <reified T> execute(
        executor: () -> HttpResponse,
    ): Result<T, NetworkError> {
        val response = try {
            executor()
        } catch (cancellation: CancellationException) {
            // The timeout plugin gives up by cancelling the request's job with the timeout as the
            // cancellation cause, so an expired timeout reaches us wrapped like this. Anything
            // else is our own caller going away, which has to keep propagating.
            if (cancellation.isRequestTimeout()) return Result.Error(NetworkError.REQUEST_TIMEOUT)

            throw cancellation
        } catch (_: HttpRequestTimeoutException) {
            return Result.Error(NetworkError.REQUEST_TIMEOUT)
        } catch (_: UnresolvedAddressException) {
            return Result.Error(NetworkError.NO_INTERNET)
        } catch (_: SerializationException) {
            return Result.Error(NetworkError.SERIALIZATION)
        } catch (_: Exception) {
            // Connection refused/reset, socket timeout, TLS failure, etc., the host resolved, but
            // we couldn't reach it. Surface as a handled error, never crash the caller.
            return Result.Error(NetworkError.UNREACHABLE)
        }

        return when (response.status.value) {
            in 200..299 -> {
                try {
                    Result.Success(response.body<T>())
                } catch (cancellation: CancellationException) {
                    // The timeout stays armed until the body has been consumed, so it can just as
                    // well expire here, part-way through reading the response.
                    if (!cancellation.isRequestTimeout()) throw cancellation

                    Result.Error(NetworkError.REQUEST_TIMEOUT)
                } catch (_: HttpRequestTimeoutException) {
                    Result.Error(NetworkError.REQUEST_TIMEOUT)
                } catch (_: SerializationException) {
                    Result.Error(NetworkError.SERIALIZATION)
                } catch (_: Exception) {
                    Result.Error(NetworkError.UNKNOWN)
                }
            }

            400 -> Result.Error(NetworkError.BAD_REQUEST)
            401 -> Result.Error(NetworkError.UNAUTHORIZED)
            404 -> Result.Error(NetworkError.NOT_FOUND)
            409 -> Result.Error(NetworkError.CONFLICT)
            408 -> Result.Error(NetworkError.REQUEST_TIMEOUT)
            413 -> Result.Error(NetworkError.PAYLOAD_TOO_LARGE)
            422 -> Result.Error(NetworkError.UNPROCESSABLE_ENTITY)
            429 -> Result.Error(NetworkError.TOO_MANY_REQUESTS)
            in 500..599 -> Result.Error(NetworkError.SERVER_ERROR)
            else -> Result.Error(NetworkError.UNKNOWN)
        }
    }

    enum class Method {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE,
    }
}

/**
 * Whether this was raised by a client-side request timeout, either directly or as the cause of the
 * cancellation the timeout plugin uses to abort the request.
 *
 * Public because [Client.execute] is `inline`.
 */
fun Throwable.isRequestTimeout(): Boolean = unwrapCancellationException() is HttpRequestTimeoutException
