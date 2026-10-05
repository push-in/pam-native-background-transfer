<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use InvalidArgumentException;

/** @internal */
final class Validation
{
    private function __construct()
    {
    }

    /**
     * Accepts HTTPS URLs, loopback HTTP URLs used by local test servers, and
     * URLs that start with a `{{template}}` resolved natively from a previous step.
     */
    public static function url(string $url): void
    {
        if (strlen($url) > 8192 || str_contains($url, "\0") || preg_match('/\s/', $url) === 1) {
            throw new InvalidArgumentException('Transfer URL is invalid.');
        }
        if (str_starts_with($url, '{{')) {
            return;
        }
        if (filter_var($url, FILTER_VALIDATE_URL) === false) {
            throw new InvalidArgumentException('Transfer URL is invalid.');
        }
        $scheme = strtolower((string) parse_url($url, PHP_URL_SCHEME));
        $host = strtolower((string) parse_url($url, PHP_URL_HOST));
        if ($scheme === 'https' || ($scheme === 'http' && in_array($host, ['127.0.0.1', 'localhost', '[::1]'], true))) {
            return;
        }
        throw new InvalidArgumentException('Transfers require an HTTPS URL.');
    }

    /** PAM sandbox-relative path (the same space as `FileReference::$path`). */
    public static function path(string $path): void
    {
        if ($path === '' || strlen($path) > 1024 || str_contains($path, "\0") || str_starts_with($path, '/') || str_contains($path, '\\')) {
            throw new InvalidArgumentException('Transfer paths must be relative sandbox paths.');
        }
        foreach (explode('/', $path) as $segment) {
            if ($segment === '' || $segment === '.' || $segment === '..') {
                throw new InvalidArgumentException('Transfer paths must be relative sandbox paths.');
            }
        }
    }

    public static function mimeType(string $mimeType): void
    {
        if (preg_match('#^[A-Za-z0-9][A-Za-z0-9!\#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!\#$&^_.+-]{0,126}$#', $mimeType) !== 1) {
            throw new InvalidArgumentException('MIME type is invalid.');
        }
    }

    public static function headerName(string $name): void
    {
        if (preg_match('/^[A-Za-z0-9!#$%&\'*+.^_`|~-]{1,128}$/', $name) !== 1) {
            throw new InvalidArgumentException('HTTP header name is invalid.');
        }
    }

    public static function headerValue(string $value): void
    {
        if (strlen($value) > 16384 || preg_match('/[\r\n\0]/', $value) === 1) {
            throw new InvalidArgumentException('HTTP header value is invalid.');
        }
    }
}
