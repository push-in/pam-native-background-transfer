# PAM Native Background Transfer

Durable uploads and downloads that continue outside the PHP runtime. Android uses WorkManager `2.11.2`; iOS uses a launch-event-enabled background `URLSession`.

```bash
composer require pushinbr/pam-native-background-transfer
pam mobile codegen
pam mobile ios:prepare
```

```php
$transfers = new Pam\Native\BackgroundTransfer\BackgroundTransfer();
$transfers->download('https://cdn.example.com/movie.mp4', 'media/movie.mp4', function (?string $id, ?string $error): void {
    // Persist the id and query it after relaunch.
});
```

Transfers require HTTPS. Paths are always resolved inside the application sandbox and traversal is rejected natively. State, kind and network requirements are sequential integer-backed enums. Android retries transient failures up to three times and honors connected, unmetered, or not-roaming constraints.

Platform support: Android API 26+, iOS 15+, PAM Native 0.6.x.
