package tid.valkey.valkey

/**
 * Connection settings for a Valkey/Redis instance.
 */
data class ValkeyConnection(
    val host: String = "localhost",
    val port: Int = 6379,
    val db: Int = 0,
    val ssl: Boolean = false,
    val username: String = "default",
    val password: String = ""
)
