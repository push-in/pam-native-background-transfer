<?php
declare(strict_types=1);
namespace Pam\Native\BackgroundTransfer;
enum NetworkRequirement:int { case Connected=1; case Unmetered=2; case NotRoaming=3; }
