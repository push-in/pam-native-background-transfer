<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Durable uploads, downloads and request chains executed by the platform
 * background scheduler (WorkManager on Android, URLSession on iOS).
 *
 * ```php
 * BackgroundTransfer::upload('https://api.example.com/media')
 *     ->multipart(fn (Multipart $m) => $m->file('file', $path, 'video/mp4')->field('caption', $caption))
 *     ->bearer(Secret::vault('session'))
 *     ->notification(TransferNotification::make('Uploading video')->progress())
 *     ->then(HttpStep::post('https://api.example.com/messages')->json(['media' => '{{response.id}}']))
 *     ->tag('chat:42')
 *     ->dispatch(fn (TransferHandle $transfer) => $transfer->watch(fn (TransferSnapshot $s) => ...));
 * ```
 */
final class BackgroundTransfer
{
    private const string MODULE = 'background-transfer';

    private function __construct()
    {
    }

    /** Starts an upload whose main request is a `POST` (use `->put()` for signed URLs). */
    public static function upload(string $url): PendingTransfer
    {
        return new PendingTransfer(TransferKind::Upload, HttpStep::post($url));
    }

    /** Starts a download; set the sandbox destination with `->to($path)`. */
    public static function download(string $url): PendingTransfer
    {
        return new PendingTransfer(TransferKind::Download, HttpStep::get($url));
    }

    /** Runs an arbitrary request (and its chain) durably, e.g. a deferred `POST`. */
    public static function request(HttpStep $step): PendingTransfer
    {
        return new PendingTransfer(TransferKind::Request, $step);
    }

    /** @param Closure(TransferSnapshot): void $listener */
    public static function watch(string $id, Closure $listener): TransferWatch
    {
        self::assertIdentifier($id);

        return TransferWatch::start($id, $listener);
    }

    /** @param Closure(?TransferSnapshot): void $then */
    public static function find(string $id, Closure $then): int
    {
        self::assertIdentifier($id);

        return NativeModules::call(self::MODULE, 'status', ['identifier' => $id], static function (NativeModuleResult $result) use ($then): void {
            $values = $result->succeeded() ? $result->values() : [];
            $then(isset($values['state']) ? TransferSnapshot::fromWire($values) : null);
        });
    }

    /** @param Closure(?TransferSnapshot): void $then */
    public static function status(string $id, Closure $then): int
    {
        return self::find($id, $then);
    }

    /**
     * Lists persisted transfers, newest first, optionally filtered by tag.
     *
     * @param Closure(list<TransferSnapshot>): void $then
     */
    public static function all(Closure $then, ?string $tag = null): int
    {
        return NativeModules::call(self::MODULE, 'list', $tag === null ? [] : ['tag' => $tag], static function (NativeModuleResult $result) use ($then): void {
            $snapshots = [];
            if ($result->succeeded()) {
                try {
                    $rows = json_decode((string) ($result->values()['transfers'] ?? '[]'), true, 16, JSON_THROW_ON_ERROR);
                } catch (JsonException) {
                    $rows = [];
                }
                foreach (is_array($rows) ? $rows : [] as $row) {
                    if (is_array($row)) {
                        $snapshots[] = TransferSnapshot::fromWire(array_filter($row, static fn (mixed $value): bool => is_scalar($value)));
                    }
                }
            }
            $then($snapshots);
        });
    }

    /** Re-schedules a failed or cancelled transfer, resuming after its last completed step. */
    public static function retry(string $id, ?Closure $then = null): int
    {
        self::assertIdentifier($id);

        return NativeModules::call(self::MODULE, 'retry', ['identifier' => $id], static function (NativeModuleResult $result) use ($then): void {
            $then?->__invoke($result->succeeded());
        });
    }

    /** @param null|Closure(bool): void $then */
    public static function cancel(string $id, ?Closure $then = null): int
    {
        self::assertIdentifier($id);

        return NativeModules::call(self::MODULE, 'cancel', ['identifier' => $id], static function (NativeModuleResult $result) use ($then): void {
            $then?->__invoke($result->succeeded());
        });
    }

    /**
     * Deletes finished transfers (and their encrypted payloads) older than the given age.
     *
     * @param null|Closure(int): void $then receives the number of removed transfers
     */
    public static function prune(int $olderThanDays = 7, ?Closure $then = null): int
    {
        if ($olderThanDays < 0 || $olderThanDays > 3650) {
            throw new InvalidArgumentException('Prune age must be between 0 and 3650 days.');
        }

        return NativeModules::call(self::MODULE, 'prune', ['olderThanDays' => $olderThanDays], static function (NativeModuleResult $result) use ($then): void {
            $then?->__invoke($result->succeeded() ? (int) ($result->values()['removed'] ?? 0) : 0);
        });
    }

    private static function assertIdentifier(string $id): void
    {
        if (preg_match('/^[A-Za-z0-9-]{8,64}$/', $id) !== 1) {
            throw new InvalidArgumentException('Transfer identifier is invalid.');
        }
    }
}
