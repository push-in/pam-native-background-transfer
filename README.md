<!-- pam:product-page:start -->
<div align="center">

# PAM Native Background Transfer

**Uploads and downloads that survive backgrounding and process death.**

Schedule durable, observable file transfers through platform-native background services while your PHP application remains responsive.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-background-transfer?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-background-transfer)
[![CI](https://img.shields.io/github/actions/workflow/status/push-in/pam-native-background-transfer/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/push-in/pam-native-background-transfer/actions)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/native/overview/) · [Quick start](#quick-start) · [What you can build](#what-you-can-build) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-background-transfer/issues)**

</div>

---

## Why PAM Native Background Transfer

Schedule durable, observable file transfers through platform-native background services while your PHP application remains responsive. The public API is strictly typed for PHP 8.5; expensive or frame-sensitive work stays in Rust or the platform SDK instead of crossing the application boundary every frame.

| | |
| --- | --- |
| **Best for** | A focused capability you can add to any PAM Native application |
| **Native path** | WorkManager/DownloadManager · URLSession |
| **Application model** | Composer package + generated native integration |
| **Design rule** | Independent module; no feed, vertical, or application template bundled |

## What you can build

- Resumable media uploads
- Offline download queues for video, documents, or maps
- Reliable sync of large files under constrained networks

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-background-transfer
pam doctor --fix
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

## See it in action

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

Platform support: Android API 26+, iOS 15+, PAM Native 0.8.x.

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

This package targets PAM Native `0.8.x`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-background-transfer/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
