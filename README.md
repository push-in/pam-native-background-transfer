# PAM Native Background Transfer

Durable uploads and downloads that continue outside the PHP runtime. Android uses WorkManager `2.11.2`; iOS uses a launch-event-enabled background `URLSession`.

```bash
pam add background-transfer
pam doctor
```

```php
$transfers = new Pam\Native\BackgroundTransfer\BackgroundTransfer();
$transfers->download('https://cdn.example.com/movie.mp4', 'media/movie.mp4', function (?string $id, ?string $error): void {
    // Persist the id and query it after relaunch.
});
```

Transfers require HTTPS. Paths are always resolved inside the application sandbox and traversal is rejected natively. State, kind and network requirements are sequential integer-backed enums. Android retries transient failures up to three times and honors connected, unmetered, or not-roaming constraints.

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.


## What installation does

`pam add background-transfer` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove background-transfer` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## API guide

| API | Responsibility |
| --- | --- |
| `BackgroundTransfer` | Create uploads/downloads and query durable work. |
| `TransferSnapshot` | Read identifier, progress, state, and error context. |
| `NetworkRequirement` | Require connected, unmetered, or not-roaming networks. |
| `TransferState` / `TransferKind` | Typed lifecycle and direction enums. |

All coded states, kinds, and variants are sequential integer-backed enums. Use enum cases in application code; do not depend on raw wire numbers.

## Production checklist

- Persist transfer identifiers before leaving the initiating screen.
- Reconcile transfer state after launch and resume.
- Treat destination files as untrusted until size, type, and checksum validation pass.
- Run `pam doctor`, `pam test`, and a signed release build on every supported platform.
- Exercise denial, cancellation, backgrounding, process restart, and offline behavior before release.

## Troubleshooting

- **Work never starts:** verify HTTPS, network constraints, and OS background policy.
- **Path rejected:** use an application-sandbox-relative path without traversal.
- **Progress stops in development:** query the persisted identifier after runtime reload.
- **Native integration is stale:** run `pam doctor --fix`, rebuild the native host, and inspect the first reported diagnostic.

## Compatibility and support

This package targets PAM Native `0.6.x`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-background-transfer/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
