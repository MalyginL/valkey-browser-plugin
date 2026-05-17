<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Valkey Browser Changelog

## [Unreleased]

## [1.0.0] - 2026-05-17

### Added

- Project scaffold (Kotlin, Gradle, IntelliJ Platform Plugin 2.16.0, Jedis 5.2.0)
- `ValkeyService` — connect/disconnect, `scanKeys()`, type-aware value readers (`getString`, `getList`, `getHash`, `getSet`, `getZSet`, `getStream`), `deleteKey()`, `getTTL()`, `getDbSize()`, `getUsedMemoryHuman()`
- `ValkeyBrowserPanel` — tool window with connection form, key list, and value viewer
- `ValkeySettings` — persistent storage for multiple named connection profiles
- `MyToolWindowFactory` — right-anchored "Valkey Browser" tool window
- String, list, hash, set, sorted set, and stream value rendering
- SSL/TLS connection support
- Username + password authentication (Redis 6+)
- DB index selector (0–15)
- Connection timeout configuration (connect and socket)
- Health indicator (green dot + status label)
- Key pattern filter (glob matching via SCAN)
- TTL display per key
- Key count, DB size, and memory usage stats
- Delete key action with confirmation dialog
- Refresh keys button and `F5` / `Delete` keyboard shortcuts
- Scan pattern field and batch size limit control
- Loading indicator during key/value fetch
- Plugin icons (`valkey.svg`) and `ValkeyIcons` loader
- Resource bundle (`ValkeyBundle.properties`) for all UI strings
- Package rename to `tid.valkey`
