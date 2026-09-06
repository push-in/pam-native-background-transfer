<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use InvalidArgumentException;

final class TransferHeaders
{
    /** @param array<string, string> $headers */
    public static function encode(array $headers): string
    {
        if (count($headers) > 32) throw new InvalidArgumentException('Too many transfer headers');
        $seen = [];
        foreach ($headers as $name => $value) {
            if (!is_string($name) || preg_match('/^[A-Za-z0-9!#$%&\x27*+.^_`|~-]+$/D', $name) !== 1
                || !is_string($value) || preg_match('/[\x00-\x1f\x7f]/', $value) === 1) {
                throw new InvalidArgumentException('Invalid transfer header');
            }
            $key = strtolower($name);
            if (isset($seen[$key]) || in_array($key, ['host', 'content-length', 'transfer-encoding', 'connection', 'trailer', 'upgrade'], true)) {
                throw new InvalidArgumentException('Reserved or duplicate transfer header');
            }
            $seen[$key] = true;
        }
        $encoded = json_encode((object) $headers, JSON_THROW_ON_ERROR);
        if (strlen($encoded) > 4096) throw new InvalidArgumentException('Transfer headers exceed four KiB');

        return $encoded;
    }
}
