# WorkManager instantiates the worker reflectively from persisted work specs.
-keep class dev.pam.backgroundtransfer.TransferWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
