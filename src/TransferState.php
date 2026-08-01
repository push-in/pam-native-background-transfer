<?php
declare(strict_types=1);
namespace Pam\Native\BackgroundTransfer;
enum TransferState:int { case Queued=1; case Running=2; case Succeeded=3; case Failed=4; case Cancelled=5; }
