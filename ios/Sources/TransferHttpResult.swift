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
