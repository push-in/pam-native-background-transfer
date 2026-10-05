<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

use InvalidArgumentException;

/**
 * Foreground notification shown while the transfer runs. Supplying one makes
 * the Android work expedited and promotes it to a `dataSync` foreground
 * service, so the transfer keeps running after the app leaves the screen.
 */
final class TransferNotification
{
    private ?string $text = null;

    private bool $progress = false;

    private ?string $channel = null;

    private ?string $completed = null;

    private ?string $failed = null;

    private function __construct(private readonly string $title)
    {
        self::assertText($title);
    }

    public static function make(string $title): self
    {
        return new self($title);
    }

    public function text(string $text): self
    {
        self::assertText($text);
        $this->text = $text;

        return $this;
    }

    /** Shows a determinate progress bar driven by the bytes transferred. */
    public function progress(bool $show = true): self
    {
        $this->progress = $show;

        return $this;
    }

    /** User-visible Android notification channel name (defaults to "Transfers"). */
    public function channel(string $name): self
    {
        self::assertText($name);
        $this->channel = $name;

        return $this;
    }

    /** Posts a dismissible notification when the transfer succeeds. */
    public function completed(string $title): self
    {
        self::assertText($title);
        $this->completed = $title;

        return $this;
    }

    /** Posts a dismissible notification when the transfer fails permanently. */
    public function failed(string $title): self
    {
        self::assertText($title);
        $this->failed = $title;

        return $this;
    }

    /** @internal @return array<string, string|bool> */
    public function toWire(): array
    {
        return array_filter([
            'title' => $this->title,
            'text' => $this->text,
            'progress' => $this->progress,
            'channel' => $this->channel,
            'completed' => $this->completed,
            'failed' => $this->failed,
        ], static fn (mixed $value): bool => $value !== null);
    }

    private static function assertText(string $text): void
    {
        if (trim($text) === '' || mb_strlen($text) > 200 || str_contains($text, "\0")) {
            throw new InvalidArgumentException('Notification text must contain 1-200 characters.');
        }
    }
}
