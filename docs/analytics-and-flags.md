# Analytics and feature flags

HTTP traffic is captured automatically. Analytics events and feature flags are not: StackAudit has no way to know which analytics SDK or remote config your app uses, so you send them through StackAudit. This guide shows the setup on each platform.

Call `StackAudit.start()` first, as described in the [README](../README.md#quick-start). Nothing is recorded before that.

- [Analytics](#analytics)
  - [Tracking plan](#tracking-plan)
  - [Android and KMP](#analytics-on-android-and-kmp)
  - [iOS](#analytics-on-ios)
  - [Flutter](#analytics-on-flutter)
- [Feature flags](#feature-flags)
  - [How a value is resolved](#how-a-value-is-resolved)
  - [Android and KMP](#flags-on-android-and-kmp)
  - [iOS](#flags-on-ios)
  - [Flutter](#flags-on-flutter)
- [Release builds](#release-builds)

## Analytics

Every event you send appears in the viewer under **Requests → Analytics**. It shows the provider, the event name, its parameters and whether it matches your tracking plan.

Parameters go through the same secret masking as headers and storage. A parameter named `password` or `token` is masked before it is written.

### Tracking plan

The tracking plan is optional. Without one, events are recorded with the status *Not checked*. With one, each event is checked when it is recorded:

| Status | Meaning |
|---|---|
| Valid | The event is in the plan and its parameters match |
| Invalid | A required parameter is missing, has the wrong type, or is unexpected in strict mode |
| Not in plan | The event name is not in the plan |

The plan is JSON:

```json
{
  "strict": false,
  "events": {
    "purchase": {
      "required": ["value", "currency"],
      "optional": ["coupon"],
      "types": { "value": "number", "currency": "string" }
    },
    "login": {
      "required": ["method"]
    }
  }
}
```

| Field | Description |
|---|---|
| `events` | Map from event name to its spec |
| `required` | Parameters that must be present and not null |
| `optional` | Parameters that may be present. Only used in strict mode |
| `types` | Expected type per parameter: `string`, `number`, `integer` or `boolean` |
| `strict` | When `true`, any parameter not listed in `required` or `optional` makes the event invalid. Defaults to `false` |

Keep the plan in a file (for example `tracking-plan.json` in your assets or bundle) and load it at startup, so the same file can be reviewed by the product and data teams.

### Analytics on Android and KMP

Set the plan after `StackAudit.start()`:

```kotlin
StackAudit.start()
StackAudit.analytics.plan = TrackingPlan.fromJson(trackingPlanJson)
```

The simplest way to capture events is to wrap the tracker your app already calls. `AnalyticsTracker` is a single-method interface:

```kotlin
val tracker: AnalyticsTracker =
    StackAudit.analytics.wrap(
        AnalyticsTracker { name, params -> firebaseAnalytics.logEvent(name, params.toBundle()) },
        provider = "firebase",
    )

tracker.track("purchase", mapOf("value" to 49.9, "currency" to "BRL"))
```

Every call to `track` is recorded in StackAudit and then forwarded to the real tracker. Use one wrapper per provider (`"firebase"`, `"amplitude"`, `"segment"`) so the viewer shows where each event went.

If your app already has its own analytics layer, record from it directly instead:

```kotlin
StackAudit.analytics.record(provider = "amplitude", name = "login", params = mapOf("method" to "email"))
```

### Analytics on iOS

```swift
import StackAudit

StackAudit.start()
try? StackAuditAnalytics.setTrackingPlan(json: trackingPlanJSON)
```

Record each event where your app sends it to the analytics SDK, usually in the one place that wraps all providers:

```swift
func track(_ name: String, _ params: [String: Any] = [:]) {
    StackAuditAnalytics.record(provider: "firebase", name: name, params: params)
    Analytics.logEvent(name, parameters: params)
}
```

Parameter values can be `String`, `Int`, `Double`, `Float` or `Bool`. Any other value is recorded as its text description.

### Analytics on Flutter

```dart
await StackAudit.start();
await StackAudit.setTrackingPlan(await rootBundle.loadString('assets/tracking-plan.json'));

Future<void> track(String name, [Map<String, Object?> params = const {}]) async {
  await StackAudit.recordAnalytics('firebase', name, params);
  await FirebaseAnalytics.instance.logEvent(name: name, parameters: params.cast());
}
```

## Feature flags

Flags appear in the viewer under the **Flags** tab, and every change of value is also recorded as an event. From that tab you can override any flag on the device, so you can test the new checkout without touching the remote config.

To capture flags, read them through StackAudit instead of reading the remote config directly. Flags are listed in the tab only after the app reads them at least once.

### How a value is resolved

Each read returns, in order of priority:

1. **Override**: the value set in the StackAudit viewer, kept on the device across restarts until you reset it.
2. **Remote**: the value your provider returns for the key.
3. **Default**: the value passed in the call.

A remote or override value of a different type from the default is ignored. For example, a text value for a `bool` flag falls back to the default.

An event is recorded only when the resolved value or its source changes, not on every read, so reading a flag in a loop does not flood the log.

### Flags on Android and KMP

Wrap your remote config in a `FlagProvider`. It returns the remote value for a key, or `null` when the key has no remote value:

```kotlin
val flags: AuditedFlags =
    StackAudit.flags.wrap(
        FlagProvider { key ->
            when (val value = remoteConfig[key]) {
                is Boolean -> FlagValue.Bool(value)
                is Number -> FlagValue.Number(value.toDouble())
                is String -> FlagValue.Text(value)
                else -> null
            }
        },
        name = "remote_config",
    )

val newCheckout = flags.bool("new_checkout", default = false)
val cartLimit = flags.number("cart_limit", default = 10.0).toInt()
val banner = flags.string("banner_text", default = "Welcome")
```

Create the wrapper once, for example in your dependency graph, and read every flag through it. `name` is shown in the viewer as the flag's provider.

Overrides can also be set from code, which is useful in UI tests:

```kotlin
StackAudit.flags.override("new_checkout", FlagValue.Bool(true))
StackAudit.flags.clearOverride("new_checkout")
StackAudit.flags.clearOverrides()
```

### Flags on iOS

Make your remote config conform to `StackAuditFlagProvider` and read flags through `StackAuditFlags`:

```swift
import StackAudit

struct RemoteConfigFlags: StackAuditFlagProvider {
    func value(forKey key: String) -> Any? {
        remoteConfig[key]
    }
}

let flags = StackAuditFlags(provider: RemoteConfigFlags(), name: "remote_config")

let newCheckout = flags.bool("new_checkout", default: false)
let cartLimit = Int(flags.number("cart_limit", default: 10))
let banner = flags.string("banner_text", default: "Welcome")
```

`value(forKey:)` returns a `Bool`, `Int`, `Double` or `String`, or `nil` when the key has no remote value.

### Flags on Flutter

On Flutter you pass the remote value yourself, and StackAudit returns the resolved one:

```dart
final remoteConfig = FirebaseRemoteConfig.instance;

final newCheckout = await StackAudit.flagBool(
  'new_checkout',
  defaultValue: false,
  remote: remoteConfig.getBool('new_checkout'),
  provider: 'remote_config',
);

final cartLimit = await StackAudit.flagNumber('cart_limit', defaultValue: 10, remote: remoteConfig.getInt('cart_limit'));
final banner = await StackAudit.flagString('banner_text', defaultValue: 'Welcome', remote: remoteConfig.getString('banner_text'));
```

Before `StackAudit.start()`, these calls return `remote` if it is set, otherwise `defaultValue`.

## Release builds

On Android and KMP, the no-op artifacts keep the same API, so this code compiles unchanged in release builds:

- Analytics wrappers forward every event to the real tracker and record nothing.
- `AuditedFlags` returns the remote value, or the default when there is none. Overrides are ignored, so a value set in the viewer during QA can never reach production.

The full setup is in the samples: [`DemoScenarios.kt`](../samples/android/src/main/kotlin/io/github/r1b2ns/stackaudit/demo/DemoScenarios.kt) on Android and [`Scenarios.swift`](../samples/ios/Sources/Scenarios.swift) on iOS.
