import Foundation

/// Builds the URLRequest (and body file) of one step. Background sessions
/// only accept file uploads and downloads, so JSON, form, multipart and empty
/// bodies are written to a private file next to the encrypted spec.
enum TransferRequestBuilder {
    struct Built {
        let request: URLRequest
        /// nil: run as a download task (GET/DELETE without body, or `saveTo`).
        let bodyFile: URL?
    }

    static func build(
        _ step: StepSpec,
        templates: TransferTemplates,
        file: (String) throws -> (url: URL, transcoded: Bool),
        workDirectory: URL,
        network: Int
    ) throws -> Built {
        do {
            return try buildUnchecked(step, templates: templates, file: file, workDirectory: workDirectory, network: network)
        } catch let failure as TransferFailure {
            throw failure
        } catch {
            throw TransferFailure(message: error.localizedDescription, retryable: false)
        }
    }

    private static func buildUnchecked(
        _ step: StepSpec,
        templates: TransferTemplates,
        file: (String) throws -> (url: URL, transcoded: Bool),
        workDirectory: URL,
        network: Int
    ) throws -> Built {
        let urlText = try templates.string(step.url)
        do {
            try TransferUrls.requireAllowed(urlText)
        } catch {
            throw TransferFailure(message: "Resolved URL is not allowed", retryable: false)
        }
        guard let url = URL(string: urlText) else { throw TransferFailure(message: "Resolved URL is not allowed", retryable: false) }
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.httpMethod = step.method
        if network == 2 {
            request.allowsCellularAccess = false
            request.allowsExpensiveNetworkAccess = false
        }
        if let path = step.headersFrom {
            guard let source = try templates.lookup(path) as? [String: Any] else {
                throw TransferError("{{\(path)}} is not an object")
            }
            for (name, value) in source {
                guard let text = value as? String, name.lowercased() != "content-length", name.lowercased() != "host" else { continue }
                request.setValue(text, forHTTPHeaderField: name)
            }
        }
        for header in step.headers {
            let value: String
            if let vault = header.secretVault {
                guard let secret = SecretVault.get(vault) else {
                    throw TransferFailure(message: "Secret \"\(vault)\" is not stored", retryable: false)
                }
                value = secret
            } else if let secret = header.secretValue {
                value = secret
            } else {
                value = try templates.string(header.value ?? "")
            }
            request.setValue(header.prefix + value, forHTTPHeaderField: header.name)
        }
        let writesBody = ["POST", "PUT", "PATCH"].contains(step.method) || step.body != nil
        guard writesBody, step.saveTo == nil || step.body != nil else {
            return Built(request: request, bodyFile: nil)
        }
        switch step.body {
        case nil:
            return Built(request: request, bodyFile: try temporary(Data(), in: workDirectory))
        case let .json(value)?:
            let data = try JSONSerialization.data(withJSONObject: try templates.json(value), options: [.fragmentsAllowed])
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            return Built(request: request, bodyFile: try temporary(data, in: workDirectory))
        case let .form(fields)?:
            let encoded = try fields.map { name, value in
                "\(formEncode(name))=\(formEncode(try templates.string(stringValue(value))))"
            }.joined(separator: "&")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            return Built(request: request, bodyFile: try temporary(Data(encoded.utf8), in: workDirectory))
        case let .file(path, mime)?:
            request.setValue(mime, forHTTPHeaderField: "Content-Type")
            let local = try file(path)
            request.setValue(local.transcoded ? "video/mp4" : mime, forHTTPHeaderField: "Content-Type")
            return Built(request: request, bodyFile: local.url)
        case let .multipart(parts)?:
            let boundary = "pam-\(UUID().uuidString)"
            let target = workDirectory.appendingPathComponent("body-\(UUID().uuidString).tmp")
            try writeMultipart(parts, boundary: boundary, templates: templates, file: file, to: target)
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            return Built(request: request, bodyFile: target)
        }
    }

    static func writeMultipart(
        _ parts: [PartSpec],
        boundary: String,
        templates: TransferTemplates,
        file: (String) throws -> (url: URL, transcoded: Bool),
        to target: URL
    ) throws {
        guard FileManager.default.createFile(atPath: target.path, contents: nil) else {
            throw TransferError("Cannot create multipart body")
        }
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        for part in parts {
            var header = "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(part.name)\""
            switch part {
            case let .field(_, value, literal):
                header += "\r\n\r\n"
                try output.write(contentsOf: Data(header.utf8))
                try output.write(contentsOf: Data((literal ? value : try templates.string(value)).utf8))
            case let .file(_, path, mime, filename):
                let local = try file(path)
                let name = local.transcoded ? (filename as NSString).deletingPathExtension + ".mp4" : filename
                header += "; filename=\"\(name)\"\r\nContent-Type: \(local.transcoded ? "video/mp4" : mime)\r\n\r\n"
                try output.write(contentsOf: Data(header.utf8))
                let input = try FileHandle(forReadingFrom: local.url)
                defer { try? input.close() }
                while let chunk = try input.read(upToCount: 64 * 1_024), !chunk.isEmpty {
                    try output.write(contentsOf: chunk)
                }
            }
            try output.write(contentsOf: Data("\r\n".utf8))
        }
        try output.write(contentsOf: Data("--\(boundary)--\r\n".utf8))
    }

    /// Steps run one at a time per transfer, so every body file in its work
    /// directory is stale once a step finished.
    static func cleanTemporaryBodies(in directory: URL) {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.lastPathComponent.hasPrefix("body-") && file.pathExtension == "tmp" {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private static func temporary(_ data: Data, in directory: URL) throws -> URL {
        let target = directory.appendingPathComponent("body-\(UUID().uuidString).tmp")
        try data.write(to: target, options: .atomic)
        return target
    }

    static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._*")
        return (value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)
    }

    private static func stringValue(_ value: Any) -> String {
        switch value {
        case let text as String: return text
        case let number as NSNumber: return number.stringValue
        default: return "\(value)"
        }
    }
}
