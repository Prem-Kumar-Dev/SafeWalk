import CryptoKit
import Foundation

enum DataConfig {
    /// GitHub Pages folder with manifest.json (spec 02, "Hosting and URLs").
    static let primaryBase = URL(string: "https://prem-kumar-dev.github.io/SafeWalk/v1/chicago/")
    /// jsDelivr mirror of the same repository, tried when Pages fails.
    static let mirrorBase = URL(string: "https://cdn.jsdelivr.net/gh/Prem-Kumar-Dev/SafeWalk@main/v1/chicago/")
    /// Check the manifest at most this often.
    static let checkInterval: TimeInterval = 6 * 60 * 60
    static let defaultStyleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
    static let darkStyleURL = URL(string: "https://tiles.openfreemap.org/styles/dark")!
}

/// Loads and updates the graph + risk pair following spec 02, "How an app updates".
///
/// On disk: Application Support/SafeWalk/current/ holds manifest.json and the two files it names,
/// exactly as downloaded (still compressed). Updates are assembled in a staging folder and swapped
/// in with one rename, so a failed download never touches working data.
actor DataStore {
    static let shared = DataStore()

    enum UpdateOutcome: Equatable { case upToDate, updated, appTooOld, failed(String) }

    private let fm = FileManager.default
    private let defaults = UserDefaults.standard
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 30
        return URLSession(configuration: c)
    }()

    private var root: URL {
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SafeWalk", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    private var current: URL { root.appendingPathComponent("current", isDirectory: true) }
    private var bundled: URL? { Bundle.main.resourceURL?.appendingPathComponent("Data", isDirectory: true) }

    // MARK: Load

    /// The cached pair if it loads, otherwise the copy shipped in the app bundle.
    func loadCurrent() throws -> CityData {
        if let d = try? Self.load(folder: current) { return d }
        guard let bundled else { throw DataError.malformed("no bundled data") }
        return try Self.load(folder: bundled)
    }

    /// Reads a folder holding manifest.json and the compressed files it names.
    static func load(folder: URL) throws -> CityData {
        let mdata = try Data(contentsOf: folder.appendingPathComponent("manifest.json"))
        let m = try JSONDecoder().decode(Manifest.self, from: mdata)
        guard m.format_version == SafeWalkFormat.version else { throw DataError.appTooOld }
        let graphRaw = try inflate(Data(contentsOf: folder.appendingPathComponent(m.graph.path)), expected: m.graph.raw_bytes)
        let graph = try StreetGraph(raw: graphRaw, graphID: graphID(of: graphRaw))
        let risk = try? RiskTable(raw: inflate(Data(contentsOf: folder.appendingPathComponent(m.risk.path)),
                                               expected: m.risk.raw_bytes))
        return CityData(manifest: m, graph: graph, risk: risk)
    }

    // MARK: Update

    var lastUpdated: Date? { defaults.object(forKey: "data.lastUpdated") as? Date }

    /// Checks the manifest (at most every 6 hours unless `force`) and installs a newer pair.
    func checkForUpdate(force: Bool = false) async -> UpdateOutcome {
        if !force, let last = defaults.object(forKey: "data.lastCheck") as? Date,
           Date().timeIntervalSince(last) < DataConfig.checkInterval { return .upToDate }
        var lastError = "no data address configured"
        for base in [DataConfig.primaryBase, DataConfig.mirrorBase].compactMap({ $0 }) {
            do {
                let outcome = try await update(from: base)
                await refreshStyle(from: base)
                defaults.set(Date(), forKey: "data.lastCheck")
                return outcome
            } catch DataError.appTooOld {
                return .appTooOld
            } catch {
                lastError = error.localizedDescription
            }
        }
        return .failed(lastError)
    }

    private func update(from base: URL) async throws -> UpdateOutcome {
        var req = URLRequest(url: base.appendingPathComponent("manifest.json"))
        let etagKey = "data.etag.\(base.host ?? "")"
        if let tag = defaults.string(forKey: etagKey) { req.setValue(tag, forHTTPHeaderField: "If-None-Match") }
        let (mdata, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw DataError.download("no response") }
        if http.statusCode == 304 { return .upToDate }
        guard http.statusCode == 200 else { throw DataError.download("manifest HTTP \(http.statusCode)") }
        let m = try JSONDecoder().decode(Manifest.self, from: mdata)
        if m.min_app_format > SafeWalkFormat.version { throw DataError.appTooOld }

        let cached = try? JSONDecoder().decode(Manifest.self, from: Data(contentsOf: current.appendingPathComponent("manifest.json")))
        if cached == m { saveETag(http, key: etagKey); return .upToDate }

        let staging = root.appendingPathComponent("staging-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }

        // Graph: reuse ours when the id is unchanged, else download.
        let graphRaw: Data
        let oldGraph = cached.map { current.appendingPathComponent($0.graph.path) }
        if let cached, cached.graph.graph_id == m.graph.graph_id, cached.graph.sha256 == m.graph.sha256,
           let oldGraph, let blob = try? Data(contentsOf: oldGraph) {
            try blob.write(to: staging.appendingPathComponent(m.graph.path))
            graphRaw = try Self.inflate(blob, expected: m.graph.raw_bytes)
        } else {
            let blob = try await fetch(base.appendingPathComponent(m.graph.path), ref: m.graph)
            try blob.write(to: staging.appendingPathComponent(m.graph.path))
            graphRaw = try Self.inflate(blob, expected: m.graph.raw_bytes)
        }
        let gid = Self.graphID(of: graphRaw)
        guard gid == m.graph.graph_id else { throw DataError.mismatch }
        let graph = try StreetGraph(raw: graphRaw, graphID: gid)

        let riskBlob = try await fetch(base.appendingPathComponent(m.risk.path), ref: m.risk)
        let risk = try RiskTable(raw: Self.inflate(riskBlob, expected: m.risk.raw_bytes))
        guard risk.graphID == gid, risk.edgeCount == graph.edgeCount else { throw DataError.mismatch }
        try riskBlob.write(to: staging.appendingPathComponent(m.risk.path))
        try mdata.write(to: staging.appendingPathComponent("manifest.json"))

        // Every check passed: swap the pair in together.
        if fm.fileExists(atPath: current.path) {
            _ = try fm.replaceItemAt(current, withItemAt: staging)
        } else {
            try fm.moveItem(at: staging, to: current)
        }
        saveETag(http, key: etagKey)
        defaults.set(Date(), forKey: "data.lastUpdated")
        return .updated
    }

    private func saveETag(_ http: HTTPURLResponse, key: String) {
        if let tag = http.value(forHTTPHeaderField: "ETag") { defaults.set(tag, forKey: key) }
    }

    private func fetch(_ url: URL, ref: Manifest.FileRef) async throws -> Data {
        let (data, resp) = try await session.data(from: url)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw DataError.download(url.lastPathComponent) }
        guard data.count == ref.bytes else { throw DataError.malformed("size of \(url.lastPathComponent)") }
        guard Self.sha256Hex(data) == ref.sha256.lowercased() else { throw DataError.malformed("checksum of \(url.lastPathComponent)") }
        return data
    }

    // MARK: Helpers

    static func inflate(_ blob: Data, expected rawBytes: Int) throws -> Data {
        // Apple's .zlib algorithm is raw DEFLATE (RFC 1951), which is what the files use.
        guard let raw = try? (blob as NSData).decompressed(using: .zlib) as Data else {
            throw DataError.malformed("can't decompress")
        }
        guard raw.count == rawBytes else { throw DataError.malformed("decompressed size") }
        return raw
    }

    static func sha256Hex(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    static func graphID(of raw: Data) -> String { String(sha256Hex(raw).prefix(16)) }

    /// Remote-config map style (`style.json` next to the manifest), so tiles can be switched without a release.
    private func refreshStyle(from base: URL) async {
        struct StyleConfig: Decodable { let map_style_url: String; let map_style_url_dark: String? }
        guard let res = try? await session.data(from: base.appendingPathComponent("style.json")),
              (res.1 as? HTTPURLResponse)?.statusCode == 200,
              let cfg = try? JSONDecoder().decode(StyleConfig.self, from: res.0),
              let url = URL(string: cfg.map_style_url), url.scheme == "https" else { return }
        defaults.set(url.absoluteString, forKey: "map.style")
        defaults.set(cfg.map_style_url_dark, forKey: "map.style.dark")
    }

    /// Map style: remote config when one was downloaded, else OpenFreeMap Liberty (Dark at night).
    static func styleURL(dark: Bool) -> URL {
        let d = UserDefaults.standard
        if dark {
            if let s = d.string(forKey: "map.style.dark"), let u = URL(string: s) { return u }
            return DataConfig.darkStyleURL
        }
        if let s = d.string(forKey: "map.style"), let u = URL(string: s) { return u }
        return DataConfig.defaultStyleURL
    }
}
