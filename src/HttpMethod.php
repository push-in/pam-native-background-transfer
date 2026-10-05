<?php

declare(strict_types=1);

namespace Pam\Native\BackgroundTransfer;

enum HttpMethod: int
{
    case Get = 1;
    case Post = 2;
    case Put = 3;
    case Patch = 4;
    case Delete = 5;

    public function verb(): string
    {
        return strtoupper($this->name);
    }
}
