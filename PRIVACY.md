# Privacy

StackAudit is a library that runs inside your app. It has no backend, no analytics, no telemetry and no crash reporting of its own. Nothing it records leaves the device unless a person exports the log on purpose.

This document describes what the library stores, where, and for how long, so you can decide how to ship it and what to tell your users.

## What StackAudit records

Only the sources you enable, and only while `StackAudit.start()` has been called:

| Source | What is stored |
|---|---|
| HTTP | Method, URL, status, headers, timings, errors, and request and response bodies up to `maxBodySize` (1 MB by default) |
| WebSocket | Connection URL, frames sent and received, close codes and errors |
| Storage | Store name, key, operation, old and new values |
| Database | SQL text, bound arguments, duration, rows affected, errors |
| Analytics | Provider, event name, parameters and the tracking-plan result |
| Feature flags | Key, default, remote and override values, and who changed them |

StackAudit also stores a random device id (a UUID created on first start, never derived from hardware identifiers), handshake nonces, and flag overrides.

Some categories can be turned off with `categories` in the configuration.

## Masking

In the default `Mask` mode, secret values are replaced before anything is written to disk:

- Headers such as `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key`
- JSON fields, form fields and query parameters such as `password`, `token`, `access_token`, `refresh_token`, `id_token`, `client_secret` and `api_key`
- Storage values under secret keys
- SQL arguments and SQL literals bound to secret columns

Long values keep their last four characters so you can tell them apart. You can add your own names with `header`, `jsonField` and `queryParam`, or exclude names with `allow`.

Masking works on names. A secret stored under a name StackAudit does not know is recorded as is, and so is personal data such as names, e-mail addresses or health data in request bodies. Add those field names to the configuration, or turn the category off.

In `ShowAfterHandshake` mode the masked copy is what the log shows, and the raw value is stored encrypted next to it. In `Show` mode nothing is masked, in storage, in the viewer or in exports.

## Where the data lives

- **Android:** a SQLite database in the app's `noBackupFilesDir`, which is excluded from Auto Backup and device transfer.
- **iOS:** a SQLite database in the app's sandbox, marked as excluded from iCloud and iTunes backups.
- **Flutter:** the native store of the platform the app runs on.

Bodies and raw secret values are encrypted with AES-GCM. The key is held in the Android Keystore or the iOS Keychain (`AfterFirstUnlockThisDeviceOnly`) and never leaves the device. Event metadata, such as URLs, headers and SQL text, is stored after masking but without that extra encryption.

The data is private to your app's sandbox, like any other app data. Each event is linked to the previous one by a hash chain, so `StackAudit.verify()` can detect entries that were changed or removed.

## How long it is kept

- Events older than `retention` (7 days by default) are deleted.
- When the store exceeds `maxStorage` (200 MB by default), the oldest events are deleted first.
- `StackAudit.clear()` deletes every event at once. Uninstalling the app deletes everything.

## Who can see it

- `Open`: anyone holding the unlocked device can open the viewer.
- `Handshake`: the viewer opens only after a signed token is accepted. Tokens are signed with an Ed25519 key that you keep off the device, are bound to your app id (and optionally to one device id), expire after at most 24 hours and cannot be reused.
- `Hidden`: there is no viewer; the log is only available through export from code.

## Leaving the device

StackAudit makes no network requests of its own. The log leaves the device only when someone:

- Taps Export in the viewer, which hands a HAR file to the system share sheet, or
- Calls `exportHar()` or `exportJsonl()` from your code and sends the result somewhere.

Exports are masked unless secrets are requested and available, or `Show` mode is on, in which case they carry raw values.

## Shipping it safely

- Use the `-noop` artifacts in release builds on Android and KMP. They have the same API and record nothing.
- On iOS, call `StackAudit.start()` only in builds meant for testing, for example behind `#if DEBUG` or a build configuration flag.
- If StackAudit is active in a build that real users receive, prefer `Access.Handshake`, keep `Mask` on, and shorten `retention`.
- Never use `Show` outside local debug builds.

Because the library does not transmit data, it does not by itself add data collection to your app. If you export logs from users' devices to your own systems, that collection is yours to disclose in your privacy policy and in the App Store and Google Play data-safety forms.

## Reporting a problem

If you find a way StackAudit exposes data it should not, please open a private security advisory at <https://github.com/r1b2ns/stack-audit/security/advisories/new> instead of a public issue.
