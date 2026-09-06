<?php
declare(strict_types=1);
namespace Pam\Native\BackgroundTransfer;
use Closure; use InvalidArgumentException; use Pam\Native\Modules\NativeModuleResult; use Pam\Native\Modules\NativeModules;
final class BackgroundTransfer {
 private const string MODULE='background-transfer';
 /** @param Closure(?string,?string):void $complete */
 public function download(string $url,string $destination,Closure $complete,NetworkRequirement $network=NetworkRequirement::Connected):int { return $this->enqueue(TransferKind::Download,$url,$destination,$complete,$network); }
 /** @param Closure(?string,?string):void $complete */
 public function upload(string $url,string $source,Closure $complete,NetworkRequirement $network=NetworkRequirement::Connected,array $headers=[]):int { return $this->enqueue(TransferKind::Upload,$url,$source,$complete,$network,$headers); }
 /** @param Closure(?TransferSnapshot):void $complete */
 public function status(string $identifier, Closure $complete): int
 {
     return NativeModules::call(self::MODULE, 'status', ['identifier' => $identifier], static function (NativeModuleResult $result) use ($identifier, $complete): void {
         $values = $result->values();
         $kind = is_int($values['kind'] ?? null) ? TransferKind::tryFrom($values['kind']) : null;
         $state = is_int($values['state'] ?? null) ? TransferState::tryFrom($values['state']) : null;
         $transferred = $values['bytesTransferred'] ?? null;
         $total = $values['bytesTotal'] ?? null;
         $message = $values['message'] ?? null;
         if (!$result->succeeded() || ($values['identifier'] ?? null) !== $identifier || $identifier === ''
             || $kind === null || $state === null || !is_int($transferred) || $transferred < 0
             || !is_int($total) || $total < -1 || ($message !== null && !is_string($message))) {
             $complete(null);
             return;
         }
         $complete(new TransferSnapshot($identifier, $kind, $state, $transferred, max(0, $total), $message));
     });
 }
 /** @param Closure(bool):void $complete */
 public function cancel(string $identifier,Closure $complete):int { return NativeModules::call(self::MODULE,'cancel',['identifier'=>$identifier],static fn(NativeModuleResult $r)=>$complete($r->succeeded())); }
 private function enqueue(TransferKind $kind,string $url,string $path,Closure $complete,NetworkRequirement $network,array $headers=[]):int { if(filter_var($url,FILTER_VALIDATE_URL)===false||!str_starts_with($url,'https://'))throw new InvalidArgumentException('Transfers require an HTTPS URL.'); if($path===''||str_contains($path,"\0"))throw new InvalidArgumentException('Transfer path is invalid.'); return NativeModules::call(self::MODULE,'enqueue',['kind'=>$kind->value,'url'=>$url,'path'=>$path,'network'=>$network->value,'headers'=>TransferHeaders::encode($headers)],static function(NativeModuleResult $r)use($complete):void{$id=$r->values()['identifier']??null;if (!$r->succeeded() || !is_string($id) || trim($id) === '') {
    $complete(null, 'Could not enqueue transfer');
    return;
} $complete($id, null);}); }
}
