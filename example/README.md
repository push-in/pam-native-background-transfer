# Background transfer demo

A one-screen PAM Native app for `pushinbr/pam-native-background-transfer`:

- **Download** a public MP3 into the sandbox (`downloads/sample.mp3`) with a
  progress notification;
- **Upload** it back as multipart to `https://httpbin.org/post`, then chain a
  JSON request that reads the first response with `{{response.…}}`;
- **Reconcile** after a relaunch: every transfer tagged `demo` is listed with
  `BackgroundTransfer::all()`, and live ones are re-watched.

```bash
cd example
pam composer install
pam doctor --fix
pam dev            # or: pam build
```

The app installs the released package from Packagist. Start a download, close the app from the task switcher and open it
again: the transfer keeps running natively and the list shows its final state.
