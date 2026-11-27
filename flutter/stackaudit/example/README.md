# StackAudit example

One screen that runs each kind of capture: plain `http` and Dio requests, a login with secrets, a 404, an analytics event, a feature flag and a storage write. Shake the device or tap **Open viewer** to browse what was recorded.

```bash
cd flutter/stackaudit/example
flutter run
```

iOS needs Flutter's Swift Package Manager support. This app turns it on in `pubspec.yaml`.
