<?php
declare(strict_types=1);
namespace Pam\Native\BackgroundTransfer;
use Closure; use InvalidArgumentException; use Pam\Native\Modules\NativeModuleResult; use Pam\Native\Modules\NativeModules;
final class BackgroundTransfer {
 private const string MODULE='background-transfer';
 /** @param Closure(?string,?string):void $complete */
 public function download(string $url,string $destination,Closure $complete,NetworkRequirement $network=NetworkRequirement::Connected):int { return $this->enqueue(TransferKind::Download,$url,$destination,$complete,$network); }
 /** @param Closure(?string,?string):void $complete */
 public function upload(string $url,string $source,Closure $complete,NetworkRequirement $network=NetworkRequirement::Connected):int { return $this->enqueue(TransferKind::Upload,$url,$source,$complete,$network); }
 /** @param Closure(?TransferSnapshot):void $complete */
 public function status(string $identifier,Closure $complete):int { return NativeModules::call(self::MODULE,'status',['identifier'=>$identifier],static function(NativeModuleResult $r)use($complete):void{$v=$r->values();$complete($r->succeeded()&&isset($v['state'])?new TransferSnapshot((string)($v['identifier']??''),TransferKind::tryFrom((int)($v['kind']??1))??TransferKind::Download,TransferState::tryFrom((int)$v['state'])??TransferState::Failed,(int)($v['bytesTransferred']??0),(int)($v['bytesTotal']??0),isset($v['message'])?(string)$v['message']:null):null);}); }
 /** @param Closure(bool):void $complete */
 public function cancel(string $identifier,Closure $complete):int { return NativeModules::call(self::MODULE,'cancel',['identifier'=>$identifier],static fn(NativeModuleResult $r)=>$complete($r->succeeded())); }
 private function enqueue(TransferKind $kind,string $url,string $path,Closure $complete,NetworkRequirement $network):int { if(filter_var($url,FILTER_VALIDATE_URL)===false||!str_starts_with($url,'https://'))throw new InvalidArgumentException('Transfers require an HTTPS URL.'); if($path===''||str_contains($path,"\0"))throw new InvalidArgumentException('Transfer path is invalid.'); return NativeModules::call(self::MODULE,'enqueue',['kind'=>$kind->value,'url'=>$url,'path'=>$path,'network'=>$network->value],static function(NativeModuleResult $r)use($complete):void{$id=$r->values()['identifier']??null;$complete(is_string($id)?$id:null,$r->succeeded()?null:$r->message());}); }
}
