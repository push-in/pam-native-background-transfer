import Foundation

/// Stale step outputs (mirrors Android `TransferRefresh.kt`). A step whose URL
/// or headers come from an earlier step (`{{steps.sign.data.upload_url}}`,
/// `headersFrom`) may run hours after that step (offline, retry backoff,
/// relaunch, manual retry): a signed URL has expired by then and the storage
/// answers 401/403 forever. On 401/403 the transfer rewinds once per run to
/// the producing step, which runs again for a fresh value; a second 401/403
/// with the fresh value fails the transfer.
enum TransferRefresh {
    private static let reference = try! NSRegularExpression(
        pattern: #"\{\{\s*(response|steps\.([A-Za-z0-9_\-]+))[.\s}]"#
    )

    /// Index of the step to resume from, or nil when the failure stands.
    static func rewindTo(steps: [StepSpec], failed: Int, statusCode: Int, refreshed: Set<Int>) -> Int? {
        guard statusCode == 401 || statusCode == 403, steps.indices.contains(failed) else { return nil }
        let step = steps[failed]
        var texts = [step.url] + step.headers.compactMap(\.value)
        if let path = step.headersFrom { texts.append("{{\(path)}}") }
        var producers = Set<Int>()
        for text in texts {
            for name in references(text) {
                let producer: Int?
                if let name {
                    producer = stride(from: failed - 1, through: 0, by: -1).first { steps[$0].name == name }
                } else {
                    producer = failed - 1
                }
                if let producer, producer >= 0 { producers.insert(producer) }
            }
        }
        return producers.filter { !refreshed.contains($0) && replayable(steps[$0]) }.min()
    }

    /// nil for `{{response…}}`, the step name for `{{steps.name…}}`.
    private static func references(_ text: String) -> [String?] {
        let range = NSRange(text.startIndex..., in: text)
        return reference.matches(in: text, range: range).map { match in
            Range(match.range(at: 2), in: text).map { String(text[$0]) }
        }
    }

    /// A step replays only when it does not read `{{response}}` (the previous step's answer is not kept).
    private static func replayable(_ step: StepSpec) -> Bool {
        var texts = [step.url] + step.headers.compactMap(\.value)
        if let path = step.headersFrom { texts.append("{{\(path)}}") }
        switch step.body {
        case let .json(value)?: texts.append(String(describing: value))
        case let .form(fields)?: texts += fields.map { String(describing: $0.1) }
        case let .multipart(parts)?:
            for case let .field(_, value, literal) in parts where !literal { texts.append(value) }
        default: break
        }
        return !texts.contains { text in references(text).contains { $0 == nil } }
    }
}
