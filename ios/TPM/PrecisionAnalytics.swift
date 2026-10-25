import Foundation
import SwiftUI
import CryptoKit

let precisionGrades = ["AA", "A", "B", "C", "D"]
func precisionBrand(_ name: String) -> String {
    let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
    switch value.lowercased() {
    case "台群", "taikan": return "台群"
    case "发那科", "fanuc": return "发那科"
    case "": return "未知品牌"
    default: return value
    }
}
func precisionIncluded(_ asset: JSONValue) -> Bool {
    !["钶锐锶", "钶锐鍶"].contains(asset["brand"]["brandName"].text.trimmingCharacters(in: .whitespacesAndNewlines))
}
func precisionTarget(_ asset: JSONValue) -> Bool {
    asset["assetCategory"]["categoryCode"].text == "017" && asset["assetCategory"]["categoryName"].text == "017CNC加工中心"
}
func precisionExecutionDate(_ value: JSONValue) -> String {
    guard let day = managementDay(value) else { return "" }
    if value.array.count >= 5 {
        let hour = value.array[3].text, minute = value.array[4].text
        return day + " " + String(repeating: "0", count: max(0, 2 - hour.count)) + hour + ":" + String(repeating: "0", count: max(0, 2 - minute.count)) + minute
    }
    return day + String(value.text.dropFirst(10))
}
func precisionAnalysisBody() -> JSONValue {
    func strings(_ values: [String]) -> JSONValue { .array(values.map { .string($0) }) }
    return .object(["inspectStates": strings(["NOT_START", "EXECUTING", "OVERDUE"]), "faultStates": .array([]), "maintainStates": .array([]),
        "analysisResults": strings(precisionGrades.map { "RESULT_" + $0 }), "type": .string("region"), "layerCode": .string(""),
        "categoryIds": .array([]), "useStates": strings(["IN_USE"]), "assetStates": strings(["NORMAL", "WARNING", "FAULT"])])
}
struct PrecisionSnapshot: Codable {
    let rows: [JSONValue]
    let total: Int?
    let brands: [String: Int]?
    let notice: String
    func gradeCount(_ grade: String, rows subset: [JSONValue]? = nil) -> Int { (subset ?? rows).filter { $0["analysisResult"].text == "RESULT_" + grade }.count }
    var brandNames: [String] { Set((brands.map { Array($0.keys) } ?? []) + rows.map { precisionBrand($0["asset"]["brand"]["brandName"].text) }).sorted() }
}

@MainActor func loadPrecisionSnapshot(source: LiveSession, check: () throws -> Void) async throws -> PrecisionSnapshot {
    try check()
    let response = try await source.request("precise/getPreciseAnalysis", body: precisionAnalysisBody())
    try check()
    guard case .array(let groups) = response, groups.allSatisfy({ if case .array = $0["preciseAnalysisList"] { true } else { false } }) else { throw APIError(message: "精密度分析返回格式异常") }
    let rows = groups.flatMap { $0["preciseAnalysisList"].array }.filter { precisionTarget($0["asset"]) && precisionIncluded($0["asset"]) }
    let ids = rows.map { $0["asset"]["assetId"].text }
    guard ids.allSatisfy({ !$0.isEmpty }), Set(ids).count == ids.count,
          rows.allSatisfy({ row in precisionGrades.map { "RESULT_" + $0 }.contains(row["analysisResult"].text) }) else {
        throw APIError(message: "分析结果缺少设备ID、包含未知等级或重复设备，请刷新核对")
    }
    guard !rows.isEmpty else { return PrecisionSnapshot(rows: [], total: nil, brands: nil, notice: "没有匹配结果，暂不能确认设备总数。") }
    do {
        let categories = Set(rows.map { $0["asset"]["assetCategory"]["categoryId"].text }).sorted()
        guard categories.allSatisfy({ !$0.isEmpty }) else { throw APIError(message: "分析结果缺少资产类别ID") }
        func inFilter(_ field: String, _ values: [String]) -> JSONValue {
            .object(["field": .string(field), "operator": .string("in"), "value": .array(values.map { .string($0) }), "logic": .string("and")])
        }
        let filters = [inFilter("assetCategory.categoryId", categories), inFilter("useState", ["IN_USE"]), inFilter("assetState", ["NORMAL", "WARNING", "FAULT"])]
        var assets: [String: JSONValue] = [:], expected: Int?, offset = 0
        repeat {
            try check()
            let page = try await source.request("asset/pageAsset", body: .object(["offset": .number(Double(offset)), "limit": .number(500), "sort": .string("assetCode"), "order": .string("asc"), "filters": .array(filters)]))
            try check()
            guard case .array(let items) = page["content"], let total = serverPageCount(page), expected == nil || expected == total,
                  items.count <= 500, !items.isEmpty || offset >= total else { throw APIError(message: "设备总数变化或分页异常，请刷新核对") }
            expected = total
            for asset in items {
                let id = asset["assetId"].text
                guard !id.isEmpty, assets[id] == nil, precisionTarget(asset), asset["useState"].text == "IN_USE", ["NORMAL", "WARNING", "FAULT"].contains(asset["assetState"].text) else { throw APIError(message: "资产筛选未正确执行或存在重复设备") }
                assets[id] = asset
            }
            offset += items.count
        } while offset < (expected ?? 0)
        guard assets.count == expected, rows.allSatisfy({ row in
            guard let asset = assets[row["asset"]["assetId"].text] else { return false }
            return precisionBrand(asset["brand"]["brandName"].text) == precisionBrand(row["asset"]["brand"]["brandName"].text)
        }) else { throw APIError(message: "分析结果与台账不一致，请刷新核对") }
        let included = assets.values.filter(precisionIncluded)
        let totals = Dictionary(grouping: included, by: { precisionBrand($0["brand"]["brandName"].text) }).mapValues(\.count)
        guard Dictionary(grouping: rows, by: { precisionBrand($0["asset"]["brand"]["brandName"].text) }).allSatisfy({ $0.value.count <= totals[$0.key, default: 0] }) else { throw APIError(message: "品牌统计发生变化") }
        return PrecisionSnapshot(rows: rows, total: included.count, brands: totals, notice: "")
    } catch is CancellationError { throw CancellationError() }
    catch { return PrecisionSnapshot(rows: rows, total: nil, brands: nil, notice: "设备总数核对失败，覆盖率暂不可用：" + error.localizedDescription) }
}

@MainActor final class PrecisionDashboardModel: ObservableObject {
    @Published var snapshot: PrecisionSnapshot?
    @Published var loading = false
    @Published var error: String?
    @Published var updatedAt: Date?
    @Published private(set) var showingPrevious = false
    private struct Saved: Codable { let snapshot: PrecisionSnapshot; let updatedAt: Date }
    private func cacheURL(owner: LiveSession, queries: QueryAccountModel) -> URL? {
        let key = SHA256.hash(data: Data((owner.draftScope + ":" + queries.username).utf8)).map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("PrecisionCache", isDirectory: true).appendingPathComponent(key + ".json")
    }
    private var context = ""
    private var generation = UUID()
    func enter(owner: LiveSession, queries: QueryAccountModel) async {
        let key = owner.scopeRevision.uuidString + queries.revision.uuidString
        if context == key { return }
        snapshot = nil; updatedAt = nil; showingPrevious = false; context = key
        if let url = cacheURL(owner: owner, queries: queries), let data = try? Data(contentsOf: url),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            snapshot = saved.snapshot; updatedAt = saved.updatedAt; showingPrevious = true
        }
        await refresh(owner: owner, queries: queries)
    }
    func refresh(owner: LiveSession, queries: QueryAccountModel) async {
        let key = owner.scopeRevision.uuidString + queries.revision.uuidString, token = UUID()
        if context != key { snapshot = nil; updatedAt = nil; showingPrevious = false }
        context = key; generation = token; loading = true; error = nil
        showingPrevious = snapshot != nil
        defer { if generation == token { loading = false } }
        do {
            let source = try await queries.source(for: owner)
            let result = try await loadPrecisionSnapshot(source: source) {
                try Task.checkCancellation()
                guard self.generation == token, key == owner.scopeRevision.uuidString + queries.revision.uuidString else { throw CancellationError() }
            }
            try Task.checkCancellation()
            guard generation == token, key == owner.scopeRevision.uuidString + queries.revision.uuidString else { throw CancellationError() }
            let date = Date()
            snapshot = result; updatedAt = date; showingPrevious = false
            if let url = cacheURL(owner: owner, queries: queries), let data = try? JSONEncoder().encode(Saved(snapshot: result, updatedAt: date)) {
                try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? data.write(to: url, options: [.atomic, .completeFileProtection])
            }
        } catch is CancellationError { } catch { if generation == token { self.error = error.localizedDescription } }
    }
}
