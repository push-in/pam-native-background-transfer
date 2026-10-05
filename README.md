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

Durable uploads, downloads and request chains that keep running outside the PHP runtime. Android uses WorkManager `2.11.2` (expedited + `dataSync` foreground service when a notification is set) and OkHttp `4.12.0`.

```bash
pam add background-transfer
pam doctor
```

### Upload a video and post the message, even if the app is closed

```php
use Pam\Native\BackgroundTransfer\{Backoff, BackgroundTransfer, HttpStep, Multipart, NetworkRequirement, Secret, TransferHandle, TransferNotification, TransferSnapshot};

Secret::put('session', $accessToken); // refresh it any time; queued transfers read the latest value

BackgroundTransfer::upload('https://api.example.com/media')
    ->multipart(fn (Multipart $m) => $m->file('file', $video->path, 'video/mp4')->field('caption', $caption))
    ->header('Idempotency-Key', $clientMessageId)
    ->bearer(Secret::vault('session'))
    ->notification(TransferNotification::make('Enviando vídeo')->progress()->failed('Falha ao enviar'))
    ->network(NetworkRequirement::Unmetered)
    ->retry(3, Backoff::Exponential)
    ->then(HttpStep::post('https://api.example.com/chats/42/messages')
        ->bearer(Secret::vault('session'))
        ->json(['media_id' => '{{response.id}}', 'client_id' => $clientMessageId]))
    ->tag('chat:42')
    ->dispatch(fn (TransferHandle $transfer) => $transfer->watch(
        fn (TransferSnapshot $s) => $this->progress = $s->progress(),
    ));
```

### Signed URL uploads

```php
BackgroundTransfer::upload('{{steps.sign.data.upload_url}}')->put()
    ->file($photo->path, 'image/jpeg')
    ->headersFrom('steps.sign.data.headers')
    ->before(HttpStep::post('https://api.example.com/uploads/signed-url')
        ->bearer(Secret::vault('session'))->json(['file_name' => 'photo.jpg'])->as('sign'))
    ->then(HttpStep::post('https://api.example.com/posts')
        ->json(['media_items' => [['key' => '{{steps.sign.data.key}}']]]))
    ->dispatch();
```

### Transcode before upload (with `pushinbr/pam-native-media` 0.4+)

```php
BackgroundTransfer::upload('https://api.example.com/media')
    ->multipart(fn (Multipart $m) => $m->file('file', 'captures/clip.mov', 'video/quicktime'))
    ->transcode(\Pam\Native\Media\VideoPreset::Chat720p)
    ->dispatch();
```

Pass `fallbackToOriginal: true` to send the original file when the device cannot re-encode it. A transfer holds up to 64 steps.

### Downloads, inspection and housekeeping

```php
BackgroundTransfer::download('https://cdn.example.com/movie.mp4')->to('downloads/movie.mp4')->dispatch();

BackgroundTransfer::all(fn (array $transfers) => ..., tag: 'chat:42'); // reconcile after relaunch
BackgroundTransfer::watch($id, fn (TransferSnapshot $s) => ...);
BackgroundTransfer::retry($id);   // resumes after the last completed step
BackgroundTransfer::cancel($id);
BackgroundTransfer::prune(olderThanDays: 7);
```

Templates: `{{response.a.b}}` reads the previous step's JSON response, `{{steps.<name>.a.0.b}}` a step named with `as()`, and `{{transfer.id}}` / `{{transfer.tag}}` the transfer itself. A JSON value that is exactly one template keeps its JSON type. Missing paths fail the transfer without retrying.

Retries cover network errors and HTTP 408/425/429/5xx; other 4xx responses fail immediately with `statusCode` and `response` on the snapshot. Completed steps are checkpointed, so neither automatic nor manual retries repeat them. Mark a non-idempotent step with `->retryable(false)` to fail instead of repeating it automatically.

Security: URLs must be HTTPS (plain HTTP is accepted only for loopback test servers). Paths are PAM sandbox paths (`FileReference::$path`) and traversal is rejected natively. Transfer specs, step checkpoints and the secret vault are encrypted with AES-256-GCM using a non-exportable Android Keystore key and are excluded from backups; snapshots never contain secrets.

Platform support: Android API 26+ (full), iOS 15+ (0.2 single-request transfers only; 0.3 pipelines are Android-only for now), PAM Native `>=1.0.35 <2.0.0`.

### Design note: why transcoding lives in pam-native-media

PAM Native compiles each plugin as an independent Android library, so plugins cannot link against each other. Media owns the codec stack (Media3 Transformer, fast-start rewriting) and exposes one stable JVM entry point, `dev.pam.media.MediaTranscoding.transcode(...)`, kept through R8. This package binds it reflectively only when `->transcode()` is used: upload-only apps never ship Media3, there is a single transcoder implementation to maintain, and the dependency points from the generic transport to the media capability, never the other way around.

## What installation does

`pam add background-transfer` resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation.

Use `pam packages` to inspect availability and `pam remove background-transfer` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

## API guide

| API | Responsibility |
| --- | --- |
| `BackgroundTransfer` | `upload()`, `download()`, `request()`, `watch()`, `find()`, `all()`, `retry()`, `cancel()`, `prune()`. |
| `PendingTransfer` | Fluent body, headers, bearer, notification, network, retry, chain, tag, unique, transcode, `dispatch()`. |
| `HttpStep` / `Multipart` | Chained requests and streamed multipart bodies. |
| `Secret` | Vault-backed or literal credentials, encrypted at rest. |
| `TransferNotification` | Foreground progress notification (expedited work). |
| `TransferHandle` / `TransferWatch` | Persistable id, live observation, cancel and retry. |
| `TransferSnapshot` / `TransferResponse` | State, stage, bytes, attempt, last HTTP response. |
| `NetworkRequirement`, `Backoff`, `HttpMethod`, `TransferState`, `TransferStage`, `TransferKind` | Sequential integer-backed enums. |

All coded states, kinds, and variants are sequential integer-backed enums. Use enum cases in application code; do not depend on raw wire numbers.

## Tests

`composer test` runs the PHP contract suite. Android JVM tests live in `android/src/test` and instrumented tests (MockWebServer, WorkManager, cross-plugin transcode) in `android/src/androidTest`; run them from a PAM Android host that includes this plugin (and `pam-native-media` for the transcode test) with `connectedDebugAndroidTest`.

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

This package targets PAM Native `>=1.0.35 <2.0.0`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-background-transfer/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
