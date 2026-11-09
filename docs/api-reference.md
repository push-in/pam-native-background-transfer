# PAM Native Background Transfer: API reference

> Moved out of the README; nothing was removed.

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
