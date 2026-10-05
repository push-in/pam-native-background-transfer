<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use JsonException;

/** HTTP response of the last executed step (bounded to 256 KiB by the worker). */
final readonly class TransferResponse
{
    public function __construct(
        public int $statusCode,
        public string $body,
    ) {
    }

    public function successful(): bool
    {
        return $this->statusCode >= 200 && $this->statusCode < 300;
    }

    public function json(): mixed
    {
        try {
            return json_decode($this->body, true, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return null;
        }
    }
}
