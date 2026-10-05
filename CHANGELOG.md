# Changelog

## 0.4.1 - 2026-10-05

### Fixed

- iOS: call the `PamMediaTranscoding` class method through the Objective-C
  class object and pass the cancel/progress blocks as objects, matching the
  pushinbr/pam-native-media 0.5 contract.

## 0.4.0 - 2026-10-05

### Added

- iOS runs 0.3 pipelines natively on a background `URLSession`: multipart,
  raw file, JSON, form and bodiless steps, `before()`/`then()` chains with
  `{{response.*}}`/`{{steps.*}}`/`{{transfer.*}}` templates and `headersFrom()`,
  per-step encrypted checkpoints, `saveTo` downloads, retries with linear or
  exponential backoff (`earliestBeginDate`), `retryable(false)`, `unique()`,
  tags, `watch()`, `all()`, `find()`, `retry()`, `cancel()` and `prune()`.
- iOS seals specs/checkpoints with AES-256-GCM (Keychain device-only key) and
  stores `Secret::vault()` values in the Keychain.
- iOS `transcode()` delegates to `pushinbr/pam-native-media` 0.5+
  (`PamMediaTranscoding`), honouring `fallbackToOriginal`.
- XCTest mirror of the Android suites (`ios/Tests`).

### Removed

- The iOS `pipelinesUnsupportedOnIOS` rejection and the 0.2 single-request iOS
  path.

### Known limitations

- iOS code is uncompiled on the release machine (no Xcode) and needs device
  validation; see the README for iOS-specific behaviour.

## 0.3.3 - 2026-10-05

### Added

- `Multipart::field($name, $value, template: false)` (and `fields(..., template: false)`)
  sends user-provided text verbatim. Previously every multipart field was
  template-resolved, so a caption containing `{{...}}` failed the transfer or
  could expand data from an earlier step response.

## 0.3.2 - 2026-10-05

### Changed

- A transfer accepts up to 64 steps (was 16), enough for signed-URL pipelines
  of large carousels (one sign and one upload step per file plus the final
  request).

### Added

- `transcode(..., fallbackToOriginal: true)` uploads the original file when the
  device cannot re-encode a video, instead of failing the transfer; the
  decision is checkpointed so resumes never re-attempt it.

## 0.3.1 - 2026-10-05

### Added

- `HttpStep::retryable(false)` marks a non-idempotent step (for example a
  `POST` that consumes a one-time upload key) as never retried automatically:
  its network and 408/425/429/5xx failures fail the transfer instead of
  repeating the request. Manual `BackgroundTransfer::retry()` still resumes
  from that step.

## 0.3.0 - 2026-10-05

### Breaking

- `BackgroundTransfer` is now a static, fluent facade. Replace
  `(new BackgroundTransfer())->upload($url, $path, $done)` with
  `BackgroundTransfer::upload($url)->put()->file($path)->dispatch($then)` and
  `->download($url, $path, $done)` with
  `BackgroundTransfer::download($url)->to($path)->dispatch($then)`.
- Paths now resolve inside the PAM file sandbox (`filesDir/pam-files`, the same
  space as `FileReference::$path`) instead of the raw application files root.
- Requires PAM Native `>=1.0.35 <2.0.0`.

### Added

- Multipart (`multipart(fn (Multipart $m) => ...)`), raw file, JSON and form
  bodies streamed natively with OkHttp and byte-accurate progress.
- Request chains executed inside the Android worker, even while PHP is
  suspended or the process was restarted: `before()` / `then()` steps with
  `{{response.*}}`, `{{steps.<name>.*}}` and `{{transfer.*}}` templates,
  `headersFrom()` for signed-URL headers, and per-step checkpoints so retries
  never repeat a completed step.
- `Secret::vault()` / `Secret::value()` credentials, `Secret::put()` /
  `Secret::forget()`; specs, checkpoints and the vault are sealed with
  AES-256-GCM using a non-exportable Android Keystore key and never appear in
  snapshots.
- `TransferNotification` makes the work expedited and runs it as a `dataSync`
  foreground service with a throttled progress bar and optional
  completion/failure notifications.
- `retry($times, Backoff, $delaySeconds)` (408/425/429/5xx and network errors
  only), `network()`, `tag()`, `unique()` de-duplication, `request()` for
  durable bodiless or JSON requests.
- `watch()` live snapshots (conflated long-poll), `all(tag:)`, `find()`,
  `retry()`, `cancel()`, `prune(olderThanDays:)`, `TransferHandle`,
  `TransferStage`, `TransferResponse` and richer `TransferSnapshot`.
- Optional `->transcode(VideoPreset::...)` pre-step delegated to
  `pushinbr/pam-native-media` 0.4+ through its stable `MediaTranscoding` entry
  point, so upload-only apps do not ship Media3.
- Android JVM unit tests and instrumented tests (MockWebServer, WorkManager,
  cross-plugin transcode).

### Known limitations

- iOS keeps the 0.2 single-request behaviour internally; 0.3 pipelines are
  rejected on iOS with `pipelinesUnsupportedOnIOS` until the URLSession port lands.

## 0.1.0 - 2026-08-01

- Initial public release of the documented PAM Native package contract.
- Add bounded input validation, sequential integer protocol enums, automated
  package tests, and PHP 8.4/8.5 continuous integration.

