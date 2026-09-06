import Foundation

enum TransferHeaders {
    private static let names = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+-.^_`|~")
    private static let reserved: Set<String> = ["host", "content-length", "transfer-encoding", "connection", "trailer", "upgrade"]

    static func decode(_ encoded: String) throws -> [String: String] {
        guard encoded.utf8.count <= 4096,
              let headers = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: String],
              headers.count <= 32 else { throw HeaderError.invalid }
        var seen = Set<String>()
        for (name, value) in headers {
            guard !name.isEmpty, name.unicodeScalars.allSatisfy({ names.contains($0) }),
                  !reserved.contains(name.lowercased()), seen.insert(name.lowercased()).inserted,
                  value.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 }) else { throw HeaderError.invalid }
        }
        return headers
    }

    static func request(url: URL, method: String, headers: [String: String]) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }

    private enum HeaderError: Error { case invalid }
}
