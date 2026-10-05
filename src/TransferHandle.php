<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;

/** Durable reference to a queued transfer; persist `$id` to reconnect after relaunch. */
final readonly class TransferHandle
{
    public function __construct(
        public string $id,
        public TransferKind $kind,
        public ?string $tag = null,
    ) {
    }

    /** @param Closure(TransferSnapshot): void $listener */
    public function watch(Closure $listener): TransferWatch
    {
        return BackgroundTransfer::watch($this->id, $listener);
    }

    /** @param Closure(?TransferSnapshot): void $then */
    public function status(Closure $then): int
    {
        return BackgroundTransfer::find($this->id, $then);
    }

    /** @param null|Closure(bool): void $then */
    public function cancel(?Closure $then = null): int
    {
        return BackgroundTransfer::cancel($this->id, $then);
    }

    /** @param null|Closure(bool): void $then */
    public function retry(?Closure $then = null): int
    {
        return BackgroundTransfer::retry($this->id, $then);
    }
}
