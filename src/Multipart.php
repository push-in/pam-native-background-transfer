<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use InvalidArgumentException;

/** A `multipart/form-data` body streamed natively from sandbox files. */
final class Multipart
{
    /** @var list<array<string, string>> */
    private array $parts = [];

    public function file(string $name, string $path, string $mimeType = 'application/octet-stream', ?string $filename = null): self
    {
        self::assertName($name);
        Validation::path($path);
        Validation::mimeType($mimeType);
        $filename ??= basename($path);
        if ($filename === '' || strlen($filename) > 255 || preg_match('/["\r\n\0\/\\\\]/', $filename) === 1) {
            throw new InvalidArgumentException('Multipart file names must be 1-255 bytes without quotes, separators or line breaks.');
        }
        $this->parts[] = ['type' => 'file', 'name' => $name, 'path' => $path, 'mimeType' => $mimeType, 'filename' => $filename];

        return $this;
    }

    public function field(string $name, string|int|float|bool $value): self
    {
        self::assertName($name);
        $value = is_bool($value) ? ($value ? '1' : '0') : (string) $value;
        if (strlen($value) > 1_048_576) {
            throw new InvalidArgumentException('Multipart fields are limited to 1 MiB.');
        }
        $this->parts[] = ['type' => 'field', 'name' => $name, 'value' => $value];

        return $this;
    }

    /** @param array<string, string|int|float|bool> $fields */
    public function fields(array $fields): self
    {
        foreach ($fields as $name => $value) {
            $this->field((string) $name, $value);
        }

        return $this;
    }

    /** @return list<array<string, string>> */
    public function parts(): array
    {
        return $this->parts;
    }

    private static function assertName(string $name): void
    {
        if ($name === '' || strlen($name) > 255 || preg_match('/["\r\n\0]/', $name) === 1) {
            throw new InvalidArgumentException('Multipart part names must be 1-255 bytes without quotes or line breaks.');
        }
    }
}
