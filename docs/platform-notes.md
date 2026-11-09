# PAM Native Background Transfer: install and platform notes

> Moved out of the README; nothing was removed.

> `pam add` and `pam remove` below are legacy aliases in PAM CLI 2.2.11; use `pam composer require pushinbr/pam-native-background-transfer` and `pam composer remove pushinbr/pam-native-background-transfer`.

## What installation does

`pam add background-transfer` (or `pam composer require pushinbr/pam-native-background-transfer` followed by `pam doctor --fix`) resolves the official compatible package, performs a non-mutating Composer preflight, updates the normal `composer.json` and `composer.lock`, refreshes generated native integration when required, and leaves the project ready for `pam doctor` validation. The package is a PAM Native plugin (module `background-transfer`); nothing is added to `pam-native.json`.

Use `pam packages` to inspect availability and `pam remove background-transfer` to uninstall the capability safely. Direct Composer commands are an advanced interoperability path; PAM is the supported application workflow.

### Android

Merged permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`. The manifest merges
WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"`,
used when a transfer has a `notification()` (expedited work with a progress
notification). Request `PermissionKind::Notifications` on Android 13+ if you
want the notification to be visible; transfers run either way. Dependencies:
`androidx.work:work-runtime:2.11.2` and `com.squareup.okhttp3:okhttp:4.12.0`.
`transcode()` additionally needs `pushinbr/pam-native-media` 0.4+ installed.

### iOS

Frameworks `Security` and `UserNotifications`; no Info.plist keys or
capabilities are required (background `URLSession`s do not need a background
mode). The plugin installs the
`application(_:handleEventsForBackgroundURLSession:completionHandler:)` hook on
the host delegate. `transcode()` needs `pushinbr/pam-native-media` 0.5+.
