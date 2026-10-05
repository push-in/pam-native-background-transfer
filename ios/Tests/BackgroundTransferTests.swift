import PamNative
import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeBackgroundTransfer (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeBackgroundTransfer

/// XCTest mirror of the Android JVM + instrumented suites. Uncompiled — needs
/// Mac validation (run inside the generated host app: background sessions
/// need an application bundle).
final class BackgroundTransferTests: XCTestCase {
    private let pipeline = #"""
    {"version":1,"kind":2,"tag":"posts","retry":{"times":2,"backoff":2,"delaySeconds":10},"steps":[
     {"name":"sign","method":2,"url":"https://api.example.com/sign","body":{"type":"json","value":{"n":1}}},
     {"method":3,"url":"{{steps.sign.url}}","headersFrom":"steps.sign.headers","retry":false,
      "body":{"type":"file","path":"media/v.mp4","mimeType":"video/mp4"}},
     {"method":2,"url":"https://api.example.com/posts","body":{"type":"multipart","parts":[
       {"type":"field","name":"caption","value":"{{steps.sign.id}}"},
       {"type":"field","name":"raw","value":"{{x}}","literal":true},
       {"type":"file","name":"f","path":"media/a.jpg","mimeType":"image/jpeg","filename":"a.jpg"}]}}]}
    """#

    func testSpecParsesStepsBodiesAndPolicies() throws {
        let spec = try TransferSpec.parse(pipeline)
        XCTAssertEqual(spec.steps.count, 3)
        XCTAssertEqual(spec.files, ["media/v.mp4", "media/a.jpg"])
        XCTAssertEqual(spec.videoFiles, ["media/v.mp4"])
        XCTAssertFalse(spec.steps[1].retryable)
        XCTAssertEqual(spec.retry.delay(forRetry: 3), 40)
        XCTAssertThrowsError(try TransferSpec.parse(#"{"kind":1,"steps":[{"method":1,"url":"http://evil.com"}]}"#))
        XCTAssertNoThrow(try TransferSpec.parse(#"{"kind":1,"steps":[{"method":1,"url":"http://127.0.0.1:8080/x"}]}"#))
        XCTAssertThrowsError(try TransferSpec.parse(#"{"kind":1,"steps":[{"method":1,"url":"https://a.b","body":{"type":"json","value":1}}]}"#))
        XCTAssertThrowsError(try TransferSpec.parse(#"{"kind":1,"steps":[{"name":"a","method":1,"url":"https://a.b"},{"name":"a","method":1,"url":"https://a.b"}]}"#))
    }

    func testTemplatesResolveResponsesStepsAndTransfer() throws {
        let progress = TransferProgress(
            next: 1,
            last: #"{"url":"https://up.example.com/x","list":[{"a":"b"}]}"#,
            named: ["sign": #"{"url":"https://up.example.com/x","id":7}"#]
        )
        let templates = progress.templates(id: "abc", tag: "posts")
        XCTAssertEqual(try templates.string("{{response.url}}/{{steps.sign.id}}?t={{transfer.tag}}"), "https://up.example.com/x/7?t=posts")
        XCTAssertEqual(try templates.string("{{response.list.0.a}}"), "b")
        XCTAssertEqual((try templates.json(["n": "{{steps.sign.id}}"]) as? [String: Any])?["n"] as? Int, 7)
        XCTAssertThrowsError(try templates.string("{{steps.other.id}}"))
        XCTAssertThrowsError(try templates.string("{{response.nope}}"))
    }

    func testMultipartBodyKeepsLiteralFieldsAndStreamsFiles() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("a.jpg")
        try Data(repeating: 1, count: 1_000).write(to: source)
        let spec = try TransferSpec.parse(pipeline)
        let templates = TransferProgress(named: ["sign": #"{"id":7}"#]).templates(id: "abc", tag: nil)
        let built = try TransferRequestBuilder.build(
            spec.steps[2], templates: templates, file: { _ in (source, false) }, workDirectory: directory, network: 1
        )
        let body = String(decoding: try Data(contentsOf: XCTUnwrap(built.bodyFile)), as: UTF8.self)
        XCTAssertTrue(body.contains("name=\"caption\"\r\n\r\n7\r\n"))
        XCTAssertTrue(body.contains("name=\"raw\"\r\n\r\n{{x}}\r\n"))
        XCTAssertTrue(built.request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=pam-") == true)
        TransferRequestBuilder.cleanTemporaryBodies(in: directory)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["a.jpg"])
    }

    func testSecretsComeFromTheKeychainVault() throws {
        try SecretVault.put("api", "tok")
        defer { try? SecretVault.forget("api") }
        let spec = try TransferSpec.parse(#"{"kind":3,"steps":[{"method":2,"url":"https://a.example/x","headers":[{"name":"Authorization","prefix":"Bearer ","secret":{"vault":"api"}}]}]}"#)
        let directory = FileManager.default.temporaryDirectory
        let built = try TransferRequestBuilder.build(
            spec.steps[0], templates: TransferProgress().templates(id: "abc", tag: nil),
            file: { _ in throw TransferError("unused") }, workDirectory: directory, network: 1
        )
        XCTAssertEqual(built.request.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        try SecretVault.forget("api")
        XCTAssertThrowsError(try TransferRequestBuilder.build(
            spec.steps[0], templates: TransferProgress().templates(id: "abc", tag: nil),
            file: { _ in throw TransferError("unused") }, workDirectory: directory, network: 1
        ))
        XCTAssertThrowsError(try SecretVault.put("bad name!", "x"))
    }

    func testSealedPayloadsRoundTripAndAreNotPlaintext() throws {
        let sealed = try TransferCrypto.seal("secret-spec")
        XCTAssertFalse(String(decoding: sealed, as: UTF8.self).contains("secret-spec"))
        XCTAssertEqual(try TransferCrypto.open(sealed), "secret-spec")
    }

    func testStoreAndWatchChannelConflateSnapshots() {
        let store = TransferStore.shared
        let record = TransferRecord(id: "watch-\(UUID().uuidString.prefix(8))", kind: 2)
        store.put(record)
        defer { store.remove(record.id) }
        let channel = WatchChannel()
        channel.unsubscribe = store.observe(record.id) { channel.offer($0) }
        store.update(record.id) { $0.state = TransferStates.running }
        store.update(record.id) { $0.state = TransferStates.succeeded }
        let next = expectation(description: "watch")
        channel.next { status, payload in
            XCTAssertEqual(status, .success)
            XCTAssertEqual((try? WireMap.decode(payload))?["state"], .integer(Int64(TransferStates.succeeded)))
            next.fulfill()
        }
        wait(for: [next], timeout: 2)
        channel.close()
        let closed = expectation(description: "closed")
        channel.next { status, _ in
            XCTAssertEqual(status, .failure)
            closed.fulfill()
        }
        wait(for: [closed], timeout: 2)
    }

    func testTaskTagRoundTrip() {
        XCTAssertEqual(TaskTag.parse(TaskTag(id: "abc-123", step: 2, attempt: 3).description), TaskTag(id: "abc-123", step: 2, attempt: 3))
        XCTAssertNil(TaskTag.parse("garbage"))
    }

    func testModuleRejectsLegacyAndUnknownRequests() {
        let module = BackgroundTransferModule()
        let legacy = expectation(description: "legacy")
        module.invoke(method: "enqueue", payload: (try? WireMap.encode(["kind": .integer(1)])) ?? Data()) { status, _ in
            XCTAssertEqual(status, .failure)
            legacy.fulfill()
        }
        let missing = expectation(description: "missing file")
        let spec = #"{"kind":2,"steps":[{"method":3,"url":"https://a.example/u","body":{"type":"file","path":"nope/missing.bin","mimeType":"application/octet-stream"}}]}"#
        module.invoke(method: "enqueue", payload: (try? WireMap.encode(["spec": .text(spec)])) ?? Data()) { status, payload in
            XCTAssertEqual(status, .failure)
            XCTAssertTrue(String(decoding: payload, as: UTF8.self).contains("does not exist"))
            missing.fulfill()
        }
        wait(for: [legacy, missing], timeout: 5)
    }
}
