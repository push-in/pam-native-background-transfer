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
    ->multipart(fn (Multipart $m) => $m->file('file', $video->path, 'video/mp4')->field('caption', $caption, template: false))
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

Multipart fields built from user text should use `->field($name, $value, template: false)`, which sends the value verbatim. Templates: `{{response.a.b}}` reads the previous step's JSON response, `{{steps.<name>.a.0.b}}` a step named with `as()`, and `{{transfer.id}}` / `{{transfer.tag}}` the transfer itself. A JSON value that is exactly one template keeps its JSON type. Missing paths fail the transfer without retrying.

Retries cover network errors and HTTP 408/425/429/5xx; other 4xx responses fail immediately with `statusCode` and `response` on the snapshot. Completed steps are checkpointed, so neither automatic nor manual retries repeat them. Mark a non-idempotent step with `->retryable(false)` to fail instead of repeating it automatically.

Security: URLs must be HTTPS (plain HTTP is accepted only for loopback test servers). Paths are PAM sandbox paths (`FileReference::$path`) and traversal is rejected natively. Transfer specs, step checkpoints and the secret vault are encrypted with AES-256-GCM using a non-exportable Android Keystore key and are excluded from backups; snapshots never contain secrets.

Platform support: Android API 26+ and iOS 15+ (full pipelines on both), PAM Native `>=1.0.35 <2.0.0`.

On iOS every step runs on a background `URLSession` (uploads from a body file
next to the encrypted spec, downloads to the sandbox), so transfers continue
while the app is suspended or terminated and iOS relaunches the app to chain
the next step; the plugin registers
`application(_:handleEventsForBackgroundURLSession:completionHandler:)` on the
host delegate automatically. Specs and checkpoints are sealed with AES-256-GCM
using a device-only Keychain key, `Secret::vault()` values are Keychain items,
and retries wait out the backoff with `earliestBeginDate` while suspended.
iOS limitations: there is no foreground-service progress notification (the
`completed`/`failed` texts of `TransferNotification` are posted as local
notifications), `network()` maps `Unmetered` to no cellular/expensive networks
and treats `NotRoaming` as connected, and the optional `transcode()` pre-step
needs CPU time, so it runs while the app is running (protected by a background
task) and resumes on the next launch if iOS suspends the app. The iOS
implementation has not been validated on a device yet; see
`ios/Tests/BackgroundTransferTests.swift`.

### Design note: why transcoding lives in pam-native-media

PAM Native compiles each plugin as an independent Android library, so plugins cannot link against each other. Media owns the codec stack (Media3 Transformer, fast-start rewriting) and exposes one stable JVM entry point, `dev.pam.media.MediaTranscoding.transcode(...)`, kept through R8. This package binds it reflectively only when `->transcode()` is used: upload-only apps never ship Media3, there is a single transcoder implementation to maintain, and the dependency points from the generic transport to the media capability, never the other way around.

## What installation does

`pam add background-transfer` (or `pam composer require pushinbr/pam-native-background-transfer` followed by `pam doctor --fix`) resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation. The package is a PAM Native plugin (module `background-transfer`); nothing is added to `pam-native.json`.

Use `pam packages` to inspect availability and `pam remove background-transfer` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

### Android

Merged permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`. The manifest merges
WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"`,
used when a transfer has a `notification()` (expedited work with a progress
notification). Request `PermissionKind::Notifications` on Android 13+ if you
want the notification to be visible; transfers run either way. Dependencies:
`androidx.work:work-runtime:2.11.2` and `com.squareup.okhttp3:okhttp:4.12.0`.
`transcode()` additionally needs `pushinbr/pam-native-media` 0.4+ installed.

### iOS

Frameworks `Security` and `UserNotifications`; no Info.plist keys or
capabilities are required (background `URLSession`s do not need a background
mode). The plugin installs the
`application(_:handleEventsForBackgroundURLSession:completionHandler:)` hook on
the host delegate. `transcode()` needs `pushinbr/pam-native-media` 0.5+.

## A real example: Zé Chat

Zé Chat sends every photo, video and album message as one durable transfer.
The outbox row stores the transfer id, so after a crash or relaunch the screen
re-attaches instead of uploading twice:

```php
use Pam\Native\BackgroundTransfer\{Backoff, BackgroundTransfer, Multipart, Secret, TransferHandle, TransferNotification, TransferSnapshot, TransferState};
use Pam\Native\Media\VideoPreset;

// The access token lives in the encrypted vault; queued transfers read the latest value.
Secret::put('session', $accessToken, function (bool $stored) use ($accessToken, $record): void {
    $auth = $stored ? Secret::vault('session') : Secret::value($accessToken);

    $transfer = BackgroundTransfer::upload("https://api.example.com/chats/{$record->chatId}/messages")
        ->multipart(function (Multipart $m) use ($record): void {
            $m->field('body', $record->body, template: false)            // user text: never templated
              ->field('idempotency_key', $record->idempotencyKey, template: false)
              ->field('type', 3);
            foreach ($record->files as $file) {
                $m->file('media_files[]', $file['path'], $file['mimeType'], $file['fileName']);
            }
        })
        ->header('Accept', 'application/json')
        ->bearer($auth)
        ->notification(TransferNotification::make('Zé Chat')->text('Enviando mídia')->channel('Envios')
            ->progress()->failed('Não foi possível enviar a mídia.'))
        ->retry(3, Backoff::Exponential)
        ->unique('chat-media:'.hash('sha256', $record->idempotencyKey))   // a double tap returns the same transfer
        ->tag("chat:{$record->chatId}");

    if ($record->hasVideoToCompress) {
        // H.264/AAC fast start, ≤1080p (720p for long videos); devices that cannot encode send the original.
        $transfer->transcode(VideoPreset::Adaptive, fastStart: true, fallbackToOriginal: true);
    }

    $transfer->dispatch(
        function (TransferHandle $handle) use ($record): void {
            $record->transferId = $handle->id;       // persist before following it
            $this->outbox->save($record);
            $handle->watch(fn (TransferSnapshot $s) => $s->finished()
                ? $this->settle($record, $s->state, $s->response?->statusCode ?? 0, $s->response?->body ?? $s->message ?? '')
                : $this->progress($record, (int) round($s->progress() * 100)));
        },
        fn (string $error) => $this->settle($record, TransferState::Failed, 0, $error),
    );
});

// On relaunch: re-attach, deliver a result that finished while the app was dead, or resume.
BackgroundTransfer::find($record->transferId, function (?TransferSnapshot $s) use ($record): void {
    match (true) {
        $s === null => $this->send($record),                         // unknown id: dispatch again (server dedupes)
        !$s->finished() => $this->follow($record),
        $s->state === TransferState::Succeeded => $this->settle($record, $s->state, $s->response?->statusCode ?? 0, $s->response?->body ?? ''),
        default => BackgroundTransfer::retry($s->identifier),         // resumes after the last completed step
    };
});

BackgroundTransfer::prune(olderThanDays: 7);                        // once per launch
```

A runnable minimal app is in [`example/`](example).

## API reference

All classes live in `Pam\Native\BackgroundTransfer`. Native calls return the
module request id (`int`).

### `BackgroundTransfer` (module `background-transfer`)

| Method | Description |
| --- | --- |
| `upload(string $url): PendingTransfer` | Upload; needs a body (`multipart()`, `file()`, `json()` or `form()`). Defaults to `POST`. |
| `download(string $url): PendingTransfer` | Download; needs `to($path)`. |
| `request(HttpStep $step): PendingTransfer` | A durable request chain starting with `$step`. |
| `watch(string $id, Closure(TransferSnapshot) $listener): TransferWatch` | Live snapshots pushed natively until the transfer finishes or `stop()`. |
| `find(string $id, Closure(?TransferSnapshot) $then)` / `status()` | Durable state; `null` when the id is unknown. |
| `all(Closure(list<TransferSnapshot>) $then, ?string $tag = null)` | Every stored transfer, optionally by tag. |
| `retry(string $id, ?Closure(bool) $then = null)` | Resumes a failed/cancelled transfer after its last completed step. |
| `cancel(string $id, ?Closure(bool) $then = null)` | Cancels; watchers receive `Cancelled`. |
| `prune(int $olderThanDays = 7, ?Closure(int) $then = null)` | Deletes finished transfers older than 0–3650 days; `$then` receives the number removed. |

### `PendingTransfer` (fluent, `dispatch()` sends it)

| Method | Description |
| --- | --- |
| `method(HttpMethod)`, `put()`, `patch()` | HTTP method of the main step. |
| `multipart(Closure(Multipart))`, `file(string $path, string $mimeType)`, `json(array)`, `form(array)` | Body (uploads stream from disk). |
| `to(string $path)` | Download destination (sandbox path). |
| `header(string, string\|Secret)`, `headers(array)`, `headersFrom(string $templatePath)`, `bearer(string\|Secret)` | Headers; `headersFrom('steps.sign.data.headers')` copies a JSON object from an earlier step. |
| `as(string $name)` | Names the main step for `{{steps.<name>…}}`. |
| `before(HttpStep ...$steps)`, `then(HttpStep ...$steps)` | Steps before/after the main step (64 in total). |
| `notification(TransferNotification)` | Android foreground notification; iOS local notifications for `completed`/`failed`. |
| `network(NetworkRequirement)` | `Connected` (default), `Unmetered`, `NotRoaming`. |
| `retry(int $times, Backoff $backoff = Exponential, int $delaySeconds = 30)` | 0–20 retries, delay 10 s–5 h. |
| `tag(string)`, `unique(string $key)` | Grouping; `unique()` returns the unfinished transfer with the same key instead of a new one. |
| `transcode(VideoPreset $preset, ?int $maxBitrate = null, bool $fastStart = true, bool $fallbackToOriginal = false)` | Re-encode the video file(s) first (needs `pam-native-media`); bitrate 100 kbps–50 Mbps. |
| `dispatch(?Closure(TransferHandle) $then = null, ?Closure(string) $failed = null): int` | Persists and schedules the transfer. |
| `toWire(): array` | Encoded spec. |

### `HttpStep`

`to(HttpMethod, string $url)`, `get()`, `post()`, `put()`, `patch()`,
`delete()`; `method()`, `as(string $name)`, `header()`, `headers()`,
`headersFrom()`, `bearer()`, `json(array)`, `form(array)`,
`multipart(Closure)`, `file(string $path, string $mimeType)`,
`saveTo(string $path)` (write the response body to a file),
`retryable(bool $retryable = true)`; `hasBody()`, `savesToFile()`, `files()`,
`toWire()`. `GET` steps cannot have a body.

### `Multipart`

`file(string $name, string $path, string $mimeType = 'application/octet-stream', ?string $filename = null)`,
`field(string $name, string|int|float|bool $value, bool $template = true)`,
`fields(array $fields, bool $template = true)`, `parts()`. Fields are limited
to 1 MiB; pass `template: false` for user text.

### `Secret`

`vault(string $name)` (resolved natively when the step runs),
`value(string $value)` (literal, encrypted inside the spec),
`put(string $name, string $value, ?Closure(bool, ?string) $then = null)`,
`forget(string $name, ?Closure(bool) $then = null)`, `toWire()`.
`__debugInfo()` hides the value. Names: 1–64 of `[A-Za-z0-9._:-]`; values:
1–16384 bytes without line breaks.

### `TransferNotification`

`make(string $title)`, `text(string)`, `progress(bool $show = true)`,
`channel(string $name)` (Android channel name, default "Transfers"),
`completed(string $title)`, `failed(string $title)`, `toWire()`. Texts are
1–200 characters.

### `TransferHandle`, `TransferWatch`

`TransferHandle` (readonly `id`, `kind`, `tag`): `watch()`, `status()`,
`cancel()`, `retry()`. `TransferWatch` (readonly `identifier`): `stop()`,
`active()`. Persist `TransferHandle::$id`; handles are not restored after a
relaunch.

### `TransferSnapshot`, `TransferResponse` (readonly)

`TransferSnapshot`: `identifier`, `kind`, `state`, `bytesTransferred`,
`bytesTotal`, `message` (error text), `stage`, `step`, `steps`, `attempt`,
`tag`, `response` (last HTTP response), `createdAt`, `updatedAt` (Unix ms);
`progress(): float` (0–1, `1.0` when succeeded), `finished(): bool`.
`TransferResponse`: `statusCode`, `body` (first 256 KiB on Android),
`successful()`, `json()` (`null` when not JSON).

### Enums (int-backed)

| Enum | Cases |
| --- | --- |
| `TransferState` | `Queued = 1`, `Running`, `Succeeded`, `Failed`, `Cancelled`, `Retrying = 6`; `finished()` |
| `TransferStage` | `Waiting = 1`, `Transcoding`, `Uploading`, `Requesting`, `Downloading`, `Done = 6` |
| `TransferKind` | `Download = 1`, `Upload = 2`, `Request = 3` |
| `NetworkRequirement` | `Connected = 1`, `Unmetered = 2`, `NotRoaming = 3` |
| `Backoff` | `Linear = 1`, `Exponential = 2` |
| `HttpMethod` | `Get = 1`, `Post`, `Put`, `Patch`, `Delete = 5`; `verb()` |

### Errors

Builders throw `InvalidArgumentException` for non-HTTPS or invalid URLs,
absolute or traversal paths, transfer ids that do not match
`[A-Za-z0-9-]{8,64}` (`watch()`, `find()`, `retry()`, `cancel()`), invalid header names/values or MIME types,
invalid secret, tag, unique-key and step names, oversized multipart fields,
notification texts outside 1–200 characters, retry counts outside 0–20,
delays outside 10 s–5 h, `prune()` ages outside 0–3650 days, a body on `GET`
and unencodable JSON. `dispatch()` throws `LogicException` for an upload
without a body, a download without `to()` and more than 64 steps. Runtime
failures never throw: `dispatch()` reports `$failed(string)` (for example a
missing source file) and finished transfers carry `state = Failed`,
`message` and the last `response`.

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
- **A watch goes silent:** a watch ends when the native module restarts; re-read the durable state with `BackgroundTransfer::find()` (Zé Chat re-checks after 30 s without a snapshot).
- **A 4xx response is not retried:** only network errors and HTTP 408/425/429/5xx are retried; inspect `TransferSnapshot::$response`.
- **A template path is missing:** the transfer fails without retrying; check `{{response.…}}`/`{{steps.<name>.…}}` against the real JSON.
- **Native integration is stale:** run `pam doctor --fix`, rebuild the native host, and inspect the first reported diagnostic.

## Compatibility and support

| `pushinbr/pam-native-background-transfer` | `pushinbr/pam-native` | Android | iOS |
| --- | --- | --- | --- |
| 0.4.x | `>=1.0.35 <2.0.0` (tested with 1.14.x) | API 26+ | 15+, full pipelines (`transcode()` with `pam-native-media` 0.5+) |
| 0.3.x | `>=1.0.35 <2.0.0` | API 26+ | Single requests only |

This package targets PAM Native `>=1.0.35 <2.0.0`, Android API 26+, and iOS 15+ unless a platform-specific section above states a stricter requirement. Platform SDKs, credentials, entitlements, physical hardware, and store configuration remain application responsibilities.

- [PAM documentation](https://push-in.github.io/pam-docs/introduction/)
- [PAM Native overview](https://push-in.github.io/pam-docs/native/overview/)
- [Plugin and native capability model](https://push-in.github.io/pam-docs/native/plugins/)
- [Report an issue](https://github.com/push-in/pam-native-background-transfer/issues)

Security vulnerabilities should be reported through the repository security policy or GitHub private vulnerability reporting, not a public issue.
