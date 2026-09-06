import Foundation

// Wire states match the package TransferState enum.
enum TransferHttpResult {
    static func accepts(_ response: URLResponse?) -> Bool {
        guard let http = response as? HTTPURLResponse else { return false }
        return (200...299).contains(http.statusCode)
    }

    static func completion(response: URLResponse?, error: Error?, failedToSave: Bool) -> (state: Int64, message: String) {
        if (error as? URLError)?.code == .cancelled { return (5, "Cancelled") }
        if failedToSave { return (4, "Could not save downloaded file") }
        if error != nil { return (4, "Transfer failed") }
        guard accepts(response) else { return (4, "Transfer did not receive a successful HTTP response") }
        return (3, "")
    }
}

// Terminal results are immutable: late progress/completion cannot revive a task.
enum TransferPhase: Int64, CaseIterable {
    case queued = 1
    case running = 2
    case completed = 3
    case failed = 4
    case cancelled = 5

    static func allows(current: Int?, next: Int64) -> Bool {
        guard let next = Self(rawValue: next) else { return false }
        guard let current else { return next == .queued }
        guard let current = Self(rawValue: Int64(current)) else { return false }
        switch current {
        case .queued: return next != .queued
        case .running: return next != .queued
        case .completed, .failed, .cancelled: return false
        }
    }
}
