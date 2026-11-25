# StackAudit

A free, open-source audit and inspection library for mobile apps. The core is written in Kotlin Multiplatform, and it works in KMP, Android, iOS and Flutter projects.

StackAudit records what your app does at runtime and lets you browse it on the device:

- REST requests and responses (OkHttp, Ktor, URLSession, `package:http`)
- WebSocket connections and frames (Ktor, URLSessionWebSocketTask)
- Key-value storage (SharedPreferences, UserDefaults, multiplatform-settings)
- SQL statements (SQLDelight, Room, SQLite)
- Analytics events, validated against a tracking plan
- Feature flags, with local overrides

Everything stays on the device. Secrets are masked before they are written, and access to the log can require a signed, short-lived token.

## Installation

### Android and KMP

The libraries are published to Maven Central under `io.github.r1b2ns`.

```kotlin
dependencies {
    debugImplementation("io.github.r1b2ns:stackaudit-android:0.1.0")
    debugImplementation("io.github.r1b2ns:stackaudit-ui:0.1.0")
    releaseImplementation("io.github.r1b2ns:stackaudit-noop:0.1.0")
}
```

| Artifact | What it adds | No-op twin |
|---|---|---|
| `stackaudit-core` | Capture engine, storage, masking, handshake | `stackaudit-noop` |
| `stackaudit-android` | OkHttp interceptor, SharedPreferences, Room | `stackaudit-noop` |
| `stackaudit-ui` | Compose Multiplatform viewer, shake to open | |
| `stackaudit-ktor` | Ktor client plugin and WebSocket capture | `stackaudit-ktor-noop` |
| `stackaudit-sqldelight` | `SqlDriver` wrapper | `stackaudit-sqldelight-noop` |
| `stackaudit-settings` | multiplatform-settings wrapper | `stackaudit-settings-noop` |

The no-op twins keep the same API and do nothing, so release builds carry no capture code.

#### Gradle plugin (optional)

The plugin adds the StackAudit interceptor to every `OkHttpClient.Builder().build()` call in your app and its dependencies at build time.

```kotlin
plugins {
    id("io.github.r1b2ns.stackaudit") version "0.1.0"
}

stackAudit {
    okhttp.set(true)
    skipBuildTypes.set(listOf("release"))
}
```

### iOS

StackAudit is distributed as a Swift package. In Xcode, choose **File › Add Package Dependencies** and enter:

```
https://github.com/r1b2ns/stack-audit
```

Or add it to `Package.swift`:

```swift
.package(url: "https://github.com/r1b2ns/stack-audit", from: "0.1.0")
```

The package requires iOS 15 or later.

### Flutter (preview)

The Flutter plugin lives in [`flutter/stackaudit`](flutter/stackaudit) and is not on pub.dev yet. Use a git dependency for now:

```yaml
dependencies:
  stackaudit:
    git:
      url: https://github.com/r1b2ns/stack-audit
      path: flutter/stackaudit
```

## Quick start

### Android

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        StackAudit.start()
    }
}
```

Shake the device or call `StackAudit.show()` to open the viewer.

Wire up the sources you use:

```kotlin
val client = HttpClient { install(StackAuditKtor) }
val driver = AndroidSqliteDriver(Schema, context, "app.db").audited("app.db")
val prefs = context.auditedSharedPreferences("prefs")
val db = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").audited("app.db").build()
```

For OkHttp, add `StackAuditInterceptor()` to your builder, or apply the Gradle plugin.

### iOS

```swift
import StackAudit

@main
struct MyApp: App {
    init() {
        StackAudit.start()
    }
}
```

URLSession traffic is captured automatically. For sessions with a custom configuration, call `configuration.audited()`. UserDefaults.standard is watched by default; call `StackAudit.watch(_:suiteName:)` for other suites. For WebSockets, use `session.auditedWebSocketTask(with:)`, and for SQLite, `StackAuditSQL.trace(db, name:)`.

### Flutter

```dart
await StackAudit.start();
final client = StackAuditHttpClient(http.Client());
```

## Configuration

```kotlin
StackAudit.start {
    secrets = Secrets.Mask {
        header("X-Session")
        jsonField("pin")
        allow("token_type")
    }
    access = Access.Handshake(publicKey = "BASE64_ED25519_PUBLIC_KEY")
    retention = 3.days
    maxBodySize = 512 * 1024
    maxStorage = 50L * 1024 * 1024
}
```

```swift
StackAudit.start {
    $0.secrets = .mask(extraHeaders: ["X-Session"], extraFields: ["pin"])
    $0.access = .handshake(publicKey: "BASE64_ED25519_PUBLIC_KEY")
    $0.retention = 3 * 24 * 3600
}
```

### Secrets

| Mode | Behavior |
|---|---|
| `Mask` (default) | Secret headers, JSON and form fields, query parameters, storage values and SQL literals are masked before they are written. |
| `ShowAfterHandshake` | Values are masked in the log. The raw values are kept encrypted and shown only to a session that holds the `read_secrets` scope. |
| `Show` | Nothing is masked. Use it only on local debug builds; StackAudit logs a warning if it is active in a release build. |

The default secret list covers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `password`, `token`, `access_token`, `refresh_token`, `id_token`, `client_secret` and `api_key`.

### Access

| Mode | Behavior |
|---|---|
| `Open` (default) | Anyone holding the device can open the viewer. |
| `Handshake` | The viewer opens only after a signed token is accepted. Tokens are Ed25519-signed, bound to the app id (and optionally the device), expire after at most 24 hours and can be used once. |
| `Hidden` | No viewer. The log is reachable only through export. |

### Handshake tokens

The `stackaudit-cli` tool, attached to each GitHub release, creates keys and signs tokens:

```bash
stackaudit-cli keygen --out ./keys
```

```bash
stackaudit-cli sign --key ./keys/stackaudit.key --app-id com.example.app --scope read,read_secrets --ttl 15m --scheme myapp
```

The `sign` command prints the token and a link of the form `myapp://stackaudit/open?token=…`. Pass incoming links to `StackAudit.handleLink(url)` (Kotlin) or `StackAudit.handle(url:)` (Swift). Keep the private key off the device and out of the repository.

## Export

The viewer's Export action shares the HTTP log as a HAR file. From code, use `StackAudit.exportHar()` for HAR or `StackAudit.exportJsonl()` for every event as JSONL. Exports are masked by default. Passing `includeSecrets = true` adds raw values only where they exist, which is never the case in `Mask` mode.

## Privacy

StackAudit has no backend and sends nothing anywhere. See [PRIVACY.md](PRIVACY.md) for what it stores and how to ship it safely.

## Building from source

Requires JDK 17, Xcode, and (for the Flutter plugin) the Flutter SDK.

```bash
./gradlew build
```

```bash
swift test
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
