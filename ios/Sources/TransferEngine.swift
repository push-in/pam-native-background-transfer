import Foundation
import PamNative
import UIKit
import UserNotifications

/// Persistent registry of public transfer records with live listeners.
/// Progress is published at most every 200 ms and persisted at most once per
/// second; lifecycle changes are always persisted and published.
final class TransferStore: @unchecked Sendable {
    static let shared = TransferStore()
    private let key = "dev.pam.background-transfer.v3"
    private let lock = NSLock()
    private var records: [String: TransferRecord] = [:]
    private var listeners: [String: [UUID: (TransferRecord) -> Void]] = [:]
    private var lastPublished: [String: TimeInterval] = [:]
    private var lastPersisted: [String: TimeInterval] = [:]

    init() {
        for (id, value) in UserDefaults.standard.dictionary(forKey: key) ?? [:] {
            if let data = value as? Data, let record = try? JSONDecoder().decode(TransferRecord.self, from: data) {
                records[id] = record
            }
        }
    }

    func get(_ id: String) -> TransferRecord? {
        lock.lock()
        defer { lock.unlock() }
        return records[id]
    }

    func all(tag: String? = nil) -> [TransferRecord] {
        lock.lock()
        defer { lock.unlock() }
        return records.values.filter { tag == nil || $0.tag == tag }.sorted { $0.createdAt > $1.createdAt }
    }

    func findUnfinished(unique: String) -> TransferRecord? {
        all().first { $0.unique == unique && !TransferStates.finished($0.state) }
    }

    func put(_ record: TransferRecord) {
        lock.lock()
        records[record.id] = record
        lock.unlock()
        persist(record)
        publish(record)
    }

    @discardableResult
    func update(_ id: String, _ change: (inout TransferRecord) -> Void) -> TransferRecord? {
        lock.lock()
        guard var record = records[id] else {
            lock.unlock()
            return nil
        }
        change(&record)
        record.updatedAt = TransferRecord.now()
        records[id] = record
        lock.unlock()
        persist(record)
        publish(record)
        return record
    }

    func progress(_ id: String, transferred: Int64, total: Int64) {
        lock.lock()
        guard var record = records[id], !TransferStates.finished(record.state) else {
            lock.unlock()
            return
        }
        record.transferred = transferred
        record.total = total
        record.updatedAt = TransferRecord.now()
        records[id] = record
        let now = Date().timeIntervalSince1970
        let shouldPersist = now - (lastPersisted[id] ?? 0) >= 1
        let shouldPublish = now - (lastPublished[id] ?? 0) >= 0.2 || (total > 0 && transferred >= total)
        if shouldPersist { lastPersisted[id] = now }
        lock.unlock()
        if shouldPersist { persist(record) }
        if shouldPublish { publish(record) }
    }

    func remove(_ id: String) {
        lock.lock()
        records[id] = nil
        lastPersisted[id] = nil
        lastPublished[id] = nil
        var stored = UserDefaults.standard.dictionary(forKey: key) ?? [:]
        stored[id] = nil
        UserDefaults.standard.set(stored, forKey: key)
        lock.unlock()
    }

    func observe(_ id: String, _ listener: @escaping (TransferRecord) -> Void) -> () -> Void {
        let token = UUID()
        lock.lock()
        listeners[id, default: [:]][token] = listener
        lock.unlock()
        return { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.listeners[id]?[token] = nil
            self.lock.unlock()
        }
    }

    private func persist(_ record: TransferRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        lock.lock()
        var stored = UserDefaults.standard.dictionary(forKey: key) ?? [:]
        stored[record.id] = data
        UserDefaults.standard.set(stored, forKey: key)
        lock.unlock()
    }

    private func publish(_ record: TransferRecord) {
        lock.lock()
        lastPublished[record.id] = Date().timeIntervalSince1970
        let targets = Array((listeners[record.id] ?? [:]).values)
        lock.unlock()
        targets.forEach { $0(record) }
    }
}

/// Encrypted spec and checkpoint files of one transfer.
enum TransferFiles {
    static func writeSpec(_ id: String, _ json: String) throws {
        try TransferCrypto.writeFile(TransferPaths.workDirectory(id).appendingPathComponent("spec.enc"), json)
    }

    static func readSpec(_ id: String) throws -> TransferSpec {
        try TransferSpec.parse(TransferCrypto.readFile(TransferPaths.workDirectory(id).appendingPathComponent("spec.enc")))
    }

    static func hasSpec(_ id: String) -> Bool {
        (try? TransferPaths.workDirectory(id).appendingPathComponent("spec.enc")).map {
            FileManager.default.fileExists(atPath: $0.path)
        } ?? false
    }

    static func writeProgress(_ id: String, _ progress: TransferProgress) throws {
        let json = String(decoding: try JSONEncoder().encode(progress), as: UTF8.self)
        try TransferCrypto.writeFile(TransferPaths.workDirectory(id).appendingPathComponent("progress.enc"), json)
    }

    static func readProgress(_ id: String) -> TransferProgress {
        guard let file = try? TransferPaths.workDirectory(id).appendingPathComponent("progress.enc"),
              let json = try? TransferCrypto.readFile(file),
              let progress = try? JSONDecoder().decode(TransferProgress.self, from: Data(json.utf8)) else {
            return TransferProgress()
        }
        return progress
    }

    static func delete(_ id: String) {
        if let directory = try? TransferPaths.workDirectory(id) {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}

/// Optional bridge to `pushinbr/pam-native-media` 0.4+ on iOS: the media
/// plugin exposes the Objective-C class `PamMediaTranscoding` with
/// `+transcode:` taking/returning a dictionary, so apps that only upload do
/// not link AVFoundation export code.
enum MediaTranscoderBridge {
    private static let selector = NSSelectorFromString("transcode:")

    private static var entry: AnyObject? {
        guard let type = NSClassFromString("PamMediaTranscoding") else { return nil }
        let object = type as AnyObject
        return object.responds(to: selector) ? object : nil
    }

    static var available: Bool { entry != nil }

    /// Synchronous; returns media's `TranscodeResult` dictionary.
    static func transcode(
        source: URL,
        destination: URL,
        options: [String: Any],
        cancelled: @escaping () -> Bool,
        progress: @escaping (Double) -> Void
    ) throws -> [String: Any] {
        guard let type = entry else {
            throw TransferFailure(message: "Video transcoding requires pushinbr/pam-native-media 0.4 or newer", retryable: false)
        }
        let optionsJson = (try? JSONSerialization.data(withJSONObject: options)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        let cancelledBlock: @convention(block) () -> Bool = { cancelled() }
        let progressBlock: @convention(block) (Double) -> Void = { progress($0) }
        let request: NSDictionary = [
            "source": source.path,
            "destination": destination.path,
            "options": optionsJson,
            "cancelled": unsafeBitCast(cancelledBlock, to: AnyObject.self),
            "progress": unsafeBitCast(progressBlock, to: AnyObject.self),
        ]
        guard let result = type.perform(selector, with: request)?.takeUnretainedValue() as? [String: Any] else {
            throw TransferFailure(message: "Video transcoding failed", retryable: false)
        }
        if let error = result["error"] as? String {
            throw TransferFailure(message: "Video transcoding failed: \(error)", retryable: false)
        }
        return result
    }
}

/// Executes persisted transfers on a background URLSession: every step is an
/// upload (body written to a file) or download task, so the system keeps
/// transferring while the app is suspended or terminated and relaunches it
/// to chain the next step. Completed steps are checkpointed (encrypted).
final class TransferEngine: NSObject, URLSessionDataDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = TransferEngine()
    static let sessionIdentifier = "dev.pam.background-transfer.v3"
    static let maxResponseBytes = 1_024 * 1_024

    let store = TransferStore.shared
    private let queue = DispatchQueue(label: "dev.pam.background-transfer.engine", qos: .utility)
    private var bodies: [Int: Data] = [:]
    private var downloads: [Int: (status: Int, body: String)] = [:]
    private var stepBase: [String: Int64] = [:]
    private var stepTotals: [String: Int64] = [:]
    private var transcoding: Set<String> = []
    private var backgroundCompletion: (() -> Void)?
    private var resumed = false

    private(set) lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.timeoutIntervalForResource = 7 * 24 * 60 * 60
        configuration.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    // MARK: Lifecycle

    /// Reconnects to the background session and restarts unfinished
    /// transfers whose task is gone (e.g. the app was killed mid-transcode).
    func resumeOnLaunch() {
        queue.async {
            guard !self.resumed else { return }
            self.resumed = true
            self.session.getAllTasks { tasks in
                let active = Set(tasks.compactMap { TaskTag.parse($0.taskDescription)?.id })
                self.queue.async {
                    for record in self.store.all() where !TransferStates.finished(record.state) && !active.contains(record.id) {
                        self.start(record.id)
                    }
                }
            }
        }
    }

    func backgroundEvents(identifier: String, completion: @escaping () -> Void) {
        guard identifier == Self.sessionIdentifier else {
            completion()
            return
        }
        queue.async {
            self.backgroundCompletion = completion
            _ = self.session
        }
    }

    // MARK: API

    func enqueue(_ json: String) throws -> TransferRecord {
        let spec = try TransferSpec.parse(json)
        if let unique = spec.unique, let existing = store.findUnfinished(unique: unique) { return existing }
        var total: Int64 = 0
        for path in Set(spec.files) {
            let file = try TransferPaths.resolve(path, mustExist: true)
            total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        if spec.transcode != nil && !MediaTranscoderBridge.available {
            throw TransferError("Video transcoding requires pushinbr/pam-native-media 0.4 or newer")
        }
        let id = UUID().uuidString.lowercased()
        try TransferFiles.writeSpec(id, json)
        let record = TransferRecord(id: id, kind: spec.kind, steps: spec.steps.count, total: total, tag: spec.tag, unique: spec.unique)
        store.put(record)
        queue.async { self.start(id) }
        return record
    }

    func cancel(_ id: String) throws {
        guard let record = store.get(id) else { throw TransferError("Transfer not found") }
        if !TransferStates.finished(record.state) {
            store.update(id) {
                $0.state = TransferStates.cancelled
                $0.stage = TransferStages.done
                $0.message = "Cancelled"
            }
        }
        session.getAllTasks { tasks in
            tasks.filter { TaskTag.parse($0.taskDescription)?.id == id }.forEach { $0.cancel() }
        }
    }

    func retry(_ id: String) throws -> TransferRecord {
        guard let record = store.get(id) else { throw TransferError("Transfer not found") }
        guard record.state == TransferStates.failed || record.state == TransferStates.cancelled else {
            throw TransferError("Only failed or cancelled transfers can be retried")
        }
        guard TransferFiles.hasSpec(id) else { throw TransferError("Transfer payload is no longer available") }
        store.update(id) {
            $0.state = TransferStates.queued
            $0.stage = TransferStages.waiting
            $0.attempt = 0
            $0.message = ""
            $0.statusCode = 0
            $0.responseBody = ""
        }
        queue.async { self.start(id) }
        return record
    }

    func prune(olderThanDays days: Int64) -> Int {
        let cutoff = TransferRecord.now() - min(max(days, 0), 3_650) * 86_400_000
        let removed = store.all().filter { TransferStates.finished($0.state) && $0.updatedAt <= cutoff }
        for record in removed {
            TransferFiles.delete(record.id)
            store.remove(record.id)
        }
        return removed.count
    }

    // MARK: Execution (engine queue)

    private func start(_ id: String) {
        guard let record = store.get(id), !TransferStates.finished(record.state) else { return }
        let spec: TransferSpec
        do {
            spec = try TransferFiles.readSpec(id)
        } catch {
            fail(id, spec: nil, TransferFailure(message: "Transfer payload is unavailable", retryable: false))
            return
        }
        var progress = TransferFiles.readProgress(id)
        if let options = spec.transcode {
            let pending = spec.videoFiles.filter { path in
                guard let output = progress.transcoded[path] else { return true }
                return !output.isEmpty && !FileManager.default.fileExists(atPath: output)
            }
            if !pending.isEmpty {
                transcode(id, spec: spec, options: options, pending: pending, progress: progress)
                return
            }
        }
        progress = TransferFiles.readProgress(id)
        store.update(id) {
            $0.state = $0.attempt == 0 ? TransferStates.running : TransferStates.retrying
            $0.attempt = max($0.attempt, 1)
            $0.message = ""
        }
        stepBase[id] = 0
        stepTotals[id] = spec.steps.dropFirst(progress.next).reduce(0) { $0 + uploadSize($1, progress: progress) }
        launch(id, spec: spec, index: progress.next, delay: 0)
    }

    /// Transcoding needs CPU, which iOS only grants while the app runs; it is
    /// protected by a background task and resumes on the next launch.
    private func transcode(_ id: String, spec: TransferSpec, options: [String: Any], pending: [String], progress initial: TransferProgress) {
        guard !transcoding.contains(id) else { return }
        transcoding.insert(id)
        store.update(id) {
            $0.state = TransferStates.running
            $0.stage = TransferStages.transcoding
        }
        var acquired = UIBackgroundTaskIdentifier.invalid
        DispatchQueue.main.sync {
            acquired = UIApplication.shared.beginBackgroundTask(withName: "pam-transfer-transcode") {}
        }
        let task = acquired
        DispatchQueue.global(qos: .userInitiated).async {
            var progress = initial
            var failure: TransferFailure?
            for (index, path) in pending.enumerated() {
                do {
                    let source = try TransferPaths.resolve(path, mustExist: true)
                    let output = try TransferPaths.workDirectory(id)
                        .appendingPathComponent("transcoded-\(progress.transcoded.count + 1).mp4")
                    var result = ""
                    do {
                        _ = try MediaTranscoderBridge.transcode(
                            source: source,
                            destination: output,
                            options: options,
                            cancelled: { [weak self] in self?.store.get(id)?.state == TransferStates.cancelled },
                            progress: { [weak self] fraction in
                                let overall = (Double(index) + min(max(fraction, 0), 1)) / Double(pending.count) * 1_000
                                self?.store.progress(id, transferred: Int64(overall), total: 1_000)
                            }
                        )
                        result = output.path
                    } catch let error as TransferFailure {
                        guard (options["fallback"] as? Bool) == true else { throw error }
                        // Fallback: an empty entry sends the original and is never re-encoded.
                        try? FileManager.default.removeItem(at: output)
                    }
                    progress.transcoded[path] = result
                    try TransferFiles.writeProgress(id, progress)
                } catch {
                    failure = error as? TransferFailure ?? TransferFailure(message: error.localizedDescription, retryable: false)
                    break
                }
            }
            self.queue.async {
                self.transcoding.remove(id)
                if let failure {
                    self.fail(id, spec: spec, failure)
                } else {
                    self.start(id)
                }
                DispatchQueue.main.async { UIApplication.shared.endBackgroundTask(task) }
            }
        }
    }

    private func launch(_ id: String, spec: TransferSpec, index: Int, delay: TimeInterval) {
        guard store.get(id).map({ !TransferStates.finished($0.state) }) == true else { return }
        guard index < spec.steps.count else {
            succeed(id, spec: spec, last: TransferFiles.readProgress(id).last ?? "", status: store.get(id)?.statusCode ?? 0)
            return
        }
        let step = spec.steps[index]
        let progress = TransferFiles.readProgress(id)
        let stage: Int
        if step.saveTo != nil {
            stage = TransferStages.downloading
        } else if case .file? = step.body {
            stage = TransferStages.uploading
        } else if case .multipart? = step.body {
            stage = TransferStages.uploading
        } else {
            stage = TransferStages.requesting
        }
        store.update(id) {
            $0.stage = stage
            $0.step = index + 1
            $0.steps = spec.steps.count
        }
        do {
            let attempt = store.get(id)?.attempt ?? 1
            let built = try TransferRequestBuilder.build(
                step,
                templates: progress.templates(id: id, tag: spec.tag),
                file: { path in
                    let url = try self.localFile(path, progress: progress)
                    return (url, progress.transcoded[path].map { !$0.isEmpty } ?? false)
                },
                workDirectory: try TransferPaths.workDirectory(id),
                network: spec.network
            )
            let task: URLSessionTask
            if let body = built.bodyFile {
                task = session.uploadTask(with: built.request, fromFile: body)
            } else {
                task = session.downloadTask(with: built.request)
            }
            task.taskDescription = TaskTag(id: id, step: index, attempt: attempt).description
            if delay > 0 { task.earliestBeginDate = Date().addingTimeInterval(delay) }
            task.resume()
        } catch let failure as TransferFailure {
            fail(id, spec: spec, failure)
        } catch {
            fail(id, spec: spec, TransferFailure(message: error.localizedDescription, retryable: false))
        }
    }

    private func localFile(_ path: String, progress: TransferProgress) throws -> URL {
        if let output = progress.transcoded[path], !output.isEmpty, FileManager.default.fileExists(atPath: output) {
            return URL(fileURLWithPath: output)
        }
        return try TransferPaths.resolve(path, mustExist: true)
    }

    private func uploadSize(_ step: StepSpec, progress: TransferProgress) -> Int64 {
        step.body?.files.reduce(Int64(0)) { total, path in
            let url = try? localFile(path, progress: progress)
            return total + Int64((try? url?.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        } ?? 0
    }

    private func finishStep(_ tag: TaskTag, status: Int, body: String, error: Error?) {
        let id = tag.id
        guard let record = store.get(id), !TransferStates.finished(record.state) else { return }
        guard let spec = try? TransferFiles.readSpec(id) else {
            fail(id, spec: nil, TransferFailure(message: "Transfer payload is unavailable", retryable: false))
            return
        }
        let step = spec.steps[min(tag.step, spec.steps.count - 1)]
        if let error {
            if (error as? URLError)?.code == .cancelled, record.state == TransferStates.cancelled { return }
            retryOrFail(id, spec: spec, index: tag.step, TransferFailure(message: error.localizedDescription, retryable: step.retryable))
            return
        }
        guard (200..<300).contains(status) else {
            let retryable = step.retryable && (status == 408 || status == 425 || status == 429 || status >= 500)
            retryOrFail(id, spec: spec, index: tag.step, TransferFailure(message: "HTTP \(status)", retryable: retryable, statusCode: status, body: body))
            return
        }
        var progress = TransferFiles.readProgress(id)
        progress.next = tag.step + 1
        progress.last = body
        if let name = step.name { progress.named[name] = body }
        try? TransferFiles.writeProgress(id, progress)
        store.update(id) { $0.statusCode = status }
        stepBase[id] = (stepBase[id] ?? 0) + uploadSize(step, progress: progress)
        if progress.next >= spec.steps.count {
            succeed(id, spec: spec, last: body, status: status)
        } else {
            launch(id, spec: spec, index: progress.next, delay: 0)
        }
    }

    private func retryOrFail(_ id: String, spec: TransferSpec, index: Int, _ failure: TransferFailure) {
        let attempt = store.get(id)?.attempt ?? 1
        if failure.retryable && attempt <= spec.retry.times {
            store.update(id) {
                $0.state = TransferStates.retrying
                $0.stage = TransferStages.waiting
                $0.attempt = attempt + 1
                $0.message = failure.message
                $0.statusCode = failure.statusCode
                $0.responseBody = String(failure.body.prefix(TransferRecord.maxResponseChars))
            }
            // earliestBeginDate lets the system wait out the backoff while suspended.
            launch(id, spec: spec, index: index, delay: spec.retry.delay(forRetry: attempt))
        } else {
            fail(id, spec: spec, failure)
        }
    }

    private func succeed(_ id: String, spec: TransferSpec, last: String, status: Int) {
        store.update(id) {
            $0.state = TransferStates.succeeded
            $0.stage = TransferStages.done
            $0.step = spec.steps.count
            $0.transferred = max($0.transferred, $0.total)
            $0.statusCode = status
            $0.responseBody = String(last.prefix(TransferRecord.maxResponseChars))
            $0.message = ""
        }
        if let notification = spec.notification { notify(id, notification, success: true, message: "") }
        TransferFiles.delete(id)
        stepBase[id] = nil
        stepTotals[id] = nil
    }

    private func fail(_ id: String, spec: TransferSpec?, _ failure: TransferFailure) {
        guard store.get(id)?.state != TransferStates.cancelled else { return }
        store.update(id) {
            $0.state = TransferStates.failed
            $0.message = failure.message
            $0.statusCode = failure.statusCode
            $0.responseBody = String(failure.body.prefix(TransferRecord.maxResponseChars))
        }
        if let notification = spec?.notification { notify(id, notification, success: false, message: failure.message) }
    }

    /// iOS has no foreground-service progress; completion/failure texts are
    /// posted as local notifications when provided.
    private func notify(_ id: String, _ spec: NotificationSpec, success: Bool, message: String) {
        guard let text = success ? spec.completed : spec.failed else { return }
        let content = UNMutableNotificationContent()
        content.title = spec.title
        content.body = success ? text : (message.isEmpty ? text : "\(text) \(message)")
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "pam-transfer-\(id)", content: content, trigger: nil)
        )
    }

    // MARK: URLSession delegate

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        queue.async {
            var buffer = self.bodies[dataTask.taskIdentifier] ?? Data()
            if buffer.count < Self.maxResponseBytes {
                buffer.append(data.prefix(Self.maxResponseBytes - buffer.count))
            }
            self.bodies[dataTask.taskIdentifier] = buffer
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard let tag = TaskTag.parse(task.taskDescription) else { return }
        queue.async {
            let base = self.stepBase[tag.id] ?? 0
            let total = self.stepTotals[tag.id] ?? self.store.get(tag.id)?.total ?? 0
            self.store.progress(tag.id, transferred: min(base + totalBytesSent, max(total, base + totalBytesSent)), total: total)
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let tag = TaskTag.parse(downloadTask.taskDescription) else { return }
        queue.async {
            self.store.progress(tag.id, transferred: totalBytesWritten, total: max(totalBytesExpectedToWrite, 0))
        }
    }

    /// The temporary file is only valid during this callback: move or read it now.
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let tag = TaskTag.parse(downloadTask.taskDescription) else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        var body = ""
        let saveTo = (try? TransferFiles.readSpec(tag.id))?.steps[safe: tag.step]?.saveTo
        if let saveTo, (200..<300).contains(status) {
            do {
                let output = try TransferPaths.resolve(saveTo, mustExist: false)
                try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
                let partial = output.deletingLastPathComponent().appendingPathComponent(".\(output.lastPathComponent).part")
                try? FileManager.default.removeItem(at: partial)
                try FileManager.default.moveItem(at: location, to: partial)
                try? FileManager.default.removeItem(at: output)
                try FileManager.default.moveItem(at: partial, to: output)
                let bytes = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                let json = try JSONSerialization.data(withJSONObject: ["path": saveTo, "bytes": bytes])
                body = String(decoding: json, as: UTF8.self)
            } catch {
                body = ""
            }
        } else if let handle = try? FileHandle(forReadingFrom: location) {
            body = String(decoding: (try? handle.read(upToCount: Self.maxResponseBytes)) ?? Data(), as: UTF8.self)
            try? handle.close()
        }
        queue.sync { self.downloads[downloadTask.taskIdentifier] = (status, body) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let tag = TaskTag.parse(task.taskDescription) else { return }
        queue.async {
            let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
            var body = ""
            if let download = self.downloads.removeValue(forKey: task.taskIdentifier) {
                body = download.body
            } else if let data = self.bodies.removeValue(forKey: task.taskIdentifier) {
                body = String(decoding: data, as: UTF8.self)
            }
            if let directory = try? TransferPaths.workDirectory(tag.id) {
                TransferRequestBuilder.cleanTemporaryBodies(in: directory)
            }
            self.finishStep(tag, status: status, body: body, error: error)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Signed-URL hops keep working; downgrades to plain HTTP do not.
        completionHandler(request.url?.scheme?.lowercased() == "https" ? request : nil)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        queue.async {
            let completion = self.backgroundCompletion
            self.backgroundCompletion = nil
            DispatchQueue.main.async { completion?() }
        }
    }
}

/// `taskDescription` of every engine task: `<id>|<step>|<attempt>`.
struct TaskTag: Equatable, CustomStringConvertible {
    let id: String
    let step: Int
    let attempt: Int

    var description: String { "\(id)|\(step)|\(attempt)" }

    static func parse(_ value: String?) -> TaskTag? {
        let parts = (value ?? "").split(separator: "|").map(String.init)
        guard parts.count == 3, let step = Int(parts[1]), let attempt = Int(parts[2]) else { return nil }
        return TaskTag(id: parts[0], step: step, attempt: attempt)
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
