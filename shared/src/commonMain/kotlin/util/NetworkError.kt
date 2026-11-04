package util

enum class NetworkError : Error {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    REQUEST_TIMEOUT,
    PAYLOAD_TOO_LARGE,
    UNPROCESSABLE_ENTITY,
    TOO_MANY_REQUESTS,
    SERVER_ERROR,
    NO_INTERNET,
    UNREACHABLE,
    SERIALIZATION,
    UNKNOWN;
}

class NetworkException(
    val error: NetworkError,
    cause: Throwable? = null,
) : Exception(error.name, cause)
