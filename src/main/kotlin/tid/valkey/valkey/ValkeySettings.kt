package tid.valkey.valkey

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent connection config for a single saved connection profile.
 */
data class SavedConnectionConfig(
    var name: String = "new connection",
    var host: String = "localhost",
    var port: Int = 6379,
    var db: Int = 0,
    var ssl: Boolean = false,
    var username: String = "default",
    var password: String = ""
) {
    /**
     * Convert to a ValkeyConnection for use with ValkeyService.
     */
    fun toConnection(): ValkeyConnection {
        return ValkeyConnection(
            host = host,
            port = port,
            db = db,
            ssl = ssl,
            username = username,
            password = password
        )
    }

    /**
     * Create a SavedConnectionConfig from a ValkeyConnection and a name.
     */
    companion object {
        fun from(connection: ValkeyConnection, name: String): SavedConnectionConfig {
            return SavedConnectionConfig(
                name = name,
                host = connection.host,
                port = connection.port,
                db = connection.db,
                ssl = connection.ssl,
                username = connection.username,
                password = connection.password
            )
        }
    }
}

/**
 * Persists connection settings across IDE sessions.
 * Stores the list of named connections and the index of the last-used one.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ValkeyBrowserSettings",
    storages = [Storage("valkey_browser.xml")]
)
class ValkeySettings {

    /** List of saved named connections. */
    var savedConnections: MutableList<SavedConnectionConfig> = mutableListOf(
        SavedConnectionConfig()
    )

    /** Index into savedConnections — the last-selected profile. */
    var lastConnectionIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, savedConnections.size.coerceAtLeast(1) - 1)
        }

    /**
     * Returns the currently active saved connection config.
     */
    fun current(): SavedConnectionConfig {
        return savedConnections.getOrElse(lastConnectionIndex) { SavedConnectionConfig() }
    }

    /**
     * Save the current connection as a named profile.
     * If a profile with the same name already exists, it gets updated.
     */
    fun saveCurrent(config: SavedConnectionConfig) {
        val existingIndex = savedConnections.indexOfFirst { it.name == config.name }
        if (existingIndex >= 0) {
            savedConnections[existingIndex] = config
        } else {
            savedConnections.add(config)
        }
    }

    /**
     * Remove a saved connection by name.
     */
    fun removeConnection(name: String) {
        val removed = savedConnections.removeAll { it.name == name }
        if (removed && lastConnectionIndex >= savedConnections.size) {
            lastConnectionIndex = maxOf(0, savedConnections.size - 1)
        }
    }

    /**
     * Select a connection profile by name and populate form fields.
     */
    fun selectConnection(name: String) {
        val index = savedConnections.indexOfFirst { it.name == name }
        if (index >= 0) {
            lastConnectionIndex = index
        }
    }
}
