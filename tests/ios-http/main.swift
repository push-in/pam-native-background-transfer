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
