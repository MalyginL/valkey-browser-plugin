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
    private companion object {
        const val SOCKET_TIMEOUT_MS = 10000  // 10s read timeout
    }

    var connection: ValkeyConnection = ValkeyConnection()

    /** Called by the panel to receive log messages in the in-panel log area */
    var logCallback: ((level: String, msg: String) -> Unit)? = null

    private fun logToCallback(level: String, msg: String) {
        logCallback?.invoke(level, msg)
    }

    val isConnected: Boolean
        get() = jedis != null && jedis!!.isConnected

    fun connect(): Result<Unit> = runCatching {
        lock.lock()
        try {
            disconnectInternal()
            val scheme = if (connection.ssl) "rediss" else "redis"
            val authUser = if (connection.password.isNotBlank()) connection.username else "none"
            val connectingMsg = "Connecting to $scheme://${connection.host}:${connection.port}/${connection.db} (ssl=${connection.ssl}, user=$authUser, timeout=${SOCKET_TIMEOUT_MS}ms)"
            thisLogger().info(connectingMsg)
            logToCallback("INFO", connectingMsg)

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
                "timeout=${SOCKET_TIMEOUT_MS}",
                null
            )
            // Log URI with password masked for security
            val safeUri = if (userInfo != null) {
                "${scheme}://***:${"***"}@${connection.host}:${connection.port}/${connection.db}?timeout=${SOCKET_TIMEOUT_MS}"
            } else {
                "${scheme}://${connection.host}:${connection.port}/${connection.db}?timeout=${SOCKET_TIMEOUT_MS}"
            }
            val uriMsg = "URI created ($safeUri), constructing Jedis client..."
            thisLogger().info(uriMsg)
            logToCallback("INFO", uriMsg)
            val client = Jedis(uri)

            val pingSentMsg = "Jedis client created, sending PING..."
            thisLogger().info(pingSentMsg)
            logToCallback("INFO", pingSentMsg)
            val pingStart = System.currentTimeMillis()
            val pingOk = runCatching { client.ping() }.isSuccess
            val pingMs = System.currentTimeMillis() - pingStart
            if (!pingOk) {
                val pingWarn = "PING failed (ACL denied?), accepting connection anyway (ping=${pingMs}ms)"
                thisLogger().warn(pingWarn)
                logToCallback("WARN", pingWarn)
            }
            jedis = client
            val connectedMsg = "Connected to Valkey at ${connection.host}:${connection.port} (db=${connection.db}, ping=${pingMs}ms, pingOk=$pingOk)"
            thisLogger().info(connectedMsg)
            logToCallback("INFO", connectedMsg)
        } catch (e: Exception) {
            disconnectInternal()
            val errMsg = "Failed to connect to Valkey at ${connection.host}:${connection.port} (${e.javaClass.simpleName}: ${e.message})"
            thisLogger().error(errMsg, e)
            logToCallback("ERROR", errMsg)
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
            val closeWarn = "Error closing connection"
            thisLogger().warn(closeWarn, e)
            logToCallback("WARN", closeWarn)
        }
        val discMsg = "Disconnected"
        thisLogger().info(discMsg)
        logToCallback("INFO", discMsg)
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
     * Returns up to [count] keys matching the pattern (uses SCAN for safety).
     * Stops scanning as soon as [count] keys are collected.
     * @param pattern Glob pattern (e.g. "*", "user:*")
     * @param count   Maximum number of keys to return (default 100)
     */
    fun scanKeys(pattern: String = "*", count: Int = 100): List<String> {
        lock.lock()
        try {
            val client = checkConnected()
            val keys = mutableListOf<String>()
            val params = ScanParams().count(count).match(pattern)
            var cursor = "0"
            var batches = 0
            val scanStart = System.currentTimeMillis()

            do {
                val scanResult = client.scan(cursor, params)
                val added = scanResult.result.orEmpty().size
                keys.addAll(scanResult.result.orEmpty())
                cursor = scanResult.cursor.toString()
                batches++
                val scanMsg = "SCAN batch $batches: cursor=$cursor, got=$added, total=${keys.size}, elapsed=${System.currentTimeMillis() - scanStart}ms"
                thisLogger().info(scanMsg)
                logToCallback("INFO", scanMsg)
            } while (cursor != "0" && keys.size < count)

            val sorted = keys.sorted()
            val totalMs = System.currentTimeMillis() - scanStart
               val scanComplete = "SCAN complete: pattern=$pattern, returned=${sorted.size}, batches=$batches, total=${totalMs}ms"
            thisLogger().info(scanComplete)
            logToCallback("INFO", scanComplete)
            return sorted
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
        try {
            val size = checkConnected().dbSize()
            val dbSizeMsg = "DBSIZE: $size"
            thisLogger().info(dbSizeMsg)
            logToCallback("INFO", dbSizeMsg)
            return size
        } finally { lock.unlock() }
    }

    /**
     * Returns the "used_memory_human" field from INFO memory.
     */
    fun getUsedMemoryHuman(): String {
        lock.lock()
        try {
            val client = checkConnected()
            val infoText = client.info("memory")
            val memory = infoText
                .split("\r\n", "\n")
                .find { it.startsWith("used_memory_human:") }
                ?.substringAfter(":")
                ?.trim()
                ?: "?"
            val memMsg = "INFO memory: used_memory_human=$memory"
            thisLogger().info(memMsg)
            logToCallback("INFO", memMsg)
            return memory
        } catch (e: Exception) {
            val memWarn = "Failed to get INFO memory (${e.javaClass.simpleName}: ${e.message})"
            thisLogger().warn(memWarn)
            logToCallback("WARN", memWarn)
            throw e
        } finally { lock.unlock() }
    }

    private fun checkConnected(): Jedis {
        return jedis ?: throw IllegalStateException("Not connected to Valkey")
    }
}
