<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;
use InvalidArgumentException;
use JsonException;

/**
 * One HTTP request executed by the native transfer worker.
 *
 * Strings may contain `{{response.path}}` (JSON response of the previous step),
 * `{{steps.name.path}}` (response of a step named with `as()`) and
 * `{{transfer.id}}` / `{{transfer.tag}}`. A JSON value that is exactly one
 * template keeps the referenced JSON type (object, array, number...).
 */
final class HttpStep
{
    /** @var list<array<string, mixed>> */
    private array $headers = [];

    /** @var null|array<string, mixed> */
    private ?array $body = null;

    private ?string $name = null;

    private ?string $headersFrom = null;

    private ?string $saveTo = null;

    private bool $retryable = true;

    private function __construct(
        private HttpMethod $method,
        private string $url,
    ) {
        Validation::url($url);
    }

    public static function to(HttpMethod $method, string $url): self
    {
        return new self($method, $url);
    }

    public static function get(string $url): self
    {
        return new self(HttpMethod::Get, $url);
    }

    public static function post(string $url): self
    {
        return new self(HttpMethod::Post, $url);
    }

    public static function put(string $url): self
    {
        return new self(HttpMethod::Put, $url);
    }

    public static function patch(string $url): self
    {
        return new self(HttpMethod::Patch, $url);
    }

    public static function delete(string $url): self
    {
        return new self(HttpMethod::Delete, $url);
    }

    public function method(HttpMethod $method): self
    {
        $this->method = $method;

        return $this;
    }

    /** Names the step so later steps can read `{{steps.<name>.field}}`. */
    public function as(string $name): self
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/', $name) !== 1 || in_array($name, ['response', 'steps', 'transfer'], true)) {
            throw new InvalidArgumentException('Step names start with a letter and use letters, digits or "_".');
        }
        $this->name = $name;

        return $this;
    }

    public function header(string $name, string|Secret $value): self
    {
        Validation::headerName($name);
        if (is_string($value)) {
            Validation::headerValue($value);
            $this->headers[] = ['name' => $name, 'value' => $value];
        } else {
            $this->headers[] = ['name' => $name, 'secret' => $value->toWire()];
        }

        return $this;
    }

    /** @param array<string, string|Secret> $headers */
    public function headers(array $headers): self
    {
        foreach ($headers as $name => $value) {
            $this->header((string) $name, $value);
        }

        return $this;
    }

    /** Copies every string entry of a JSON object from a previous response, e.g. `steps.sign.data.headers`. */
    public function headersFrom(string $templatePath): self
    {
        if (preg_match('/^(response|steps\.[A-Za-z][A-Za-z0-9_]*)(\.[A-Za-z0-9_-]+)*$/', $templatePath) !== 1) {
            throw new InvalidArgumentException('headersFrom expects a template path such as "steps.sign.data.headers".');
        }
        $this->headersFrom = $templatePath;

        return $this;
    }

    public function bearer(string|Secret $token): self
    {
        $secret = is_string($token) ? Secret::value($token) : $token;
        $this->headers[] = ['name' => 'Authorization', 'prefix' => 'Bearer ', 'secret' => $secret->toWire()];

        return $this;
    }

    /** @param array<array-key, mixed> $payload */
    public function json(array $payload): self
    {
        try {
            json_encode($payload, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('JSON body is not encodable: '.$error->getMessage(), previous: $error);
        }
        $this->body = ['type' => 'json', 'value' => $payload === [] ? new \stdClass() : $payload];

        return $this;
    }

    /** @param array<string, string|int|float|bool> $fields */
    public function form(array $fields): self
    {
        $encoded = [];
        foreach ($fields as $name => $value) {
            $encoded[(string) $name] = is_bool($value) ? ($value ? '1' : '0') : (string) $value;
        }
        $this->body = ['type' => 'form', 'fields' => $encoded === [] ? new \stdClass() : $encoded];

        return $this;
    }

    /** @param Closure(Multipart): mixed $build */
    public function multipart(Closure $build): self
    {
        $multipart = new Multipart();
        $build($multipart);
        if ($multipart->parts() === []) {
            throw new InvalidArgumentException('A multipart body needs at least one part.');
        }
        $this->body = ['type' => 'multipart', 'parts' => $multipart->parts()];

        return $this;
    }

    /** Streams one sandbox file as the raw request body (signed-URL `PUT` uploads). */
    public function file(string $path, string $mimeType = 'application/octet-stream'): self
    {
        Validation::path($path);
        Validation::mimeType($mimeType);
        $this->body = ['type' => 'file', 'path' => $path, 'mimeType' => $mimeType];

        return $this;
    }

    /** Streams the response body into a sandbox file instead of keeping it as the step response. */
    public function saveTo(string $path): self
    {
        Validation::path($path);
        $this->saveTo = $path;

        return $this;
    }

    /**
     * Marks a non-idempotent step (e.g. a `POST` that consumes a one-time key)
     * as never retried automatically: its network and 408/425/429/5xx failures
     * fail the transfer instead. A manual `BackgroundTransfer::retry()` still
     * resumes from it.
     */
    public function retryable(bool $retryable = true): self
    {
        $this->retryable = $retryable;

        return $this;
    }

    public function hasBody(): bool
    {
        return $this->body !== null;
    }

    public function savesToFile(): bool
    {
        return $this->saveTo !== null;
    }

    /** @return list<string> */
    public function files(): array
    {
        return match ($this->body['type'] ?? null) {
            'file' => [(string) $this->body['path']],
            'multipart' => array_values(array_map(
                static fn (array $part): string => $part['path'],
                array_filter($this->body['parts'], static fn (array $part): bool => $part['type'] === 'file'),
            )),
            default => [],
        };
    }

    /** @internal @return array<string, mixed> */
    public function toWire(): array
    {
        if ($this->body !== null && $this->method === HttpMethod::Get) {
            throw new InvalidArgumentException('GET steps cannot send a request body.');
        }

        return array_filter([
            'name' => $this->name,
            'method' => $this->method->value,
            'url' => $this->url,
            'headers' => $this->headers,
            'headersFrom' => $this->headersFrom,
            'body' => $this->body,
            'saveTo' => $this->saveTo,
            'retry' => $this->retryable ? null : false,
        ], static fn (mixed $value): bool => $value !== null && $value !== []);
    }
}
