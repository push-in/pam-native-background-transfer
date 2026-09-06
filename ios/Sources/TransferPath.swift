import Foundation

enum TransferPath {
    static func resolve(_ path: String, root: URL) throws -> URL {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\0") else {
            throw PathError.outsideRoot
        }
        return try validate(root.appendingPathComponent(path), root: root)
    }

    static func validate(_ target: URL, root: URL) throws -> URL {
        let canonicalRoot = root.standardizedFileURL.resolvingSymlinksInPath()
        let canonicalTarget = target.standardizedFileURL.resolvingSymlinksInPath()
        guard canonicalTarget.path.hasPrefix(canonicalRoot.path + "/") else {
            throw PathError.outsideRoot
        }
        return canonicalTarget
    }

    private enum PathError: Error { case outsideRoot }
}
