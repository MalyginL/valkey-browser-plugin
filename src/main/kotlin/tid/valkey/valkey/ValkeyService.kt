package tid.valkey.valkey

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.ScanParams
import java.net.URI
import java.util.concurrent.locks.ReentrantLock

/**
 * Manages connection to Valkey/Redis and provides read-only operations.
 */
@Service(Service.Level.PROJECT)
class ValkeyService {

    private var jedis: Jedis? = null
    private val lock = ReentrantLock()
    var connection: ValkeyConnection = ValkeyConnection()

    val isConnected: Boolean
        get() = jedis != null && jedis!!.isConnected

    fun connect(): Result<Unit> = runCatching {
        lock.lock()
        try {
            disconnectInternal()
            val scheme = if (connection.ssl) "rediss" else "redis"
            val userInfo = if (connection.password.isNotBlank()) {
                "${connection.username.ifBlank { "default" }}:${connection.password}"
            } else {
                null
            }
            val uri = URI(
                scheme,
                userInfo,
                connection.host,
                connection.port,
                "/${connection.db}",
                "timeout=${connection.connectTimeout}&socket.timeout=${connection.socketTimeout}",
                null
            )
            val client = Jedis(uri)

            client.ping()
            jedis = client
            thisLogger().info("Connected to Valkey at ${connection.host}:${connection.port} (db=${connection.db})")
        } catch (e: Exception) {
            disconnectInternal()
            thisLogger().error("Failed to connect to Valkey", e)
            throw e
        } finally { lock.unlock() }
    }

    fun disconnect() {
        lock.lock()
        try { disconnectInternal() } finally { lock.unlock() }
    }

    private fun disconnectInternal() {
        try {
            jedis?.close()
        } catch (e: Exception) {
            thisLogger().warn("Error closing connection", e)
        }
        jedis = null
    }

    /**
     * Pings the server to verify the connection is alive.
     */
    fun ping() {
        lock.lock()
        try { checkConnected().ping() } finally { lock.unlock() }
    }

    /**
     * Returns all keys matching the pattern (uses SCAN for safety).
     * @param pattern Glob pattern (e.g. "*", "user:*")
     * @param count   Hint for keys per batch (default 100)
     */
    fun scanKeys(pattern: String = "*", count: Int = 100): List<String> {
        lock.lock()
        try {
            val client = checkConnected()
            val keys = mutableListOf<String>()
            val params = ScanParams().count(count).match(pattern)
            var cursor = "0"

            do {
                val scanResult = client.scan(cursor, params)
                keys.addAll(scanResult.result.orEmpty())
                cursor = scanResult.cursor.toString()
            } while (cursor != "0")

            return keys.sorted()
        } finally { lock.unlock() }
    }

    /**
     * Sets a string value for a key with optional TTL in seconds.
     */
    fun setStringWithTTL(key: String, value: String, ttlSeconds: Long?): String {
        lock.lock()
        try {
            val conn = checkConnected()
            if (ttlSeconds != null && ttlSeconds > 0) {
                conn.setex(key, ttlSeconds, value)
            } else {
                conn.set(key, value)
            }
            return "OK"
        } finally { lock.unlock() }
    }

    /**
     * Gets the string value for a key.
     */
    fun getString(key: String): String? {
        lock.lock()
        try { return checkConnected().get(key) } finally { lock.unlock() }
    }

    /**
     * Gets the type of a key (string, list, hash, set, zset, stream, none).
     */
    fun getType(key: String): String {
        lock.lock()
        try { return checkConnected().type(key) } finally { lock.unlock() }
    }

    /**
     * Gets all elements of a list (LRANGE key 0 -1).
     */
    fun getList(key: String): List<String> {
        lock.lock()
        try { return checkConnected().lrange(key, 0, -1) } finally { lock.unlock() }
    }

    /**
     * Gets all field-value pairs of a hash (HGETALL key).
     */
    fun getHash(key: String): Map<String, String> {
        lock.lock()
        try { return checkConnected().hgetAll(key) } finally { lock.unlock() }
    }

    /**
     * Gets all members of a set (SMEMBERS key).
     */
    fun getSet(key: String): Set<String> {
        lock.lock()
        try { return checkConnected().smembers(key) } finally { lock.unlock() }
    }

    /**
     * Gets all members and scores of a sorted set (ZRANGE key 0 -1 WITHSCORES).
     */
    fun getZSet(key: String): List<Pair<String, Double>> {
        lock.lock()
        try {
            val scored = checkConnected().zrangeWithScores(key, 0, -1)
            return scored.map { it.element to it.score }
        } finally { lock.unlock() }
    }

    /**
     * Gets up to 100 entries from a stream (XRANGE key - + COUNT 100).
     * Returns a list of (id, map-of-fields).
     */
    fun getStream(key: String): List<Pair<String, Map<String, String>>> {
        lock.lock()
        try {
            val entries = checkConnected().xrange(key, "-", "+", 100)
            return entries.map { it.id.toString() to it.fields }
        } finally { lock.unlock() }
    }

    /**
     * Deletes a key (DEL key).
     */
    fun deleteKey(key: String): Long {
        lock.lock()
        try { return checkConnected().del(key) } finally { lock.unlock() }
    }

    /**
     * Returns the TTL in seconds for a key (-1 = no expiry, -2 = key doesn't exist).
     */
    fun getTTL(key: String): Long {
        lock.lock()
        try { return checkConnected().ttl(key) } finally { lock.unlock() }
    }

    /**
     * Returns the number of keys in the current database (DBSIZE).
     */
    fun getDbSize(): Long {
        lock.lock()
        try { return checkConnected().dbSize() } finally { lock.unlock() }
    }

    /**
     * Returns the "used_memory_human" field from INFO memory.
     */
    fun getUsedMemoryHuman(): String {
        lock.lock()
        try {
            val client = checkConnected()
            val infoText = client.info("memory")
            return infoText
                .split("\r\n", "\n")
                .find { it.startsWith("used_memory_human:") }
                ?.substringAfter(":")
                ?.trim()
                ?: "?"
        } finally { lock.unlock() }
    }

    private fun checkConnected(): Jedis {
        return jedis ?: throw IllegalStateException("Not connected to Valkey")
    }
}
