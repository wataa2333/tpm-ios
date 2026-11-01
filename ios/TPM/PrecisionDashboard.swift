import SwiftUI
import Charts

struct PrecisionDashboardScreen: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @StateObject private var model = PrecisionDashboardModel()
    @State private var search = ""
    @State private var searching = false
    @State private var grade = "全部"
    @State private var shown = 20
    private var context: String { session.scopeRevision.uuidString + queries.revision.uuidString }
    private var matching: [JSONValue] {
        let text = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let typedGrade = precisionGrades.contains(text.uppercased()) ? text.uppercased() : nil
        return (model.snapshot?.rows ?? []).filter { row in
            let result = row["analysisResult"].text.replacingOccurrences(of: "RESULT_", with: "")
            let matches = typedGrade.map { $0 == result } ?? ["assetCode", "assetName", "addressCode"].contains { row["asset"][$0].text.localizedCaseInsensitiveContains(text) }
            return (grade == "全部" || grade == result) && (text.isEmpty || matches)
        }.sorted { a, b in
            let ad = managementDay(a["analysisDate"]) ?? "", bd = managementDay(b["analysisDate"]) ?? ""
            return ad == bd ? a["asset"]["assetCode"].text < b["asset"]["assetCode"].text : ad > bd
        }
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                if model.showingPrevious { PreviousDataNotice(date: model.updatedAt) }
                PrecisionPanel {
                    Text("017CNC 精密度检查看板").font(AppFont.headline)
                    Text("查询：\(queries.label(operator: session)) · \(session.currentFactoryName)").font(AppFont.caption).foregroundStyle(.secondary)
                    if let date = model.updatedAt { Text("更新：" + date.formatted(date: .numeric, time: .shortened)).font(AppFont.caption).foregroundStyle(.secondary) }
                    Button("刷新数据") { Task { await model.refresh(owner: session, queries: queries) } }.disabled(model.loading)
                    if model.loading { ProgressView("正在读取") }
                    if let error = model.error { Text(error).foregroundStyle(.red) }
                }
                if let data = model.snapshot {
                    PrecisionPanel {
                        Text("检测进度").font(AppFont.headline)
                        Text("已检 \(data.rows.count) 台").font(AppFont.title2.bold())
                        if let total = data.total, total > 0 {
                            Text("共\(total)台 · 未检\(max(0, total - data.rows.count))台")
                            ProgressView(value: Double(data.rows.count), total: Double(total))
                            Text("覆盖率：" + precisionPercent(data.rows.count, total)).font(AppFont.subheadline)
                        }
                        if !data.notice.isEmpty { Text(data.notice).font(AppFont.caption).foregroundStyle(.orange) }
                    }
                    PrecisionPanel {
                        Text("精度等级分布").font(AppFont.headline)
                        PrecisionGradeSummary(data: data, rows: data.rows, total: data.total)
                    }
                    ForEach(data.brandNames, id: \.self) { brand in
                        PrecisionPanel {
                            Text(brand).font(AppFont.headline)
                            let rows = data.rows.filter { precisionBrand($0["asset"]["brand"]["brandName"].text) == brand }
                            let total = data.brands?[brand]
                            Text("已检\(rows.count)台" + (total.map { " / 共\($0)台" } ?? " · 总数未知")).font(AppFont.subheadline)
                            if let total, total > 0 {
                                Text("已检 " + precisionPercent(rows.count, total) + " · 未检 " + precisionPercent(max(0, total - rows.count), total)).font(AppFont.caption)
                            }
                            PrecisionGradeSummary(data: data, rows: rows, total: total)
                        }
                    }
                    PrecisionDateDistribution(rows: data.rows)
                    PrecisionPanel {
                        Text("设备检测明细").font(AppFont.headline)
                        ScrollView(.horizontal) {
                            HStack(spacing: 8) {
                                ForEach(["全部"] + precisionGrades, id: \.self) { value in
                                    ManagementChoice(title: value, selected: grade == value) { grade = value }
                                }
                            }
                        }
                        Text("共\(matching.count)台 · 已显示\(min(shown, matching.count))台").font(AppFont.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array(matching.prefix(shown).enumerated()), id: \.offset) { _, row in
                        NavigationLink { PrecisionDeviceScreen(asset: row["asset"]) } label: {
                            HStack(alignment: .center, spacing: 12) {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(row["asset"]["addressCode"].text.isEmpty ? row["asset"]["assetCode"].text : row["asset"]["addressCode"].text).font(AppFont.precisionText(.title3, size: 20))
                                    Text(row["asset"]["assetName"].text).font(AppFont.precisionText(.subheadline, size: 15))
                                    Text(row["asset"]["assetCode"].text).font(AppFont.precisionText(.caption, size: 12))
                                    Text("检测日期：" + readableDate(row["analysisDate"])).font(AppFont.precisionText(.caption, size: 12))
                                    PrecisionPeopleLabel(assetID: row["asset"]["assetId"].text, refreshID: model.updatedAt).id(row["asset"]["assetId"].text)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                Text(row["analysisResult"].text.replacingOccurrences(of: "RESULT_", with: ""))
                                    .font(AppFont.precisionGrade)
                                    .foregroundStyle(Color.accentColor.opacity(0.65))
                                    .lineLimit(1).minimumScaleFactor(0.65).frame(minWidth: 80)
                                    .accessibilityLabel("精度判定等级 " + row["analysisResult"].text.replacingOccurrences(of: "RESULT_", with: ""))
                            }.contentShape(.rect).padding(14)
                                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain)
                    }
                    if shown < matching.count { Button("加载更多（20台）") { shown += 20 }.frame(maxWidth: .infinity).buttonStyle(.bordered) }
                }
            }.padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("精密度检查看板").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .nativeSearchable(text: $search, isPresented: $searching, prompt: "机台号、编码、名称或等级")
        .scrollDismissesKeyboard(.interactively)
        .task(id: context) { await model.enter(owner: session, queries: queries) }
        .onChange(of: search) { shown = 20 }.onChange(of: grade) { shown = 20 }
        .refreshable { await model.refresh(owner: session, queries: queries) }
    }
}

struct PrecisionPanel<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10, content: content).frame(maxWidth: .infinity, alignment: .leading)
            .padding(14).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}
func precisionPercent(_ count: Int, _ total: Int) -> String { total > 0 ? String(format: "%.1f%%", Double(count) * 100 / Double(total)) : "—" }
private struct PrecisionGradeSummary: View {
    let data: PrecisionSnapshot
    let rows: [JSONValue]
    let total: Int?
    private let colors: [Color] = [.teal, .green, .blue, .orange, .red]
    var body: some View {
        VStack(spacing: 8) {
            ForEach(Array(precisionGrades.enumerated()), id: \.offset) { index, grade in
                let count = data.gradeCount(grade, rows: rows)
                HStack {
                    Text(grade).frame(width: 28, alignment: .leading)
                    ProgressView(value: Double(count), total: Double(max(1, total ?? rows.count))).tint(colors[index])
                    Text("\(count)台").font(AppFont.caption).monospacedDigit().frame(minWidth: 42, alignment: .trailing)
                }
            }
            if let total {
                let missing = max(0, total - rows.count)
                HStack { Text("未检"); Spacer(); Text("\(missing)台 · " + precisionPercent(missing, total)).font(AppFont.caption) }
            }
        }
    }
}

private struct PrecisionDateDistribution: View {
    let rows: [JSONValue]
    @State private var days = 30
    @State private var visibleDays = 14.0
    @State private var magnifyStart: Double?
    @State private var chartScrollIndex = 0.0
    @State private var zoomAnchor = 0.0
    @State private var selectedIndex: Int?
    private var selectedPoint: (String, Int)? {
        guard let selectedIndex, dates.indices.contains(selectedIndex) else { return nil }
        return dates[selectedIndex]
    }
    private var dates: [(String, Int)] {
        let grouped = Dictionary(grouping: rows.compactMap { managementDay($0["analysisDate"]) }, by: { $0 }).mapValues(\.count)
        let from = days == 0 ? "" : serverDate(Calendar.current.date(byAdding: .day, value: -(days - 1), to: Date()) ?? Date(), dateOnly: true)
        let to = serverDate(Date(), dateOnly: true)
        guard let first = grouped.keys.filter({ $0 >= from && $0 <= to }).min() else { return [] }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
        guard var date = formatter.date(from: first), let end = formatter.date(from: to) else { return [] }
        var result: [(String, Int)] = []
        while date <= end {
            let key = formatter.string(from: date); result.append((key, grouped[key, default: 0]))
            guard let next = Calendar.current.date(byAdding: .day, value: 1, to: date) else { break }; date = next
        }
        return result
    }
    var body: some View {
        // Reuse one date distribution throughout this render, including every axis label.
        let dates = self.dates
        PrecisionPanel {
            Text("检测日期分布").font(AppFont.headline)
            Picker("日期范围", selection: $days) { Text("7天").tag(7); Text("30天").tag(30); Text("90天").tag(90); Text("全部").tag(0) }.pickerStyle(.segmented)
            Text("当前分析结果的检测日期分布，并非历史每日新增；点选查看台数，左右拖动、双指缩放。").font(AppFont.caption).foregroundStyle(.secondary)
            if dates.isEmpty { Text("该范围暂无检测日期").font(AppFont.subheadline) }
            else {
                Chart {
                    ForEach(Array(dates.enumerated()), id: \.offset) { index, point in
                    LineMark(x: .value("日期", index), y: .value("台数", point.1)).foregroundStyle(Color.accentColor).interpolationMethod(.monotone)
                    PointMark(x: .value("日期", index), y: .value("台数", point.1)).foregroundStyle(Color.accentColor).symbolSize(selectedIndex == index ? 75 : 12)
                    }
                    if let selectedIndex, dates.indices.contains(selectedIndex) {
                        RuleMark(x: .value("选中日期", selectedIndex)).foregroundStyle(Color.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    }
                }
                .chartXScale(domain: 0...max(1, dates.count - 1))
                .chartYScale(domain: 0...max(1, dates.map { $0.1 }.max() ?? 1))
                .chartScrollableAxes(.horizontal).chartXVisibleDomain(length: min(visibleDays, Double(max(2, dates.count))))
                .chartScrollPosition(x: $chartScrollIndex)
                .chartXSelection(value: $selectedIndex)
                .chartGesture { proxy in
                    SpatialTapGesture().onEnded { value in proxy.selectXValue(at: value.location.x) }
                }
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine(); AxisTick(); AxisValueLabel { if let index = value.as(Int.self), dates.indices.contains(index) { Text(String(dates[index].0.suffix(5))).font(AppFont.caption2) } }
                } }
                .chartYAxis { AxisMarks { value in
                    AxisGridLine(); AxisTick()
                    AxisValueLabel { if let count = value.as(Int.self) { Text(String(count)).font(AppFont.caption2) } }
                } }
                .frame(height: 220)
                .simultaneousGesture(MagnifyGesture().onChanged { value in
                    if magnifyStart == nil {
                        magnifyStart = min(visibleDays, Double(max(2, dates.count)))
                        zoomAnchor = chartScrollIndex + (magnifyStart ?? visibleDays) / 2
                    }
                    let length = min(Double(max(2, dates.count)), max(2, (magnifyStart ?? 14) / Double(value.magnification)))
                    var transaction = Transaction(); transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        visibleDays = length
                        chartScrollIndex = min(max(0, Double(dates.count - 1) - length), max(0, zoomAnchor - length / 2))
                    }
                }.onEnded { _ in magnifyStart = nil })
                .transaction { if magnifyStart != nil { $0.animation = nil; $0.disablesAnimations = true } }
                if let point = selectedPoint {
                    HStack {
                        Text(point.0).font(AppFont.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(point.1) 台").font(AppFont.headline).foregroundStyle(Color.accentColor).monospacedDigit()
                    }.accessibilityElement(children: .combine).accessibilityIdentifier("precision-selected-value")
                }
            }
        }
        .onChange(of: days) { selectedIndex = nil; chartScrollIndex = 0; magnifyStart = nil }
        .onChange(of: dates.map { $0.0 + ":" + String($0.1) }) { selectedIndex = nil }
    }
}
