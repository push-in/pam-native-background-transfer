<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

/** What the native worker is doing for a running transfer. */
enum TransferStage: int
{
    case Waiting = 1;
    case Transcoding = 2;
    case Uploading = 3;
    case Requesting = 4;
    case Downloading = 5;
    case Done = 6;
}
