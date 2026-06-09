# Changelog

## [1.0.0] - 2026-04-21

### Added
- Initial release of LanComm library

### Changed
- Package renamed from `com.ymr.lanlink.core` to `com.ymr.lanlink.core`
- Proto payload type changed from `string` to `bytes`
- AuthRequest/AuthResponse have `custom_data: bytes` field for extensible auth

### Features
- UDP broadcast device discovery
- Android NSD (mDNS) service discovery
- TCP socket server with multi-client support
- TCP socket client with auto-reconnect
- Flow-based async API
- Pluggable AuthProvider interface
- StateFlow/SharedFlow for connection state and messages

### Migration from com.ymr.lanlink.core
1. Update package imports from `com.ymr.lanlink.*` to `com.ymr.lanlink.core.*`
2. If using proto messages, update `LanMessage.payload` handling (now bytes instead of string)
3. For auth, consider using `custom_data` field for custom credentials