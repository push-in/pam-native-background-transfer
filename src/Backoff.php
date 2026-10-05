<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

/** Delay growth between automatic retries of a failed transfer attempt. */
enum Backoff: int
{
    case Linear = 1;
    case Exponential = 2;
}
