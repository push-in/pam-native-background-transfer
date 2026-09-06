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
