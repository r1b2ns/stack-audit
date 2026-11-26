# Contributing to StackAudit

Thanks for your interest in StackAudit. Bug reports, ideas, documentation fixes and pull requests are all welcome.

## Ways to help

- **Report a bug.** Use the [bug report form](https://github.com/r1b2ns/stack-audit/issues/new?template=bug_report.yml). The platform, the StackAudit version and the HTTP client or storage you use usually matter.
- **Suggest a feature.** Use the [feature request form](https://github.com/r1b2ns/stack-audit/issues/new?template=feature_request.yml). Describe the problem you are trying to solve first, then the solution you have in mind.
- **Pick an issue.** Issues labeled [`good first issue`](https://github.com/r1b2ns/stack-audit/labels/good%20first%20issue) are small and self-contained. Comment on the issue before you start so nobody else works on the same thing.
- **Improve the docs.** Typos, unclear steps and missing examples in the [README](README.md) or in [`docs/`](docs) are worth a pull request on their own.

For a large change, such as a new capture source or a new public API, open an issue first so we can agree on the design before you write the code.

## Project layout

| Path | What it is |
|---|---|
| `stackaudit-core` | KMP core: event log, encrypted store, masking, analytics and flags |
| `stackaudit-ui` | Compose Multiplatform viewer |
| `stackaudit-android` | Android entry point, OkHttp interceptor, Room and SharedPreferences capture |
| `stackaudit-ktor` | Ktor client plugin |
| `stackaudit-sqldelight` | SQLDelight driver wrapper |
| `stackaudit-settings` | multiplatform-settings wrapper |
| `stackaudit-apple` | Builds `StackAuditKit.xcframework` for the Swift package |
| `stackaudit-*-noop` | Release artifacts with the same API that record nothing |
| `stackaudit-gradle-plugin` | Gradle plugin that adds the interceptor to every OkHttp client |
| `stackaudit-cli` | Command-line tool that creates keys and signs handshake tokens |
| `ios/` | Swift package `StackAudit` (sources and tests) |
| `flutter/stackaudit` | Flutter plugin (beta) |
| `samples/android`, `samples/ios` | Demo apps that exercise every capture source |

## Setup

You need:

- JDK 17
- Android SDK (set `sdk.dir` in `local.properties`, or `ANDROID_HOME`)
- Xcode 16 and [XcodeGen](https://github.com/yonaskolb/XcodeGen), for the iOS parts
- Flutter SDK (stable), for the Flutter plugin

Fork the repository, clone your fork and create a branch from `main`.

## Running the tests

Run the tests for the parts you changed. CI runs all of them on every pull request.

**Kotlin, JVM and Android**

```bash
./gradlew :stackaudit-core:jvmTest :stackaudit-ktor:jvmTest :stackaudit-noop:jvmTest :stackaudit-ktor-noop:jvmTest :stackaudit-ui:jvmTest :stackaudit-ui:testAndroidHostTest :stackaudit-android:testAndroidHostTest :stackaudit-cli:test
```

**Gradle plugin**

```bash
cd stackaudit-gradle-plugin && ../gradlew validatePlugins test
```

**Kotlin on the iOS simulator**

```bash
./gradlew :stackaudit-core:iosSimulatorArm64Test :stackaudit-ktor:iosSimulatorArm64Test
```

**Swift package**

Build the framework first. While `stackaudit-apple/build/XCFrameworks/debug/StackAuditKit.xcframework` exists, `Package.swift` uses it instead of the released binary, so your Kotlin changes are picked up.

```bash
./gradlew :stackaudit-apple:assembleStackAuditKitDebugXCFramework
```

```bash
xcodebuild test -scheme StackAudit -destination 'platform=iOS Simulator,name=iPhone 16'
```

**Flutter plugin**

```bash
cd flutter/stackaudit && flutter pub get && flutter analyze && flutter test
```

**Sample apps**

```bash
./gradlew :sample-android:assembleDebug
```

```bash
cd samples/ios && xcodegen generate && open StackAuditDemo.xcodeproj
```

## Guidelines

- **Add tests.** Every bug fix and every feature comes with tests that fail without the change.
- **Keep the no-op artifacts in sync.** A public API added to a module must also exist in its `-noop` twin, so release builds compile unchanged. The no-op version records nothing and, where it makes sense, forwards to the real implementation (for example, analytics wrappers still call the real tracker).
- **Never store secrets in plain text.** Anything new that is captured (headers, parameters, values) goes through the secret masking before it is written. Add a test that proves it.
- **Keep platforms aligned.** When a feature exists on more than one platform, keep the names and behavior close, or explain the difference in the pull request.
- **Update the docs.** A change to the public API or to the setup updates the README or the guides in `docs/` in the same pull request.
- **Follow the existing style.** Kotlin uses the official code style. Prefer clear names over comments.

## Commits and pull requests

Commit subjects follow [Conventional Commits](https://www.conventionalcommits.org/), with an optional scope:

```
feat(ios): capture URLSessionWebSocketTask frames automatically
fix(core): stop iOS platformLog from crashing on a second start
docs: explain how to capture analytics events and feature flags
```

Common types are `feat`, `fix`, `docs`, `test`, `refactor` and `chore`.

When you open a pull request:

1. Keep it focused on one change.
2. Describe what changed and why, and link the issue it closes (`Closes #12`).
3. Add screenshots for any change in the viewer.
4. Make sure CI is green.

## Reporting a security issue

Do not open a public issue for a vulnerability, such as a secret that is not masked or a way around the access token. Report it privately through [GitHub Security Advisories](https://github.com/r1b2ns/stack-audit/security/advisories/new).

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
