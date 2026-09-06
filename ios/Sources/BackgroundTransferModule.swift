import Foundation
import PamNative

public final class BackgroundTransferModule: NativeModule, @unchecked Sendable {
    private let coordinator = TransferCoordinator.shared
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            switch method {
            case "enqueue":
                guard case let .integer(kind)?=values["kind"], case let .text(urlText)?=values["url"], case let .text(path)?=values["path"], let url=URL(string:urlText), url.scheme=="https", (kind == 1 || kind == 2) else { throw TransferError.invalidRequest }
                let encodedHeaders: String
                if let value = values["headers"] {
                    guard case let .text(text) = value else { throw TransferError.invalidRequest }
                    encodedHeaders = text
                } else { encodedHeaders = "{}" }
                let headers = try TransferHeaders.decode(encodedHeaders)
                let id = try coordinator.enqueue(kind:kind,url:url,path:path,headers:headers)
                succeed(["identifier":.text(id)],completion)
            case "status":
                guard case let .text(id)?=values["identifier"] else { throw TransferError.invalidRequest }
                guard let snapshot = coordinator.snapshot(id: id) else { throw TransferError.invalidRequest }
                succeed(snapshot, completion)
            case "cancel":
                guard case let .text(id)?=values["identifier"] else { throw TransferError.invalidRequest }
                coordinator.cancel(id: id) { self.succeed([:], completion) }
            default: throw TransferError.invalidRequest
            }
        } catch { completion(.failure,Data("Background transfer failure".utf8)) }
    }
    private func succeed(_ values:[String:WireValue],_ completion:ModuleCompletion){do{completion(.success,try WireMap.encode(values))}catch{completion(.failure,Data("Background transfer failure".utf8))}}
}

private final class TransferCoordinator:NSObject,URLSessionDownloadDelegate,URLSessionTaskDelegate,@unchecked Sendable {
    static let shared=TransferCoordinator(); private let defaults=UserDefaults.standard; private let lock=NSLock()
    private lazy var session:URLSession={let config=URLSessionConfiguration.background(withIdentifier:"dev.pam.background-transfer.v1");config.sessionSendsLaunchEvents=true;config.isDiscretionary=false;return URLSession(configuration:config,delegate:self,delegateQueue:nil)}()
    func enqueue(kind:Int64,url:URL,path:String,headers:[String:String])throws->String{let target=try safeURL(path);let id=UUID().uuidString;let metadata="\(id)\u{0}\(kind)\u{0}\(target.path)";let task:URLSessionTask;if kind==1{task=session.downloadTask(with:TransferHeaders.request(url:url,method:"GET",headers:headers))}else{let request=TransferHeaders.request(url:url,method:"PUT",headers:headers);task=session.uploadTask(with:request,fromFile:target)};task.taskDescription=metadata;save(id:id,kind:kind,state:1,transferred:0,total:0,message:"");task.resume();return id}
    func snapshot(id:String)->[String:WireValue]?{lock.lock();defer{lock.unlock()};let p="dev.pam.transfer.\(id).";guard defaults.object(forKey:p+"state") != nil else{return nil};return ["identifier":.text(id),"kind":.integer(Int64(defaults.integer(forKey:p+"kind"))),"state":.integer(Int64(defaults.integer(forKey:p+"state"))),"bytesTransferred":.integer(Int64(defaults.integer(forKey:p+"transferred"))),"bytesTotal":.integer(Int64(defaults.integer(forKey:p+"total"))),"message":.text(defaults.string(forKey:p+"message") ?? "")]}
    func cancel(id: String, completion: @escaping () -> Void) {
        session.getAllTasks { tasks in
            if let snapshot = self.details(id: id) {
                self.save(id: id, kind: snapshot.kind, state: TransferPhase.cancelled.rawValue,
                          transferred: snapshot.transferred, total: snapshot.total, message: "Cancelled")
            }
            tasks.filter { self.parts($0).id == id }.forEach { $0.cancel() }
            completion()
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let p = parts(downloadTask)
        guard !p.id.isEmpty else { return }
        guard TransferHttpResult.accepts(downloadTask.response) else { return }
        do {
            lock.lock()
            defer { lock.unlock() }
            let key = "dev.pam.transfer.\(p.id).state"
            guard TransferPhase.allows(current: defaults.object(forKey: key) as? Int,
                                       next: TransferPhase.running.rawValue) else { return }
            let target = try TransferPath.validate(URL(fileURLWithPath: p.path), root: transferRoot)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: location)
            } else {
                try FileManager.default.moveItem(at: location, to: target)
            }
        } catch {
            save(id: p.id, kind: p.kind, state: 4, transferred: downloadTask.countOfBytesReceived,
                 total: downloadTask.countOfBytesExpectedToReceive, message: "Could not save downloaded file")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let p = parts(task)
        guard !p.id.isEmpty else { return }
        let current = snapshot(id: p.id)
        let failedToSave: Bool
        if case .integer(4)? = current?["state"] { failedToSave = true } else { failedToSave = false }
        let result = TransferHttpResult.completion(response: task.response, error: error, failedToSave: failedToSave)
        save(id: p.id, kind: p.kind, state: result.state,
             transferred: max(task.countOfBytesReceived, task.countOfBytesSent),
             total: max(task.countOfBytesExpectedToReceive, task.countOfBytesExpectedToSend), message: result.message)
    }
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64){let p=parts(downloadTask);save(id:p.id,kind:p.kind,state:2,transferred:totalBytesWritten,total:totalBytesExpectedToWrite,message:"")}
    private var transferRoot: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }
    private func safeURL(_ path: String) throws -> URL {
        try TransferPath.resolve(path, root: transferRoot)
    }
    private func parts(_ task:URLSessionTask)->(id:String,kind:Int64,path:String){let p=(task.taskDescription ?? "").split(separator:"\0",omittingEmptySubsequences:false);return p.count==3 ? (String(p[0]),Int64(p[1]) ?? 1,String(p[2])):("",1,"")}
    private func save(id:String,kind:Int64,state:Int64,transferred:Int64,total:Int64,message:String){guard !id.isEmpty else{return};lock.lock();defer{lock.unlock()};let p="dev.pam.transfer.\(id).";guard TransferPhase.allows(current: defaults.object(forKey: p+"state") as? Int, next: state) else { return };defaults.set(kind,forKey:p+"kind");defaults.set(state,forKey:p+"state");defaults.set(transferred,forKey:p+"transferred");defaults.set(total,forKey:p+"total");defaults.set(message,forKey:p+"message")}
    private func details(id:String)->(kind:Int64,transferred:Int64,total:Int64)?{guard let s=snapshot(id:id), case let .integer(kind)?=s["kind"],case let .integer(transferred)?=s["bytesTransferred"],case let .integer(total)?=s["bytesTotal"] else{return nil};return(kind,transferred,total)}
}
private enum TransferError:Error{case invalidRequest;case invalidPath}
