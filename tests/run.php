<?php
declare(strict_types=1);
$packageAutoload = dirname(__DIR__).'/vendor/autoload.php';
if (is_file($packageAutoload)) require $packageAutoload;
$roots = [
    'Pam\\Native\\BackgroundTransfer\\' => dirname(__DIR__).'/src/',
    'Pam\\Native\\Media\\' => dirname(__DIR__, 2).'/pam-native-media/src/',
    'Pam\\Native\\Testing\\' => dirname(__DIR__, 2).'/pam-native-testing/src/',
    'Pam\\Native\\' => dirname(__DIR__, 2).'/../pam-native/packages/native/src/',
];
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) require $file;
            return;
        }
    }
});

use Pam\Native\BackgroundTransfer\Backoff;
use Pam\Native\BackgroundTransfer\BackgroundTransfer;
use Pam\Native\BackgroundTransfer\HttpMethod;
use Pam\Native\BackgroundTransfer\HttpStep;
use Pam\Native\BackgroundTransfer\Multipart;
use Pam\Native\BackgroundTransfer\NetworkRequirement;
use Pam\Native\BackgroundTransfer\Secret;
use Pam\Native\BackgroundTransfer\TransferHandle;
use Pam\Native\BackgroundTransfer\TransferKind;
use Pam\Native\BackgroundTransfer\TransferNotification;
use Pam\Native\BackgroundTransfer\TransferSnapshot;
use Pam\Native\BackgroundTransfer\TransferStage;
use Pam\Native\BackgroundTransfer\TransferState;
use Pam\Native\Internal\Wire;
use Pam\Native\Testing\NativeTestHarness;

$tests = [];
$test = static function (string $name, Closure $run) use (&$tests): void { $tests[$name] = $run; };
$assert = static function (bool $condition, string $message): void { if (!$condition) throw new RuntimeException($message); };
$spec = static function ($fake): array {
    $payload = Wire::decodeMap($fake->lastCall()->payload);
    return json_decode((string) $payload['spec'], true, 32, JSON_THROW_ON_ERROR);
};
$id = '550e8400-e29b-41d4-a716-446655440000';

$test('serializes a chained multipart upload with typed integer enums', static function () use ($assert, $spec, $id): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'enqueue', ['identifier' => $id]);
    $handle = null;
    BackgroundTransfer::upload('https://api.example.test/media')
        ->multipart(fn (Multipart $m) => $m->file('file', 'captures/clip.mov', 'video/quicktime')->field('caption', 'Olá')->field('silent', true))
        ->header('Idempotency-Key', 'k-1')
        ->bearer(Secret::vault('session'))
        ->notification(TransferNotification::make('Enviando vídeo')->text('Toque para abrir')->progress()->completed('Vídeo enviado'))
        ->network(NetworkRequirement::Unmetered)
        ->retry(5, Backoff::Linear, 60)
        ->as('upload')
        ->then(HttpStep::post('https://api.example.test/messages')->bearer(Secret::vault('session'))->json(['media' => '{{response.id}}', 'chat' => 42]))
        ->tag('chat:42')
        ->unique('message:abc')
        ->dispatch(static function (TransferHandle $h) use (&$handle): void { $handle = $h; });
    $assert($handle?->id === $id && $handle->kind === TransferKind::Upload && $handle->tag === 'chat:42', 'handle mismatch');
    $wire = $spec($fake);
    $assert($wire['kind'] === 2 && $wire['network'] === 2 && $wire['tag'] === 'chat:42' && $wire['unique'] === 'message:abc', 'transfer options mismatch');
    $assert($wire['retry'] === ['times' => 5, 'backoff' => 1, 'delaySeconds' => 60], 'retry mismatch');
    $assert($wire['notification'] === ['title' => 'Enviando vídeo', 'text' => 'Toque para abrir', 'progress' => true, 'completed' => 'Vídeo enviado'], 'notification mismatch');
    [$upload, $message] = $wire['steps'];
    $assert($upload['method'] === 2 && $upload['name'] === 'upload' && $upload['url'] === 'https://api.example.test/media', 'main step mismatch');
    $assert($upload['body']['parts'][0] === ['type' => 'file', 'name' => 'file', 'path' => 'captures/clip.mov', 'mimeType' => 'video/quicktime', 'filename' => 'clip.mov'], 'file part mismatch');
    $assert($upload['body']['parts'][2]['value'] === '1', 'bool field must be encoded as 1');
    $assert(!array_key_exists('literal', $upload['body']['parts'][1]), 'fields are template-resolved by default');
    $literal = (new Multipart())->field('body', '{{response.id}}', template: false)->fields(['a' => 'x'], template: false)->parts();
    $assert($literal[0] === ['type' => 'field', 'name' => 'body', 'value' => '{{response.id}}', 'literal' => true], 'literal field must be flagged on the wire');
    $assert(($literal[1]['literal'] ?? false) === true, 'literal fields() must flag every part');
    $assert($upload['headers'][1] === ['name' => 'Authorization', 'prefix' => 'Bearer ', 'secret' => ['vault' => 'session']], 'bearer vault mismatch');
    $assert($message['body'] === ['type' => 'json', 'value' => ['media' => '{{response.id}}', 'chat' => 42]], 'chained JSON mismatch');
    $fake->assertSatisfied();
    NativeTestHarness::uninstall();
});

$test('supports signed URL flows with before steps and response headers', static function () use ($assert, $spec, $id): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'enqueue', ['identifier' => $id]);
    BackgroundTransfer::upload('{{steps.sign.data.upload_url}}')->put()
        ->file('media/a.jpg', 'image/jpeg')
        ->headersFrom('steps.sign.data.headers')
        ->before(HttpStep::post('https://api.example.test/uploads/signed-url')->bearer('literal-token')->json(['file_name' => 'a.jpg'])->as('sign'))
        ->then(HttpStep::post('https://api.example.test/posts')->json(['media_items' => [['key' => '{{steps.sign.data.key}}']]]))
        ->dispatch();
    $wire = $spec($fake);
    $assert(count($wire['steps']) === 3 && $wire['steps'][1]['method'] === HttpMethod::Put->value, 'step order mismatch');
    $assert($wire['steps'][1]['headersFrom'] === 'steps.sign.data.headers' && $wire['steps'][1]['body']['type'] === 'file', 'signed upload mismatch');
    $assert($wire['steps'][0]['headers'][0]['secret'] === ['value' => 'literal-token'], 'literal bearer must travel as a secret');
    $assert(!array_key_exists('retry', $wire['steps'][2]), 'steps are retryable by default');
    $once = HttpStep::post('https://api.example.test/imports')->json(['key' => '{{steps.sign.data.key}}'])->retryable(false)->toWire();
    $assert($once['retry'] === false, 'non-retryable step must be flagged on the wire');
    $many = BackgroundTransfer::request(HttpStep::post('https://api.example.test/a'));
    for ($index = 1; $index < 64; $index++) {
        $many->then(HttpStep::post('https://api.example.test/a'));
    }
    $assert(count($many->toWire()['steps']) === 64, 'a transfer must accept 64 steps');
    $rejected = false;
    try {
        $many->then(HttpStep::post('https://api.example.test/a'))->toWire();
    } catch (LogicException) {
        $rejected = true;
    }
    $assert($rejected, 'more than 64 steps must be rejected');
    NativeTestHarness::uninstall();
});

$test('download and request kinds', static function () use ($assert, $spec, $id): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'enqueue', ['identifier' => $id]);
    $fake->succeed('background-transfer', 'enqueue', ['identifier' => $id]);
    BackgroundTransfer::download('https://cdn.example.test/movie.mp4')->to('downloads/movie.mp4')->dispatch();
    $download = $spec($fake);
    BackgroundTransfer::request(HttpStep::delete('https://api.example.test/drafts/1'))->dispatch();
    $request = $spec($fake);
    $assert($download['kind'] === 1 && $download['steps'][0]['saveTo'] === 'downloads/movie.mp4' && $download['steps'][0]['method'] === 1, 'download mismatch');
    $assert($request['kind'] === 3 && $request['steps'][0]['method'] === 5 && !isset($request['steps'][0]['body']), 'request mismatch');
    NativeTestHarness::uninstall();
});

$test('reports native scheduling failures', static function () use ($assert): void {
    $fake = NativeTestHarness::install();
    $fake->fail('background-transfer', 'enqueue', 'File does not exist: a.bin');
    $error = null;
    BackgroundTransfer::upload('https://x.example.test')->file('a.bin')->dispatch(static fn () => null, static function (string $message) use (&$error): void { $error = $message; });
    $assert($error === 'File does not exist: a.bin', 'failure not reported');
    NativeTestHarness::uninstall();
});

$test('rejects unsafe or incomplete transfers before the bridge', static function () use ($assert): void {
    $cases = [
        static fn () => BackgroundTransfer::upload('http://example.test/a'),
        static fn () => BackgroundTransfer::upload('https://example.test/a')->file('/etc/passwd'),
        static fn () => BackgroundTransfer::upload('https://example.test/a')->file('a/../../b'),
        static fn () => BackgroundTransfer::upload('https://example.test/a')->header("X-Bad", "a\r\nb"),
        static fn () => BackgroundTransfer::upload('https://example.test/a')->toWire(),
        static fn () => BackgroundTransfer::download('https://example.test/a')->toWire(),
        static fn () => HttpStep::get('https://example.test/a')->json(['a' => 1])->toWire(),
        static fn () => BackgroundTransfer::upload('https://example.test/a')->retry(50),
        static fn () => Secret::vault('bad name'),
        static fn () => HttpStep::post('https://example.test')->as('response'),
    ];
    foreach ($cases as $index => $case) {
        try {
            $case();
            throw new RuntimeException("unsafe case $index accepted");
        } catch (InvalidArgumentException|LogicException $error) {
            if ($error instanceof RuntimeException && str_starts_with($error->getMessage(), 'unsafe case')) throw $error;
        }
    }
    BackgroundTransfer::upload('http://127.0.0.1:8080/upload')->file('a.bin')->toWire();
    $assert(true, '');
});

$test('watches live snapshots until the transfer finishes', static function () use ($assert, $id): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'watch', ['subscription' => 7]);
    $fake->succeed('background-transfer', 'watchNext', ['identifier' => $id, 'kind' => 2, 'state' => 2, 'stage' => 3, 'bytesTransferred' => 50, 'bytesTotal' => 200, 'step' => 1, 'steps' => 2]);
    $fake->succeed('background-transfer', 'watchNext', ['identifier' => $id, 'kind' => 2, 'state' => 3, 'stage' => 6, 'bytesTransferred' => 200, 'bytesTotal' => 200, 'statusCode' => 201, 'responseBody' => '{"id":9}', 'tag' => 'chat:42']);
    $fake->succeed('background-transfer', 'unwatch');
    $seen = [];
    $watch = BackgroundTransfer::watch($id, static function (TransferSnapshot $snapshot) use (&$seen): void { $seen[] = $snapshot; });
    $assert(count($seen) === 2 && !$watch->active(), 'watch did not stop after completion');
    $assert($seen[0]->stage === TransferStage::Uploading && abs($seen[0]->progress() - 0.25) < 0.0001, 'running snapshot mismatch');
    $assert($seen[1]->state === TransferState::Succeeded && $seen[1]->response?->json() === ['id' => 9] && $seen[1]->tag === 'chat:42', 'final snapshot mismatch');
    $assert(Wire::decodeMap($fake->lastCall()->payload)['subscription'] === 7, 'unwatch subscription mismatch');
    $fake->assertSatisfied();
    NativeTestHarness::uninstall();
});

$test('lists, cancels, retries, prunes and finds transfers', static function () use ($assert, $id): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'list', ['transfers' => json_encode([['identifier' => $id, 'kind' => 2, 'state' => 4, 'message' => 'HTTP 500', 'statusCode' => 500, 'tag' => 'chat:42']])]);
    $fake->succeed('background-transfer', 'cancel');
    $fake->succeed('background-transfer', 'retry');
    $fake->succeed('background-transfer', 'prune', ['removed' => 3]);
    $fake->fail('background-transfer', 'status', 'Transfer not found');
    $list = null; $removed = null; $found = false;
    BackgroundTransfer::all(static function (array $rows) use (&$list): void { $list = $rows; }, tag: 'chat:42');
    $assert(Wire::decodeMap($fake->lastCall()->payload) === ['tag' => 'chat:42'], 'tag filter mismatch');
    BackgroundTransfer::cancel($id);
    (new TransferHandle($id, TransferKind::Upload))->retry();
    BackgroundTransfer::prune(olderThanDays: 3, then: static function (int $count) use (&$removed): void { $removed = $count; });
    $assert(Wire::decodeMap($fake->lastCall()->payload) === ['olderThanDays' => 3], 'prune payload mismatch');
    BackgroundTransfer::find($id, static function (?TransferSnapshot $snapshot) use (&$found): void { $found = $snapshot; });
    $assert(count($list) === 1 && $list[0]->state === TransferState::Failed && $list[0]->response?->statusCode === 500, 'list mismatch');
    $assert($removed === 3 && $found === null, 'prune/find mismatch');
    $fake->assertSatisfied();
    NativeTestHarness::uninstall();
});

$test('stores vault secrets without exposing them', static function () use ($assert): void {
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'secretPut');
    $ok = false;
    Secret::put('session', 'token-123', static function (bool $stored) use (&$ok): void { $ok = $stored; });
    $assert($ok && Wire::decodeMap($fake->lastCall()->payload) === ['name' => 'session', 'value' => 'token-123'], 'secret put mismatch');
    $assert(!str_contains(print_r(Secret::value('token-123'), true), 'token-123'), 'secret leaked in debug output');
    NativeTestHarness::uninstall();
});

$test('transcode step references the media preset contract', static function () use ($assert, $spec, $id): void {
    if (!enum_exists(Pam\Native\Media\VideoPreset::class)) {
        return;
    }
    $fake = NativeTestHarness::install();
    $fake->succeed('background-transfer', 'enqueue', ['identifier' => $id]);
    BackgroundTransfer::upload('https://api.example.test/media')
        ->multipart(fn (Multipart $m) => $m->file('file', 'a.mp4', 'video/mp4'))
        ->transcode(Pam\Native\Media\VideoPreset::Chat720p, maxBitrate: 1_200_000)
        ->dispatch();
    $assert($spec($fake)['transcode'] === ['preset' => Pam\Native\Media\VideoPreset::Chat720p->value, 'maxBitrate' => 1_200_000, 'fastStart' => true], 'transcode mismatch');
    $fallback = BackgroundTransfer::upload('https://api.example.test/media')->put()->file('a.mov', 'video/mp4')
        ->transcode(Pam\Native\Media\VideoPreset::Adaptive, fallbackToOriginal: true)->toWire();
    $assert(($fallback['transcode']['fallback'] ?? false) === true, 'transcode fallback must travel on the wire');
    NativeTestHarness::uninstall();
});

$test('coded variants are sequential integers and match the IDL', static function () use ($assert): void {
    $idl = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.idl.json'), true, 16, JSON_THROW_ON_ERROR);
    foreach ($idl['enums'] as $name => $cases) {
        $class = 'Pam\\Native\\BackgroundTransfer\\'.$name;
        $actual = [];
        foreach ($class::cases() as $case) $actual[$case->name] = $case->value;
        $assert($actual === $cases, "$name differs from IDL");
        $assert(array_values($cases) === range(1, count($cases)), "$name is not sequential");
    }
});

$failed = 0;
foreach ($tests as $name => $run) {
    try { $run(); fwrite(STDOUT, "PASS $name\n"); } catch (Throwable $e) { $failed++; fwrite(STDERR, "FAIL $name: {$e->getMessage()} @ {$e->getFile()}:{$e->getLine()}\n"); }
}
fwrite(STDOUT, count($tests)." tests, $failed failures\n");
exit($failed ? 1 : 0);
