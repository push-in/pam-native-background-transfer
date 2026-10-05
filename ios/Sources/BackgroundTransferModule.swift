import Foundation
import ObjectiveC
import PamNative
import UIKit

/// PAM module `background-transfer` on iOS: 0.3 pipelines executed by a
/// background URLSession (see TransferEngine), Keychain-sealed specs and
/// secrets, conflated watch channels.
public final class BackgroundTransferModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let engine = TransferEngine.shared
    private let lock = NSLock()
    private var watches: [Int64: WatchChannel] = [:]
    private var nextSubscription: Int64 = 1
    private let worker = DispatchQueue(label: "pam-background-transfer.module", qos: .utility)

    public init() {
        DispatchQueue.main.async {
            AppDelegateHook.install()
            TransferEngine.shared.resumeOnLaunch()
        }
    }

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        let values: [String: WireValue]
        do {
            values = try WireMap.decode(payload)
        } catch {
            fail(completion, error)
            return
        }
        if method == "watchNext" {
            guard case let .integer(subscription)? = values["subscription"], let channel = channel(subscription) else {
                fail(completion, TransferError("Watch is closed"))
                return
            }
            channel.next(completion)
            return
        }
        worker.async {
            do {
                let result: [String: WireValue]
                switch method {
                case "enqueue":
                    guard case let .text(spec)? = values["spec"] else {
                        throw TransferError("Transfers need pushinbr/pam-native-background-transfer 0.3 descriptions")
                    }
                    result = Self.wire(try self.engine.enqueue(spec))
                case "status":
                    guard let record = self.engine.store.get(try Self.text(values, "identifier")) else {
                        throw TransferError("Transfer not found")
                    }
                    result = Self.wire(record)
                case "list":
                    var tag: String?
                    if case let .text(value)? = values["tag"], !value.isEmpty { tag = value }
                    let records = self.engine.store.all(tag: tag).map(\.json)
                    let data = try JSONSerialization.data(withJSONObject: records)
                    result = ["transfers": .text(String(decoding: data, as: UTF8.self))]
                case "watch":
                    result = try self.watch(try Self.text(values, "identifier"))
                case "unwatch":
                    guard case let .integer(subscription)? = values["subscription"] else { throw TransferError("subscription is required") }
                    self.unwatch(subscription)
                    result = [:]
                case "cancel":
                    try self.engine.cancel(try Self.text(values, "identifier"))
                    result = [:]
                case "retry":
                    result = Self.wire(try self.engine.retry(try Self.text(values, "identifier")))
                case "prune":
                    guard case let .integer(days)? = values["olderThanDays"] else { throw TransferError("olderThanDays is required") }
                    result = ["removed": .integer(Int64(self.engine.prune(olderThanDays: days)))]
                case "secretPut":
                    try SecretVault.put(try Self.text(values, "name"), try Self.text(values, "value"))
                    result = [:]
                case "secretForget":
                    try SecretVault.forget(try Self.text(values, "name"))
                    result = [:]
                default:
                    throw TransferError("Unknown method: \(method)")
                }
                completion(.success, try WireMap.encode(result))
            } catch {
                self.fail(completion, error)
            }
        }
    }

    public func close() {
        lock.lock()
        let all = Array(watches.values)
        watches.removeAll()
        lock.unlock()
        all.forEach { $0.close() }
    }

    private func watch(_ id: String) throws -> [String: WireValue] {
        guard let current = engine.store.get(id) else { throw TransferError("Transfer not found") }
        let channel = WatchChannel()
        channel.offer(current)
        channel.unsubscribe = engine.store.observe(id) { [weak channel] in channel?.offer($0) }
        lock.lock()
        let subscription = nextSubscription
        nextSubscription += 1
        watches[subscription] = channel
        lock.unlock()
        return ["subscription": .integer(subscription)]
    }

    private func unwatch(_ subscription: Int64) {
        lock.lock()
        let channel = watches.removeValue(forKey: subscription)
        lock.unlock()
        channel?.close()
    }

    private func channel(_ subscription: Int64) -> WatchChannel? {
        lock.lock()
        defer { lock.unlock() }
        return watches[subscription]
    }

    private func fail(_ completion: ModuleCompletion, _ error: Error) {
        completion(.failure, Data(((error as? LocalizedError)?.errorDescription ?? "Background transfer failure").utf8))
    }

    private static func text(_ values: [String: WireValue], _ key: String) throws -> String {
        guard case let .text(value)? = values[key] else { throw TransferError("\(key) is required") }
        return value
    }

    static func wire(_ record: TransferRecord) -> [String: WireValue] {
        [
            "identifier": .text(record.id),
            "kind": .integer(Int64(record.kind)),
            "state": .integer(Int64(record.state)),
            "stage": .integer(Int64(record.stage)),
            "step": .integer(Int64(record.step)),
            "steps": .integer(Int64(record.steps)),
            "bytesTransferred": .integer(record.transferred),
            "bytesTotal": .integer(record.total),
            "attempt": .integer(Int64(record.attempt)),
            "message": .text(record.message),
            "tag": .text(record.tag ?? ""),
            "statusCode": .integer(Int64(record.statusCode)),
            "responseBody": .text(record.responseBody),
            "createdAt": .integer(record.createdAt),
            "updatedAt": .integer(record.updatedAt),
        ]
    }
}

/// Conflated long-poll channel: `watchNext` receives the newest snapshot not
/// yet delivered.
final class WatchChannel: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: TransferRecord?
    private var waiter: ModuleCompletion?
    private var closed = false
    var unsubscribe: (() -> Void)?

    func offer(_ record: TransferRecord) {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        let current = waiter
        if current == nil { pending = record } else { waiter = nil }
        lock.unlock()
        current?(.success, (try? WireMap.encode(BackgroundTransferModule.wire(record))) ?? Data())
    }

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if closed {
            lock.unlock()
            completion(.failure, Data("Watch is closed".utf8))
            return
        }
        if let ready = pending {
            pending = nil
            lock.unlock()
            completion(.success, (try? WireMap.encode(BackgroundTransferModule.wire(ready))) ?? Data())
            return
        }
        guard waiter == nil else {
            lock.unlock()
            completion(.failure, Data("Watch already pending".utf8))
            return
        }
        waiter = completion
        lock.unlock()
    }

    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        pending = nil
        let current = waiter
        waiter = nil
        lock.unlock()
        unsubscribe?()
        current?(.failure, Data("Watch stopped".utf8))
    }
}

/// Adds `application(_:handleEventsForBackgroundURLSession:completionHandler:)`
/// to the host's app delegate when it does not implement it, so iOS can hand
/// the background session back to the engine after relaunching the app.
enum AppDelegateHook {
    private static var installed = false

    static func install() {
        guard !installed, let delegate = UIApplication.shared.delegate else { return }
        installed = true
        let type: AnyClass = Swift.type(of: delegate)
        let selector = #selector(UIApplicationDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:))
        guard class_getInstanceMethod(type, selector) == nil else { return }
        let block: @convention(block) (AnyObject, UIApplication, String, @escaping () -> Void) -> Void = { _, _, identifier, completion in
            TransferEngine.shared.backgroundEvents(identifier: identifier, completion: completion)
        }
        class_addMethod(type, selector, imp_implementationWithBlock(block), "v@:@@@?")
    }
}
