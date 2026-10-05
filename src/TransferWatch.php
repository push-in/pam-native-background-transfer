<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Live observation of one transfer. Snapshots are delivered on change
 * (throttled natively) until the transfer finishes or `stop()` is called.
 */
final class TransferWatch
{
    private const string MODULE = 'background-transfer';

    private ?int $subscription = null;

    private bool $stopped = false;

    /** @param Closure(TransferSnapshot): void $listener */
    private function __construct(
        public readonly string $identifier,
        private readonly Closure $listener,
    ) {
    }

    /** @internal @param Closure(TransferSnapshot): void $listener */
    public static function start(string $identifier, Closure $listener): self
    {
        $watch = new self($identifier, $listener);
        NativeModules::call(self::MODULE, 'watch', ['identifier' => $identifier], static function (NativeModuleResult $result) use ($watch): void {
            if (!$result->succeeded()) {
                $watch->stopped = true;

                return;
            }
            $watch->subscription = (int) ($result->values()['subscription'] ?? 0);
            if ($watch->stopped) {
                $watch->release();

                return;
            }
            $watch->next();
        });

        return $watch;
    }

    public function stop(): void
    {
        if ($this->stopped) {
            return;
        }
        $this->stopped = true;
        $this->release();
    }

    public function active(): bool
    {
        return !$this->stopped;
    }

    private function next(): void
    {
        if ($this->stopped || $this->subscription === null) {
            return;
        }
        NativeModules::call(self::MODULE, 'watchNext', ['subscription' => $this->subscription], function (NativeModuleResult $result): void {
            if ($this->stopped) {
                return;
            }
            if (!$result->succeeded()) {
                $this->stopped = true;
                $this->release();

                return;
            }
            $snapshot = TransferSnapshot::fromWire($result->values());
            ($this->listener)($snapshot);
            if ($snapshot->finished()) {
                $this->stop();

                return;
            }
            $this->next();
        });
    }

    private function release(): void
    {
        if ($this->subscription === null) {
            return;
        }
        $subscription = $this->subscription;
        $this->subscription = null;
        NativeModules::call(self::MODULE, 'unwatch', ['subscription' => $subscription], static fn (): null => null);
    }
}
