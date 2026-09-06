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
        // Foundation may leave a path unresolved when its final file does not exist yet.
        // Resolve the nearest existing ancestor before appending new destination segments.
        var ancestor = target.standardizedFileURL
        var missing: [String] = []
        while !FileManager.default.fileExists(atPath: ancestor.path) {
            if (try? FileManager.default.destinationOfSymbolicLink(atPath: ancestor.path)) != nil {
                throw PathError.outsideRoot
            }
            guard ancestor.path != "/" else { throw PathError.outsideRoot }
            missing.append(ancestor.lastPathComponent)
            ancestor.deleteLastPathComponent()
        }
        var canonicalTarget = ancestor.resolvingSymlinksInPath()
        for segment in missing.reversed() { canonicalTarget.appendPathComponent(segment) }
        guard canonicalTarget.path.hasPrefix(canonicalRoot.path + "/") else {
            throw PathError.outsideRoot
        }
        return canonicalTarget
    }

    private enum PathError: Error { case outsideRoot }
}
