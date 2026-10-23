package networking

/**
 * The SHA-1 digest of [bytes], which the range API of Have I Been Pwned looks a password up by,
 * see [HaveIBeenPwnedClient.passwordLeakCount]. For nothing else: SHA-1 no longer resists
 * collisions, which is none of the breach check's concern.
 */
internal expect fun sha1(bytes: ByteArray): ByteArray
