package app.lidory.duckcount

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform