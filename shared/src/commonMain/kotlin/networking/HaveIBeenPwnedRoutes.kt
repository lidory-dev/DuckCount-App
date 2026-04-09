package networking

object HaveIBeenPwnedRoutes {
    private const val BASE_URL = "https://api.pwnedpasswords.com"

    object Password {
        const val CHECK = "$BASE_URL/range/:sha1FiveFirstCharacters"
    }
}
