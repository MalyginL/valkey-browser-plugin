package tid.valkey.valkey

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Persistent connection config for a single saved connection profile.
 *
 * Note: the password is **not** stored in this object — it is saved securely via
 * [PasswordSafe] in the OS keychain (Windows Credential Manager / macOS Keychain).
 */
data class SavedConnectionConfig(
    var name: String = "new connection",
    var host: String = "localhost",
    var port: Int = 6379,
    var db: Int = 0,
    var ssl: Boolean = false,
    var username: String = "default"
)

/**
 * Persists connection settings across IDE sessions.
 * Stores the list of named connections and the index of the last-used one.
 *
 * Passwords are **not** stored in the XML state file. Instead they are saved
 * via the IntelliJ [PasswordSafe] which delegates to the OS keychain
 * (Windows Credential Manager / macOS Keychain / Linux libsecret).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ValkeyBrowserSettings",
    storages = [Storage("valkey_browser.xml")]
)
class ValkeySettings : PersistentStateComponent<ValkeySettings> {

    companion object {
        private const val CREDENTIAL_SYSTEM_ID = "ValkeyBrowser"
    }

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

    // ── Secure password storage via PasswordSafe (off EDT) ──

    private fun createCredentialAttributes(name: String): CredentialAttributes {
        return CredentialAttributes(generateServiceName(CREDENTIAL_SYSTEM_ID, "connection:$name"))
    }

    /**
     * Save a password for the given connection name in the OS keychain (runs off EDT).
     * Pass an empty string to delete the stored password.
     */
    fun savePassword(name: String, password: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val attributes = createCredentialAttributes(name)
                if (password.isEmpty()) {
                    PasswordSafe.instance.set(attributes, null)
                } else {
                    val credentials = Credentials(null, password)
                    PasswordSafe.instance.set(attributes, credentials)
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to save password for '$name'", e)
            }
        }
    }

    /**
     * Load the password for the given connection name from the OS keychain (runs off EDT).
     * Callback receives `null` if no password is stored.
     */
    fun loadPassword(name: String, callback: (String?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val attributes = createCredentialAttributes(name)
                PasswordSafe.instance.getPassword(attributes)
            } catch (e: Exception) {
                thisLogger().warn("Failed to load password for '$name'", e)
                null
            }
            callback(result)
        }
    }

    /**
     * Remove the stored password when a connection is deleted (runs off EDT).
     */
    fun removePassword(name: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val attributes = createCredentialAttributes(name)
                PasswordSafe.instance.set(attributes, null)
            } catch (e: Exception) {
                thisLogger().warn("Failed to remove password for '$name'", e)
            }
        }
    }

    // ── PersistentStateComponent ──

    override fun getState(): ValkeySettings = this

    override fun loadState(state: ValkeySettings) {
        savedConnections.clear()
        savedConnections.addAll(state.savedConnections)
        lastConnectionIndex = state.lastConnectionIndex
    }
}
