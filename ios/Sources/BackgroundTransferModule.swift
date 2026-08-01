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
                guard case let .integer(kind)?=values["kind"], case let .text(urlText)?=values["url"], case let .text(path)?=values["path"], let url=URL(string:urlText), url.scheme=="https" else { throw TransferError.invalidRequest }
                let id = try coordinator.enqueue(kind:kind,url:url,path:path)
                succeed(["identifier":.text(id)],completion)
            case "status":
                guard case let .text(id)?=values["identifier"] else { throw TransferError.invalidRequest }
                succeed(coordinator.snapshot(id:id),completion)
            case "cancel":
                guard case let .text(id)?=values["identifier"] else { throw TransferError.invalidRequest }
                coordinator.cancel(id:id); succeed([:],completion)
            default: throw TransferError.invalidRequest
            }
        } catch { completion(.failure,Data(String(describing:error).utf8)) }
    }
    private func succeed(_ values:[String:WireValue],_ completion:ModuleCompletion){do{completion(.success,try WireMap.encode(values))}catch{completion(.failure,Data(String(describing:error).utf8))}}
}

private final class TransferCoordinator:NSObject,URLSessionDownloadDelegate,URLSessionTaskDelegate,@unchecked Sendable {
    static let shared=TransferCoordinator(); private let defaults=UserDefaults.standard; private let lock=NSLock()
    private lazy var session:URLSession={let config=URLSessionConfiguration.background(withIdentifier:"dev.pam.background-transfer.v1");config.sessionSendsLaunchEvents=true;config.isDiscretionary=false;return URLSession(configuration:config,delegate:self,delegateQueue:nil)}()
    func enqueue(kind:Int64,url:URL,path:String)throws->String{let target=try safeURL(path);let id=UUID().uuidString;let metadata="\(id)\u{0}\(kind)\u{0}\(target.path)";let task:URLSessionTask;if kind==1{task=session.downloadTask(with:url)}else{var request=URLRequest(url:url);request.httpMethod="PUT";task=session.uploadTask(with:request,fromFile:target)};task.taskDescription=metadata;save(id:id,kind:kind,state:1,transferred:0,total:0,message:"");task.resume();return id}
    func snapshot(id:String)->[String:WireValue]{lock.lock();defer{lock.unlock()};let p="dev.pam.transfer.\(id).";guard defaults.object(forKey:p+"state") != nil else{return ["identifier":.text(id),"kind":.integer(1),"state":.integer(4),"bytesTransferred":.integer(0),"bytesTotal":.integer(0),"message":.text("Transfer not found")]};return ["identifier":.text(id),"kind":.integer(Int64(defaults.integer(forKey:p+"kind"))),"state":.integer(Int64(defaults.integer(forKey:p+"state"))),"bytesTransferred":.integer(Int64(defaults.integer(forKey:p+"transferred"))),"bytesTotal":.integer(Int64(defaults.integer(forKey:p+"total"))),"message":.text(defaults.string(forKey:p+"message") ?? "")]}
    func cancel(id:String){session.getAllTasks{tasks in tasks.filter{self.parts($0).id==id}.forEach{$0.cancel()};if let s=self.details(id:id){self.save(id:id,kind:s.kind,state:5,transferred:s.transferred,total:s.total,message:"Cancelled")}}}
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL){let p=parts(downloadTask);guard !p.id.isEmpty else{return};do{let target=URL(fileURLWithPath:p.path);try FileManager.default.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true);try? FileManager.default.removeItem(at:target);try FileManager.default.moveItem(at:location,to:target)}catch{save(id:p.id,kind:p.kind,state:4,transferred:downloadTask.countOfBytesReceived,total:downloadTask.countOfBytesExpectedToReceive,message:String(describing:error))}}
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){let p=parts(task);guard !p.id.isEmpty else{return};let state:Int64=error == nil ? 3 : ((error as? URLError)?.code == .cancelled ? 5 : 4);save(id:p.id,kind:p.kind,state:state,transferred:max(task.countOfBytesReceived,task.countOfBytesSent),total:max(task.countOfBytesExpectedToReceive,task.countOfBytesExpectedToSend),message:error.map{String(describing:$0)} ?? "")}
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64){let p=parts(downloadTask);save(id:p.id,kind:p.kind,state:2,transferred:totalBytesWritten,total:totalBytesExpectedToWrite,message:"")}
    private func safeURL(_ path:String)throws->URL{let root=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0];let target=root.appendingPathComponent(path).standardizedFileURL;guard target.path.hasPrefix(root.standardizedFileURL.path+"/") else{throw TransferError.invalidPath};return target}
    private func parts(_ task:URLSessionTask)->(id:String,kind:Int64,path:String){let p=(task.taskDescription ?? "").split(separator:"\0",omittingEmptySubsequences:false);return p.count==3 ? (String(p[0]),Int64(p[1]) ?? 1,String(p[2])):("",1,"")}
    private func save(id:String,kind:Int64,state:Int64,transferred:Int64,total:Int64,message:String){guard !id.isEmpty else{return};lock.lock();defer{lock.unlock()};let p="dev.pam.transfer.\(id).";defaults.set(kind,forKey:p+"kind");defaults.set(state,forKey:p+"state");defaults.set(transferred,forKey:p+"transferred");defaults.set(total,forKey:p+"total");defaults.set(message,forKey:p+"message")}
    private func details(id:String)->(kind:Int64,transferred:Int64,total:Int64)?{let s=snapshot(id:id);guard case let .integer(kind)?=s["kind"],case let .integer(transferred)?=s["bytesTransferred"],case let .integer(total)?=s["bytesTotal"] else{return nil};return(kind,transferred,total)}
}
private enum TransferError:Error{case invalidRequest;case invalidPath}
