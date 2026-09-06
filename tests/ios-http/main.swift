import Foundation

let url = URL(string: "https://example.test/private-document")!
func response(_ status: Int) -> HTTPURLResponse { HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)! }
for status in [200, 201, 204, 299] {
    precondition(TransferHttpResult.accepts(response(status)))
    precondition(TransferHttpResult.completion(response: response(status), error: nil, failedToSave: false).state == 3)
}
for status in [301, 307, 400, 401, 403, 404, 429, 500, 503] {
    precondition(!TransferHttpResult.accepts(response(status)))
    precondition(TransferHttpResult.completion(response: response(status), error: nil, failedToSave: false).state == 4)
}
precondition(TransferHttpResult.completion(response: nil, error: nil, failedToSave: false).state == 4)
precondition(TransferHttpResult.completion(response: response(200), error: nil, failedToSave: true).state == 4)
precondition(TransferHttpResult.completion(response: response(200), error: URLError(.cancelled), failedToSave: false).state == 5)
precondition(TransferHttpResult.completion(response: response(200), error: URLError(.timedOut), failedToSave: false).state == 4)
print("PASS transfer HTTP completion contracts")

let signedHeaders = try TransferHeaders.decode("{\"Content-Type\":\"image/png\",\"x-amz-acl\":\"private\"}")
let uploadRequest = TransferHeaders.request(url: url, method: "PUT", headers: signedHeaders)
precondition(uploadRequest.httpMethod == "PUT")
precondition(uploadRequest.value(forHTTPHeaderField: "Content-Type") == "image/png")
precondition(uploadRequest.value(forHTTPHeaderField: "x-amz-acl") == "private")
for invalid in ["[]", "{\"Host\":\"other.test\"}", "{\"Content-Length\":\"10\"}", "{\"X-Test\":1}", "{\"X-Test\":\"a\",\"x-test\":\"b\"}", "{\"X-Test\":\"a\\r\\nInjected: b\"}"] {
    do {
        _ = try TransferHeaders.decode(invalid)
        fatalError("Invalid headers accepted")
    } catch { }
}
print("PASS signed transfer headers")

for terminal in [TransferPhase.completed, .failed, .cancelled] {
    for next in TransferPhase.allCases {
        precondition(!TransferPhase.allows(current: Int(terminal.rawValue), next: next.rawValue))
    }
}
precondition(TransferPhase.allows(current: nil, next: TransferPhase.queued.rawValue))
precondition(!TransferPhase.allows(current: nil, next: TransferPhase.running.rawValue))
precondition(TransferPhase.allows(current: Int(TransferPhase.queued.rawValue), next: TransferPhase.cancelled.rawValue))
precondition(TransferPhase.allows(current: Int(TransferPhase.running.rawValue), next: TransferPhase.completed.rawValue))
precondition(!TransferPhase.allows(current: 99, next: TransferPhase.running.rawValue))
print("PASS terminal transfer state precedence")

let files = FileManager.default
let fixture = files.temporaryDirectory.appendingPathComponent("pam-transfer-path-" + UUID().uuidString)
let root = fixture.appendingPathComponent("allowed")
let outside = fixture.appendingPathComponent("outside")
try files.createDirectory(at: root, withIntermediateDirectories: true)
try files.createDirectory(at: outside, withIntermediateDirectories: true)
defer { try? files.removeItem(at: fixture) }
try Data("private".utf8).write(to: outside.appendingPathComponent("secret.txt"))
try files.createSymbolicLink(at: root.appendingPathComponent("escape"), withDestinationURL: outside)
let canonicalRoot = root.resolvingSymlinksInPath()
let safeDestination = try TransferPath.resolve("documents/new.pdf", root: root)
precondition(safeDestination == canonicalRoot.appendingPathComponent("documents/new.pdf"))
for invalid in ["", "/absolute.pdf", "..", "../outside/secret.txt", "escape/secret.txt", "escape/new.pdf"] {
    do {
        _ = try TransferPath.resolve(invalid, root: root)
        fatalError("Path outside transfer root accepted")
    } catch { }
}
let pending = root.appendingPathComponent("pending")
try files.createDirectory(at: pending, withIntermediateDirectories: true)
let original = try TransferPath.resolve("pending/new.pdf", root: root)
try files.removeItem(at: pending)
try files.createSymbolicLink(at: pending, withDestinationURL: outside)
do {
    _ = try TransferPath.validate(original, root: root)
    fatalError("Changed destination parent escaped transfer root")
} catch { }
print("PASS transfer file path confinement")
