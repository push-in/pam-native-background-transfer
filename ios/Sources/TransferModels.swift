import Foundation

// Transfer description, public record and templates (port of the Android
// TransferSpec/TransferStore/TransferTemplates). Pure Foundation so the logic
// is unit-testable.

struct TransferError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// A step failure; retryable network/server errors are retried with backoff.
struct TransferFailure: LocalizedError {
    let message: String
    let retryable: Bool
    var statusCode = 0
    var body = ""
    var errorDescription: String? { message }
}

enum TransferStates {
    static let queued = 1
    static let running = 2
    static let succeeded = 3
    static let failed = 4
    static let cancelled = 5
    static let retrying = 6

    static func finished(_ state: Int) -> Bool { state == succeeded || state == failed || state == cancelled }
}

enum TransferStages {
    static let waiting = 1
    static let transcoding = 2
    static let uploading = 3
    static let requesting = 4
    static let downloading = 5
    static let done = 6
}

struct RetryPolicy: Equatable {
    let times: Int
    let backoff: Int
    let delaySeconds: Int64

    /// Linear (1) or exponential (2) delay before attempt [attempt] (1-based retry count).
    func delay(forRetry attempt: Int) -> TimeInterval {
        let base = Double(delaySeconds)
        let value = backoff == 1 ? base * Double(attempt) : base * pow(2, Double(max(attempt - 1, 0)))
        return min(value, 18_000)
    }
}

struct NotificationSpec: Equatable {
    let title: String
    let text: String?
    let progress: Bool
    let completed: String?
    let failed: String?
}

struct HeaderSpec: Equatable {
    let name: String
    let value: String?
    let prefix: String
    let secretVault: String?
    let secretValue: String?
}

enum PartSpec {
    case field(name: String, value: String, literal: Bool)
    case file(name: String, path: String, mimeType: String, filename: String)

    var name: String {
        switch self {
        case let .field(name, _, _), let .file(name, _, _, _): return name
        }
    }
}

enum BodySpec {
    case json(Any)
    case form([(String, Any)])
    case file(path: String, mimeType: String)
    case multipart([PartSpec])

    var files: [String] {
        switch self {
        case let .file(path, _): return [path]
        case let .multipart(parts):
            return parts.compactMap { if case let .file(_, path, _, _) = $0 { return path } else { return nil } }
        default: return []
        }
    }
}

struct StepSpec {
    let name: String?
    let method: String
    let url: String
    let headers: [HeaderSpec]
    let headersFrom: String?
    let body: BodySpec?
    let saveTo: String?
    let retryable: Bool

    private static let namePattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$"
    private static let headerPattern = "^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$"

    static func parse(_ json: [String: Any]) throws -> StepSpec {
        let methods = [1: "GET", 2: "POST", 3: "PUT", 4: "PATCH", 5: "DELETE"]
        guard let methodId = (json["method"] as? NSNumber)?.intValue, let method = methods[methodId] else {
            throw TransferError("Unknown HTTP method")
        }
        guard let url = json["url"] as? String, url.count <= 8_192, !url.contains(where: \.isWhitespace) else {
            throw TransferError("Invalid step URL")
        }
        if !url.hasPrefix("{{") { try TransferUrls.requireAllowed(url) }
        var headers: [HeaderSpec] = []
        for header in (json["headers"] as? [[String: Any]]) ?? [] {
            guard let name = header["name"] as? String, name.range(of: headerPattern, options: .regularExpression) != nil else {
                throw TransferError("Invalid header name")
            }
            let value = nonEmpty(header["value"])
            if let value, value.contains("\r") || value.contains("\n") { throw TransferError("Invalid header value") }
            let secret = header["secret"] as? [String: Any]
            let spec = HeaderSpec(
                name: name,
                value: value,
                prefix: header["prefix"] as? String ?? "",
                secretVault: nonEmpty(secret?["vault"]),
                secretValue: nonEmpty(secret?["value"])
            )
            guard spec.value != nil || spec.secretVault != nil || spec.secretValue != nil else {
                throw TransferError("Header \(name) has no value")
            }
            headers.append(spec)
        }
        let body = try (json["body"] as? [String: Any]).map(parseBody)
        if body != nil && method == "GET" { throw TransferError("GET steps cannot send a body") }
        let name = nonEmpty(json["name"])
        if let name, name.range(of: namePattern, options: .regularExpression) == nil { throw TransferError("Invalid step name") }
        let saveTo = nonEmpty(json["saveTo"])
        if let saveTo { try TransferPaths.requireRelative(saveTo) }
        return StepSpec(
            name: name,
            method: method,
            url: url,
            headers: headers,
            headersFrom: nonEmpty(json["headersFrom"]),
            body: body,
            saveTo: saveTo,
            retryable: (json["retry"] as? Bool) ?? true
        )
    }

    private static func parseBody(_ json: [String: Any]) throws -> BodySpec {
        switch json["type"] as? String {
        case "json":
            guard let value = json["value"] else { throw TransferError("JSON body needs a value") }
            return .json(value)
        case "form":
            let fields = (json["fields"] as? [String: Any]) ?? [:]
            return .form(fields.sorted { $0.key < $1.key }.map { ($0.key, $0.value) })
        case "file":
            guard let path = json["path"] as? String, let mime = json["mimeType"] as? String else {
                throw TransferError("File body needs path and mimeType")
            }
            try TransferPaths.requireRelative(path)
            try requireMime(mime)
            return .file(path: path, mimeType: mime)
        case "multipart":
            guard let parts = json["parts"] as? [[String: Any]], (1...64).contains(parts.count) else {
                throw TransferError("Multipart bodies support 1-64 parts")
            }
            return .multipart(try parts.map(parsePart))
        default:
            throw TransferError("Unknown body type")
        }
    }

    private static func parsePart(_ json: [String: Any]) throws -> PartSpec {
        guard let name = json["name"] as? String, !name.isEmpty, name.count <= 255,
              !name.contains("\""), !name.contains("\r"), !name.contains("\n") else {
            throw TransferError("Invalid multipart part name")
        }
        switch json["type"] as? String {
        case "field":
            guard let value = json["value"] as? String else { throw TransferError("Multipart field needs a value") }
            return .field(name: name, value: value, literal: (json["literal"] as? Bool) ?? false)
        case "file":
            guard let path = json["path"] as? String, let mime = json["mimeType"] as? String,
                  let filename = json["filename"] as? String else { throw TransferError("Invalid multipart file") }
            try TransferPaths.requireRelative(path)
            try requireMime(mime)
            guard !filename.isEmpty, filename.count <= 255, !filename.contains(where: { "\"\r\n/\\".contains($0) }) else {
                throw TransferError("Invalid multipart file name")
            }
            return .file(name: name, path: path, mimeType: mime, filename: filename)
        default:
            throw TransferError("Unknown multipart part")
        }
    }
}

func nonEmpty(_ value: Any?) -> String? {
    guard let text = value as? String, !text.isEmpty else { return nil }
    return text
}

func requireMime(_ value: String) throws {
    guard value.range(
        of: "^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$",
        options: .regularExpression
    ) != nil else { throw TransferError("Invalid MIME type") }
}

/// Parsed, validated description produced by `PendingTransfer::toWire()`.
struct TransferSpec {
    static let maxSteps = 64
    private static let keyPattern = "^[A-Za-z0-9_.:/-]{1,128}$"

    let kind: Int
    let tag: String?
    let unique: String?
    let network: Int
    let retry: RetryPolicy
    let notification: NotificationSpec?
    let transcode: [String: Any]?
    let steps: [StepSpec]

    var files: [String] { steps.flatMap { $0.body?.files ?? [] } }

    var videoFiles: [String] {
        var seen = Set<String>()
        var result: [String] = []
        for step in steps {
            switch step.body {
            case let .file(path, mime)? where mime.hasPrefix("video/"):
                if seen.insert(path).inserted { result.append(path) }
            case let .multipart(parts)?:
                for case let .file(_, path, mime, _) in parts where mime.hasPrefix("video/") {
                    if seen.insert(path).inserted { result.append(path) }
                }
            default:
                break
            }
        }
        return result
    }

    static func parse(_ json: String) throws -> TransferSpec {
        guard json.utf8.count <= 4 * 1024 * 1024 else { throw TransferError("Transfer description is too large") }
        guard let root = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            throw TransferError("Invalid transfer description")
        }
        guard ((root["version"] as? NSNumber)?.intValue ?? 1) == 1 else {
            throw TransferError("Unsupported transfer description version")
        }
        guard let kind = (root["kind"] as? NSNumber)?.intValue, (1...3).contains(kind) else {
            throw TransferError("Unknown transfer kind")
        }
        guard let stepsJson = root["steps"] as? [[String: Any]], (1...maxSteps).contains(stepsJson.count) else {
            throw TransferError("A transfer needs 1-\(maxSteps) steps")
        }
        let steps = try stepsJson.map(StepSpec.parse)
        let names = steps.compactMap(\.name)
        guard names.count == Set(names).count else { throw TransferError("Step names must be unique") }
        let tag = nonEmpty(root["tag"])
        if let tag, tag.range(of: keyPattern, options: .regularExpression) == nil { throw TransferError("Invalid tag") }
        let unique = nonEmpty(root["unique"])
        if let unique, unique.range(of: keyPattern, options: .regularExpression) == nil {
            throw TransferError("Invalid unique key")
        }
        let network = (root["network"] as? NSNumber)?.intValue ?? 1
        guard (1...3).contains(network) else { throw TransferError("Unknown network requirement") }
        let retry = root["retry"] as? [String: Any]
        let notification = (root["notification"] as? [String: Any]).flatMap { json -> NotificationSpec? in
            guard let title = json["title"] as? String else { return nil }
            return NotificationSpec(
                title: String(title.prefix(200)),
                text: nonEmpty(json["text"]).map { String($0.prefix(200)) },
                progress: (json["progress"] as? Bool) ?? false,
                completed: nonEmpty(json["completed"]).map { String($0.prefix(200)) },
                failed: nonEmpty(json["failed"]).map { String($0.prefix(200)) }
            )
        }
        return TransferSpec(
            kind: kind,
            tag: tag,
            unique: unique,
            network: network,
            retry: RetryPolicy(
                times: min(max((retry?["times"] as? NSNumber)?.intValue ?? 3, 0), 20),
                backoff: (retry?["backoff"] as? NSNumber)?.intValue ?? 2,
                delaySeconds: min(max((retry?["delaySeconds"] as? NSNumber)?.int64Value ?? 30, 10), 18_000)
            ),
            notification: notification,
            transcode: root["transcode"] as? [String: Any],
            steps: steps
        )
    }
}

/// Public, non-secret state of one transfer.
struct TransferRecord: Codable, Equatable {
    static let maxResponseChars = 256 * 1024

    var id: String
    var kind: Int
    var state = TransferStates.queued
    var stage = TransferStages.waiting
    var step = 0
    var steps = 1
    var transferred: Int64 = 0
    var total: Int64 = 0
    var attempt = 0
    var message = ""
    var tag: String?
    var unique: String?
    var statusCode = 0
    var responseBody = ""
    var createdAt: Int64 = TransferRecord.now()
    var updatedAt: Int64 = TransferRecord.now()

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    var json: [String: Any] {
        [
            "identifier": id, "kind": kind, "state": state, "stage": stage, "step": step, "steps": steps,
            "bytesTransferred": transferred, "bytesTotal": total, "attempt": attempt, "message": message,
            "tag": tag ?? "", "unique": unique ?? "", "statusCode": statusCode, "responseBody": responseBody,
            "createdAt": createdAt, "updatedAt": updatedAt,
        ]
    }
}

/// Encrypted resumable state: completed steps, their responses and transcodes.
struct TransferProgress: Codable, Equatable {
    var next = 0
    var last: String?
    var named: [String: String] = [:]
    var transcoded: [String: String] = [:]

    func templates(id: String, tag: String?) -> TransferTemplates {
        TransferTemplates(
            transferId: id,
            tag: tag,
            last: last.map(TransferTemplates.parse),
            named: named.mapValues(TransferTemplates.parse)
        )
    }
}

/// Resolves `{{response.a.b}}`, `{{steps.name.a.0.b}}`, `{{transfer.id}}` and
/// `{{transfer.tag}}` against the responses of completed steps.
struct TransferTemplates {
    private static let pattern = try! NSRegularExpression(pattern: "\\{\\{\\s*([A-Za-z0-9_\\-]+(?:\\.[A-Za-z0-9_\\-]+)*)\\s*\\}\\}")

    let transferId: String
    let tag: String?
    let last: Any?
    let named: [String: Any]

    func string(_ value: String) throws -> String {
        let source = value as NSString
        var output = ""
        var cursor = 0
        for match in Self.pattern.matches(in: value, range: NSRange(location: 0, length: source.length)) {
            output += source.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            let resolved = try lookup(source.substring(with: match.range(at: 1)))
            switch resolved {
            case let text as String: output += text
            case is NSNull: break
            case let number as NSNumber:
                output += CFGetTypeID(number) == CFBooleanGetTypeID() ? (number.boolValue ? "true" : "false") : number.stringValue
            default:
                if JSONSerialization.isValidJSONObject(resolved),
                   let data = try? JSONSerialization.data(withJSONObject: resolved) {
                    output += String(decoding: data, as: UTF8.self)
                } else {
                    output += "\(resolved)"
                }
            }
            cursor = match.range.location + match.range.length
        }
        output += source.substring(from: cursor)
        return output
    }

    /// A string that is exactly one template keeps the referenced JSON type.
    func json(_ value: Any) throws -> Any {
        switch value {
        case let text as String:
            let range = NSRange(location: 0, length: (text as NSString).length)
            if let match = Self.pattern.firstMatch(in: text, range: range), match.range == range {
                return try lookup((text as NSString).substring(with: match.range(at: 1)))
            }
            return try string(text)
        case let object as [String: Any]:
            return try object.mapValues { try json($0) }
        case let array as [Any]:
            return try array.map { try json($0) }
        default:
            return value
        }
    }

    func lookup(_ path: String) throws -> Any {
        let segments = path.split(separator: ".").map(String.init)
        var current: Any?
        switch segments.first {
        case "transfer":
            switch segments.count > 1 ? segments[1] : "" {
            case "id": return transferId
            case "tag": return tag ?? ""
            default: throw TransferError("Unknown template {{\(path)}}")
            }
        case "response":
            current = last
        case "steps":
            guard segments.count > 1 else { throw TransferError("Template {{\(path)}} needs a step name") }
            guard named.keys.contains(segments[1]) else { throw TransferError("Step \"\(segments[1])\" has not run yet") }
            current = named[segments[1]]
        default:
            throw TransferError("Unknown template {{\(path)}}")
        }
        for segment in segments.dropFirst(segments.first == "steps" ? 2 : 1) {
            if let object = current as? [String: Any] {
                current = object[segment]
            } else if let array = current as? [Any], let index = Int(segment), array.indices.contains(index) {
                current = array[index]
            } else {
                current = nil
            }
            if current == nil { throw TransferError("Template {{\(path)}} is missing from the response") }
        }
        return current ?? NSNull()
    }

    /// Parses a response body as JSON when possible, otherwise keeps the text.
    static func parse(_ body: String) -> Any {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{") || trimmed.hasPrefix("[") else { return body }
        return (try? JSONSerialization.jsonObject(with: Data(trimmed.utf8))) ?? body
    }
}

/// PAM sandbox paths (`FileReference` space rooted at Application Support/pam-files).
enum TransferPaths {
    static func requireRelative(_ path: String) throws {
        guard !path.isEmpty, path.count <= 1_024, !path.hasPrefix("/"), !path.contains("\0"), !path.contains("\\"),
              !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw TransferError("Transfer paths must be relative sandbox paths")
        }
    }

    static var root: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url.standardizedFileURL.resolvingSymlinksInPath()
    }

    static func resolve(_ path: String, mustExist: Bool, root base: URL = root) throws -> URL {
        try requireRelative(path)
        let file = base.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard file.path.hasPrefix(base.path + "/") else { throw TransferError("Path escapes the application sandbox") }
        if mustExist {
            var directory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: file.path, isDirectory: &directory), !directory.boolValue else {
                throw TransferError("File does not exist: \(path)")
            }
        }
        return file
    }

    /// Private, non-backed-up working directory of one transfer.
    static func workDirectory(_ id: String) throws -> URL {
        guard id.range(of: "^[A-Za-z0-9-]{8,64}$", options: .regularExpression) != nil else {
            throw TransferError("Invalid transfer identifier")
        }
        var url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-background-transfer", isDirectory: true)
            .appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }
}

enum TransferUrls {
    private static let loopback: Set<String> = ["127.0.0.1", "localhost", "::1", "[::1]"]

    /// HTTPS everywhere; plain HTTP only for loopback test servers.
    static func requireAllowed(_ url: String) throws {
        guard let parsed = URL(string: url), let scheme = parsed.scheme?.lowercased(), let host = parsed.host?.lowercased(),
              scheme == "https" || (scheme == "http" && loopback.contains(host)) else {
            throw TransferError("Transfers require an HTTPS URL")
        }
    }
}
