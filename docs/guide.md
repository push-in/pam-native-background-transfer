# PAM Native Background Transfer: guide

> Moved out of the README; nothing was removed. Portuguese UI strings in the examples were translated to English.

### Upload a video and post the message, even if the app is closed

```php
use Pam\Native\BackgroundTransfer\{Backoff, BackgroundTransfer, HttpStep, Multipart, NetworkRequirement, Secret, TransferHandle, TransferNotification, TransferSnapshot};

Secret::put('session', $accessToken); // refresh it any time; queued transfers read the latest value

BackgroundTransfer::upload('https://api.example.com/media')
    ->multipart(fn (Multipart $m) => $m->file('file', $video->path, 'video/mp4')->field('caption', $caption, template: false))
    ->header('Idempotency-Key', $clientMessageId)
    ->bearer(Secret::vault('session'))
    ->notification(TransferNotification::make('Sending video')->progress()->failed('Upload failed'))
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
        ->notification(TransferNotification::make('Zé Chat')->text('Sending media')->channel('Uploads')
            ->progress()->failed('Could not send the media.'))
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

A runnable minimal app is in [`example/`](../example).
