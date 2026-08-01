<?php
declare(strict_types=1);
namespace Pam\Native\BackgroundTransfer;
final readonly class TransferSnapshot {
 public function __construct(public string $identifier,public TransferKind $kind,public TransferState $state,public int $bytesTransferred=0,public int $bytesTotal=0,public ?string $message=null) {}
}
