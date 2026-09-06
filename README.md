# PAM Native Background Transfer

## Start here

This is a Composer extension for PAM Native. Install the PAM Runtime, create a native project, and then add this package through PAM’s verified Composer toolchain:

```bash
curl --proto '=https' --proto-redir '=https' --tlsv1.2 \
    --connect-timeout 15 --max-time 60 --max-filesize 1048576 -fsSL \
    https://github.com/push-in/pam/releases/latest/download/install.sh | sh

pam init my-app --template native
cd my-app
pam composer require pushinbr/pam-native-background-transfer
pam doctor --fix
```


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

Platform minimums: Android API 26+ and iOS 15+. Composer declares PAM Native 0.8–1.x compatibility; the current branch is compiled against PAM Native 1.0.19.


## What installation does

`pam add background-transfer` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove background-transfer` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## Signed uploads (unreleased)

The optional `headers` argument is available in this development branch. Use it only with a native host built with the matching plugin version; an older native module does not implement this wire field.

```php
$transfers->upload(
    url: $grant['url'],
    source: 'documents/selected.png',
    complete: function (?string $id, ?string $error): void {
        if ($error !== null) {
            // Show a retry action; no valid transfer identifier was returned.
            return;
        }
        // Persist $id and query status after resume or relaunch.
    },
    headers: $grant['headers'],
);
```

The source is an existing file inside the application sandbox. Header values are strings, with at most 32 headers and four KiB of JSON. Names are case-insensitively unique; control characters and transport-managed headers (`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Trailer`, `Upgrade`) are rejected. Android preserves headers with the queued work; iOS includes them in the background URLSession request.

Do not change the source while uploading. Obtain a fresh signed grant when an earlier one expires. A successful transfer means the HTTP upload completed; ask your authenticated backend to verify and confirm the received file before presenting it as an accepted document.

Android download replacement is atomic after completion. HTTP errors are failures on both platforms. PHP returns `null` for malformed/unavailable status replies; callers must handle this explicitly. Android transfer operations acknowledge WorkManager completion asynchronously.

Validation of this branch includes PHP contracts, Android compilation/JVM file tests, and Swift policy tests/module compilation. Physical lifecycle, HTTPS redirects, and real signed uploads remain release gates. This section does not claim a published release is ready for production.

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

Use the Composer and plugin manifests for declared PAM Native compatibility. Android API 26+ and iOS 15+ are the platform minimums. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-background-transfer/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
