<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use Closure;
use InvalidArgumentException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * A credential used by a background transfer.
 *
 * `Secret::vault('session')` references a named entry in the native transfer
 * vault and is resolved by the worker every time a step runs, so a refreshed
 * token reaches queued transfers without re-enqueueing them. `Secret::value()`
 * embeds a literal that is encrypted at rest with the transfer itself.
 * Neither form is ever written to the transfer snapshot.
 */
final readonly class Secret
{
    private const string MODULE = 'background-transfer';

    private function __construct(
        public ?string $vault,
        private ?string $literal,
    ) {
    }

    public static function vault(string $name): self
    {
        self::assertName($name);

        return new self($name, null);
    }

    public static function value(string $value): self
    {
        self::assertValue($value);

        return new self(null, $value);
    }

    /**
     * Stores or replaces a vault entry with Android Keystore / iOS Keychain backed encryption.
     *
     * @param null|Closure(bool, ?string): void $then
     */
    public static function put(string $name, string $value, ?Closure $then = null): int
    {
        self::assertName($name);
        self::assertValue($value);

        return NativeModules::call(self::MODULE, 'secretPut', ['name' => $name, 'value' => $value], static function (NativeModuleResult $result) use ($then): void {
            $then?->__invoke($result->succeeded(), $result->succeeded() ? null : $result->message());
        });
    }

    /** @param null|Closure(bool): void $then */
    public static function forget(string $name, ?Closure $then = null): int
    {
        self::assertName($name);

        return NativeModules::call(self::MODULE, 'secretForget', ['name' => $name], static function (NativeModuleResult $result) use ($then): void {
            $then?->__invoke($result->succeeded());
        });
    }

    /** @return array{vault: string}|array{value: string} */
    public function toWire(): array
    {
        return $this->vault !== null ? ['vault' => $this->vault] : ['value' => (string) $this->literal];
    }

    public function __debugInfo(): array
    {
        return ['vault' => $this->vault, 'value' => $this->literal === null ? null : '[redacted]'];
    }

    private static function assertName(string $name): void
    {
        if (preg_match('/^[A-Za-z0-9_.:-]{1,64}$/', $name) !== 1) {
            throw new InvalidArgumentException('Secret names use 1-64 letters, digits, ".", "_", ":" or "-".');
        }
    }

    private static function assertValue(string $value): void
    {
        if ($value === '' || strlen($value) > 16384 || preg_match('/[\r\n\0]/', $value) === 1) {
            throw new InvalidArgumentException('Secret values must be 1-16384 bytes without line breaks.');
        }
    }
}
