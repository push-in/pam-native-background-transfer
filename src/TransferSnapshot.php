<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

final readonly class TransferSnapshot
{
    public function __construct(
        public string $identifier,
        public TransferKind $kind,
        public TransferState $state,
        public int $bytesTransferred = 0,
        public int $bytesTotal = 0,
        public ?string $message = null,
        public TransferStage $stage = TransferStage::Waiting,
        public int $step = 0,
        public int $steps = 0,
        public int $attempt = 0,
        public ?string $tag = null,
        public ?TransferResponse $response = null,
        public int $createdAt = 0,
        public int $updatedAt = 0,
    ) {
    }

    /** @param array<string, string|int|float|bool> $values */
    public static function fromWire(array $values): self
    {
        $text = static fn (string $key): ?string => isset($values[$key]) && (string) $values[$key] !== '' ? (string) $values[$key] : null;
        $status = (int) ($values['statusCode'] ?? 0);

        return new self(
            identifier: (string) ($values['identifier'] ?? ''),
            kind: TransferKind::tryFrom((int) ($values['kind'] ?? 0)) ?? TransferKind::Upload,
            state: TransferState::tryFrom((int) ($values['state'] ?? 0)) ?? TransferState::Failed,
            bytesTransferred: max(0, (int) ($values['bytesTransferred'] ?? 0)),
            bytesTotal: max(0, (int) ($values['bytesTotal'] ?? 0)),
            message: $text('message'),
            stage: TransferStage::tryFrom((int) ($values['stage'] ?? 0)) ?? TransferStage::Waiting,
            step: max(0, (int) ($values['step'] ?? 0)),
            steps: max(0, (int) ($values['steps'] ?? 0)),
            attempt: max(0, (int) ($values['attempt'] ?? 0)),
            tag: $text('tag'),
            response: $status > 0 ? new TransferResponse($status, (string) ($values['responseBody'] ?? '')) : null,
            createdAt: (int) ($values['createdAt'] ?? 0),
            updatedAt: (int) ($values['updatedAt'] ?? 0),
        );
    }

    /** Fraction from 0.0 to 1.0; stages without byte totals report 0 until success. */
    public function progress(): float
    {
        if ($this->state === TransferState::Succeeded) {
            return 1.0;
        }

        return $this->bytesTotal > 0 ? min(1.0, $this->bytesTransferred / $this->bytesTotal) : 0.0;
    }

    public function finished(): bool
    {
        return $this->state->finished();
    }
}
