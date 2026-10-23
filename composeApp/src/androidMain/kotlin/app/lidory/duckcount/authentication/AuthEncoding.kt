package app.lidory.duckcount.authentication

import org.json.JSONObject
import kotlin.io.encoding.Base64

/** Base64-encodes these bytes using the standard alphabet. */
fun ByteArray.toBase64(): String =
    Base64.Default.encode(this)

/** Decodes a standard-alphabet Base64 string into bytes. */
fun String.decodeBase64ToByteArray(): ByteArray =
    Base64.Default.decode(this)

// JWTs use base64url, conventionally without padding; accept both padded and unpadded.
private val jwtBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * Extracts a claim (default `sub`) from a JWT's payload segment.
 *
 * This only base64url-decodes and JSON-parses the payload, it does NOT verify the signature.
 * That's acceptable here because the token comes straight from our own authenticated server
 * response, so we already trust it. Returns null if the token is malformed or the claim is
 * absent/blank.
 *
 * The DuckCount server carries the user's UUID in the short-lived token's `sub` claim; change
 * the [claim] argument at the call site if that ever moves.
 */
fun String.jwtClaim(claim: String = "sub"): String? {
    val segments = split(".")
    if (segments.size < 2) return null
    return runCatching {
        val payload = jwtBase64.decode(segments[1]).decodeToString()
        JSONObject(payload).optString(claim).takeIf(String::isNotBlank)
    }.getOrNull()
}
