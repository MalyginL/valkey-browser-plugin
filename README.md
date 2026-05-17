# Valkey Browser

IntelliJ plugin for browsing and managing Valkey/Redis keys and values directly from the IDE.

## Features

- **Key browsing** — scan and filter keys via glob patterns
- **Multi-type value viewer** — string, list, hash, set, sorted set, stream
- **Connection management** — save and switch between multiple connection profiles
- **SSL/TLS support** — connect to secure Valkey/Redis instances
- **Authentication** — username + password (Redis 6+)
- **DB selector** — switch between database indices (0–15)
- **TTL & stats** — per-key TTL, DB size, and memory usage at a glance
- **Key operations** — delete keys with confirmation
- **Timeout config** — adjustable connect and socket timeouts

## Installation

1. Open **Settings/Preferences** → **Plugins** → **Marketplace**
2. Search for **Valkey Browser**
3. Click **Install** and restart the IDE

Or install manually from a `.zip` build artifact via **Install Plugin from Disk**.

## Usage

### Open the Tool Window

Go to **View → Tool Windows → Valkey Browser** (right dock).

### Connect

1. Enter the connection details (host, port, DB, credentials)
2. Check **SSL** if your server requires TLS
3. Click **Connect** — a green dot and "Connected" label confirm success

### Browse Keys

- The key list loads automatically after connecting
- Use the **Scan Pattern** field to filter keys (e.g. `user:*`)
- Adjust the **Limit** to control keys per SCAN batch
- Press **⟳ Scan** or `F5` to refresh

### View Values

Click a key to see its value in the bottom panel. Data types are rendered contextually:

| Type       | Format                          |
|------------|---------------------------------|
| String     | Raw text                        |
| List       | Numbered items                  |
| Hash       | `field → value` pairs           |
| Set        | Comma-separated members         |
| Sorted set | Ranked entries with scores      |
| Stream     | Entries with ID and fields      |

### Manage Connections

- **Save…** — save current form fields as a named profile
- **Delete…** — remove the selected profile
- Select a saved connection from the list to populate the form

### Delete a Key

- Select a key and click **🗑 Delete Key**, or press `Delete`
- Confirm in the dialog to permanently remove the key

## Keyboard Shortcuts

| Key       | Action              |
|-----------|---------------------|
| `F5`      | Refresh keys        |
| `Delete`  | Delete selected key |

## Building from Source

```bash
./gradlew buildPlugin
```

The plugin archive is at `build/distributions/valkey-browser-<version>.zip`.

## Requirements

- IntelliJ IDEA 2025.2+ (or any IntelliJ-based IDE)
- Valkey or Redis server (Redis 6+ for username/password auth)
