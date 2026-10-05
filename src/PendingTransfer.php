<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;
use InvalidArgumentException;
use JsonException;
use LogicException;
use Pam\Native\Media\VideoPreset;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Fluent description of a durable transfer. Nothing is sent to the device
 * until `dispatch()`; afterwards the whole chain (optional transcode, the
 * main request, and every `then()` step) runs inside the native worker,
 * independent of the PHP runtime.
 */
final class PendingTransfer
{
    private const string MODULE = 'background-transfer';

    /** @var list<HttpStep> */
    private array $before = [];

    /** @var list<HttpStep> */
    private array $after = [];

    private ?TransferNotification $notification = null;

    private NetworkRequirement $network = NetworkRequirement::Connected;

    private int $retries = 3;

    private Backoff $backoff = Backoff::Exponential;

    private int $backoffSeconds = 30;

    private ?string $tag = null;

    private ?string $unique = null;

    /** @var null|array{preset: int, maxBitrate: int, fastStart: bool, fallback?: true} */
    private ?array $transcode = null;

    /** @internal */
    public function __construct(
        private readonly TransferKind $kind,
        private readonly HttpStep $step,
    ) {
    }

    public function method(HttpMethod $method): self
    {
        $this->step->method($method);

        return $this;
    }

    public function put(): self
    {
        return $this->method(HttpMethod::Put);
    }

    public function patch(): self
    {
        return $this->method(HttpMethod::Patch);
    }

    /** @param Closure(Multipart): mixed $build */
    public function multipart(Closure $build): self
    {
        $this->step->multipart($build);

        return $this;
    }

    public function file(string $path, string $mimeType = 'application/octet-stream'): self
    {
        $this->step->file($path, $mimeType);

        return $this;
    }

    /** @param array<array-key, mixed> $payload */
    public function json(array $payload): self
    {
        $this->step->json($payload);

        return $this;
    }

    /** @param array<string, string|int|float|bool> $fields */
    public function form(array $fields): self
    {
        $this->step->form($fields);

        return $this;
    }

    /** Destination sandbox path of a download. */
    public function to(string $path): self
    {
        $this->step->saveTo($path);

        return $this;
    }

    public function header(string $name, string|Secret $value): self
    {
        $this->step->header($name, $value);

        return $this;
    }

    /** @param array<string, string|Secret> $headers */
    public function headers(array $headers): self
    {
        $this->step->headers($headers);

        return $this;
    }

    public function headersFrom(string $templatePath): self
    {
        $this->step->headersFrom($templatePath);

        return $this;
    }

    public function bearer(string|Secret $token): self
    {
        $this->step->bearer($token);

        return $this;
    }

    /** Names the main step for `{{steps.<name>...}}` templates. */
    public function as(string $name): self
    {
        $this->step->as($name);

        return $this;
    }

    public function notification(TransferNotification $notification): self
    {
        $this->notification = $notification;

        return $this;
    }

    public function network(NetworkRequirement $network): self
    {
        $this->network = $network;

        return $this;
    }

    /**
     * Retries network failures, HTTP 408/425/429 and 5xx responses. Other
     * 4xx responses fail immediately. Completed steps are never repeated.
     */
    public function retry(int $times, Backoff $backoff = Backoff::Exponential, int $delaySeconds = 30): self
    {
        if ($times < 0 || $times > 20) {
            throw new InvalidArgumentException('Retry count must be between 0 and 20.');
        }
        if ($delaySeconds < 10 || $delaySeconds > 18_000) {
            throw new InvalidArgumentException('Retry delay must be between 10 seconds and 5 hours.');
        }
        $this->retries = $times;
        $this->backoff = $backoff;
        $this->backoffSeconds = $delaySeconds;

        return $this;
    }

    /** Runs before the main request, e.g. to obtain a signed upload URL. */
    public function before(HttpStep ...$steps): self
    {
        array_push($this->before, ...$steps);

        return $this;
    }

    /** Runs after the main request succeeds, inside the worker. */
    public function then(HttpStep ...$steps): self
    {
        array_push($this->after, ...$steps);

        return $this;
    }

    public function tag(string $tag): self
    {
        if (preg_match('/^[A-Za-z0-9_.:\/-]{1,128}$/', $tag) !== 1) {
            throw new InvalidArgumentException('Tags use 1-128 letters, digits, ".", "_", ":", "/" or "-".');
        }
        $this->tag = $tag;

        return $this;
    }

    /** Deduplicates dispatches: an unfinished transfer with the same key is returned instead of a new one. */
    public function unique(string $key): self
    {
        if (preg_match('/^[A-Za-z0-9_.:\/-]{1,128}$/', $key) !== 1) {
            throw new InvalidArgumentException('Unique keys use 1-128 letters, digits, ".", "_", ":", "/" or "-".');
        }
        $this->unique = $key;

        return $this;
    }

    /**
     * Re-encodes every `video/*` file of the transfer before it is sent.
     * Requires `pushinbr/pam-native-media` 0.4+, which owns the codec stack.
     * With `$fallbackToOriginal`, a file the device cannot transcode is sent
     * unchanged instead of failing the transfer.
     *
     * @param null|int<100000, 50000000> $maxBitrate
     */
    public function transcode(VideoPreset $preset, ?int $maxBitrate = null, bool $fastStart = true, bool $fallbackToOriginal = false): self
    {
        if ($maxBitrate !== null && ($maxBitrate < 100_000 || $maxBitrate > 50_000_000)) {
            throw new InvalidArgumentException('Maximum bitrate must be between 100 kbps and 50 Mbps.');
        }
        $this->transcode = ['preset' => $preset->value, 'maxBitrate' => $maxBitrate ?? 0, 'fastStart' => $fastStart]
            + ($fallbackToOriginal ? ['fallback' => true] : []);

        return $this;
    }

    /** @internal @return array<string, mixed> */
    public function toWire(): array
    {
        if ($this->kind === TransferKind::Upload && !$this->step->hasBody()) {
            throw new LogicException('An upload needs a body: call multipart(), file(), json() or form().');
        }
        if ($this->kind === TransferKind::Download && !$this->step->savesToFile()) {
            throw new LogicException('A download needs a destination: call to($path).');
        }
        $steps = array_map(static fn (HttpStep $step): array => $step->toWire(), [...$this->before, $this->step, ...$this->after]);
        if (count($steps) > 64) {
            throw new LogicException('A transfer supports at most 64 steps.');
        }

        return array_filter([
            'version' => 1,
            'kind' => $this->kind->value,
            'tag' => $this->tag,
            'unique' => $this->unique,
            'network' => $this->network->value,
            'retry' => ['times' => $this->retries, 'backoff' => $this->backoff->value, 'delaySeconds' => $this->backoffSeconds],
            'notification' => $this->notification?->toWire(),
            'transcode' => $this->transcode,
            'steps' => $steps,
        ], static fn (mixed $value): bool => $value !== null);
    }

    /**
     * Persists and schedules the transfer natively.
     *
     * @param null|Closure(TransferHandle): void $then
     * @param null|Closure(string): void $failed
     */
    public function dispatch(?Closure $then = null, ?Closure $failed = null): int
    {
        try {
            $spec = json_encode($this->toWire(), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('Transfer is not encodable: '.$error->getMessage(), previous: $error);
        }
        $kind = $this->kind;
        $tag = $this->tag;

        return NativeModules::call(self::MODULE, 'enqueue', ['spec' => $spec], static function (NativeModuleResult $result) use ($then, $failed, $kind, $tag): void {
            $id = $result->succeeded() ? ($result->values()['identifier'] ?? null) : null;
            if (is_string($id) && $id !== '') {
                $then?->__invoke(new TransferHandle($id, $kind, $tag));

                return;
            }
            $failed?->__invoke($result->succeeded() ? 'Transfer was not scheduled.' : $result->message());
        });
    }
}
