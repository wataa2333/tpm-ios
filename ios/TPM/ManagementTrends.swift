import SwiftUI
import Charts
import UIKit

enum ManagedGrain: String, CaseIterable, Identifiable {
    case day, month
    var id: String { rawValue }
    var title: String { self == .day ? "按日" : "按月" }
}

struct ManagedTrendBucket: Identifiable {
    let id: String
    let date: Date
    let label: String
    var counts: [String: Int] = [:]
    var total = 0
}

/// One calculation for the chart, both table orientations and their fixed totals.
func managedTrendBuckets(report: ManagedReport, window: ManagedWindow, grain: ManagedGrain, calendar suppliedCalendar: Calendar = .current) -> [ManagedTrendBucket] {
    var calendar = Calendar(identifier: .gregorian); calendar.timeZone = suppliedCalendar.timeZone
    let formatter = DateFormatter()
    formatter.calendar = calendar; formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = calendar.timeZone
    formatter.dateFormat = "yyyy-MM-dd"
    guard let start = formatter.date(from: window.from), let end = formatter.date(from: window.to) else { return [] }
    let component: Calendar.Component = grain == .day ? .day : .month
    var date = grain == .month ? calendar.date(from: calendar.dateComponents([.year, .month], from: start)) ?? start : start
    let label = DateFormatter(); label.calendar = calendar; label.timeZone = calendar.timeZone
    label.locale = Locale(identifier: "zh_CN"); label.dateFormat = grain == .day ? "MM-dd EEE" : "yyyy-MM"
    var buckets: [ManagedTrendBucket] = []
    while date <= end {
        let id = String(formatter.string(from: date).prefix(grain == .day ? 10 : 7))
        buckets.append(ManagedTrendBucket(id: id, date: date, label: label.string(from: date)))
        guard let next = calendar.date(byAdding: component, value: 1, to: date), next > date else { break }
        date = next
    }
    let indices = Dictionary(uniqueKeysWithValues: buckets.enumerated().map { ($0.element.id, $0.offset) })
    for person in report.people {
        for doc in person.documents where ["RESOLVED", "FINISHED"].contains(doc.state) {
            let key = String(doc.end.prefix(grain == .day ? 10 : 7))
            guard let index = indices[key] else { continue }
            buckets[index].counts[person.id, default: 0] += 1
            buckets[index].total += 1
        }
    }
    return buckets
}

struct ManagedTrendPoint: Identifiable {
    let series: String
    let index: Int
    let value: Int
    var segment = 0
    var id: String { series + ":" + String(index) }
}

struct ManagementTrendScreen: View {
    @ObservedObject var model: ManagementAnalyticsModel
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    @AppStorage(ManagementSettings.enabledKey) private var enabled = false
    @State private var grain = ManagedGrain.day
    @State private var selected = Set<String>()
    @State private var showTotal = true
    @State private var swapped = false
    @State private var visibleCount: Double = 7
    @State private var zoomStart: Double?
    @State private var originalOrientation: UIInterfaceOrientation?
    @State private var orientationError: String?
    @State private var suggestLandscape = false
    @State private var initialized = false
    private var people: [ManagedPerson] { model.report.people.filter { $0.id != ManagedReport.unknown } }
    private var buckets: [ManagedTrendBucket] {
        guard let window = model.window else { return [] }
        return managedTrendBuckets(report: model.report, window: window, grain: grain)
    }
    private var points: [ManagedTrendPoint] {
        var result: [ManagedTrendPoint] = []
        var segments: [String: Int] = [:]
        for (index, bucket) in buckets.enumerated() {
            if showTotal {
                if bucket.total > 0 || !model.incomplete {
                    result.append(ManagedTrendPoint(series: "全部合计", index: index, value: bucket.total, segment: segments["total", default: 0]))
                } else { segments["total", default: 0] += 1 }
            }
            for person in people where selected.contains(person.id) {
                if let count = bucket.counts[person.id] ?? (model.incomplete ? nil : 0) {
                    result.append(ManagedTrendPoint(series: seriesTitle(person), index: index, value: count, segment: segments[person.id, default: 0]))
                } else { segments[person.id, default: 0] += 1 }
            }
        }
        return result
    }
    private var seriesNames: [String] { (showTotal ? ["全部合计"] : []) + people.filter { selected.contains($0.id) }.map(seriesTitle) }
    private var seriesColors: [Color] {
        let palette: [Color] = [.accentColor, .orange, .purple, .teal, .pink, .indigo, .brown, .mint]
        return seriesNames.indices.map { palette[$0 % palette.count] }
    }
    private var legend: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach(seriesNames.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Circle().fill(seriesColors[index]).frame(width: 8, height: 8)
                    Text(seriesNames[index]).font(.caption).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height
            Group {
                if landscape {
                    HStack(alignment: .top, spacing: 16) {
                        ScrollView { controls.padding(12) }.frame(width: min(320, geometry.size.width * 0.4))
                        ScrollView { results.padding(.vertical, 12).padding(.trailing, 16) }
                    }
                } else {
                    ScrollView { VStack(alignment: .leading, spacing: 20) { controls; results }.padding(16) }
                }
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .interfaceMotion(landscape)
        }
        .navigationTitle("维修处理趋势").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("横屏", systemImage: "rectangle.landscape.rotate") { requestLandscape() } } }
        .alert("建议横屏查看", isPresented: $suggestLandscape) {
            Button("继续竖屏", role: .cancel) { }
            Button("切换横屏") { requestLandscape() }
        } message: { Text("日期跨度超过 7 天，横屏可同时查看人员选择、图表和统计表。") }
        .errorDialog($orientationError, title: "屏幕方向", clearsOnClose: true)
        .onAppear {
            if !initialized {
                initialized = true
                grain = (model.window?.dayCount ?? 0) > 90 ? .month : .day
                suggestLandscape = (model.window?.dayCount ?? 0) > 7
            }
        }
        .onChange(of: grain) { visibleCount = grain == .day ? 7 : 12; zoomStart = nil }
        .onChange(of: session.scopeRevision) { model.clear(); dismiss() }
        .onChange(of: enabled) { _, value in if !value { dismiss() } }
        .onDisappear { restoreOrientation() }
    }
    private var controls: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let window = model.window { Text(window.from + " 至 " + window.to).font(.subheadline.weight(.medium)) }
            Text(model.status).font(.caption).foregroundStyle(.secondary)
            Text("RESOLVED＋FINISHED · 维修结束日期").font(.caption).foregroundStyle(.secondary)
            HStack {
                ForEach(ManagedGrain.allCases) { option in
                    ManagementChoice(title: option.title, selected: grain == option) { grain = option }
                }
            }
            ManagementChoice(title: "全部合计", selected: showTotal) { showTotal.toggle() }
            HStack {
                Button("全选人员") { selected = Set(people.map(\.id)) }
                Spacer(minLength: 8)
                Button("清空人员") { selected.removeAll() }
            }.font(.subheadline).buttonStyle(.bordered)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], spacing: 8) {
                ForEach(people) { person in
                    ManagementChoice(title: seriesTitle(person), selected: selected.contains(person.id)) {
                        if selected.contains(person.id) { selected.remove(person.id) } else { selected.insert(person.id) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            Text("全部合计含身份未明确工单；选择人员不会改变全部合计。单指横向查看日期，双指缩放；纵向滚动页面。").font(.caption).foregroundStyle(.secondary)
            if model.incomplete { Text("读取未完整，空白值用 —；图表仅绘制已知数量。").font(.caption).foregroundStyle(.secondary) }
        }.interfaceMotion(selected).interfaceMotion(showTotal)
    }
    private var results: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 12) {
                Text("处理数量").font(.headline)
                if points.isEmpty {
                    ContentUnavailableView("没有可绘制数据", systemImage: "chart.xyaxis.line", description: Text("选择全部合计或人员，或返回管理页面完成读取。"))
                        .frame(minHeight: 230)
                } else { trendChart; legend }
                HStack {
                    Button("放大", systemImage: "plus.magnifyingglass") { visibleCount = max(2, visibleCount / 1.5) }
                    Button("缩小", systemImage: "minus.magnifyingglass") { visibleCount = min(Double(max(2, buckets.count)), visibleCount * 1.5) }
                    Spacer(minLength: 0)
                    Button("重置") { visibleCount = grain == .day ? 7 : 12 }
                }.font(.caption).buttonStyle(.bordered)
            }.padding(14).background(.background, in: RoundedRectangle(cornerRadius: 18))
            HStack {
                Text("统计表").font(.headline)
                Spacer()
                Button(swapped ? "日期为行" : "人员为行", systemImage: "arrow.triangle.swap") { swapped.toggle() }.font(.subheadline).buttonStyle(.bordered)
            }
            ManagementTrendTable(buckets: buckets, people: people.filter { selected.contains($0.id) }, swapped: swapped, complete: !model.incomplete)
                .id(swapped)
        }.interfaceMotion(swapped).interfaceMotion(grain).interfaceMotion(selected).interfaceMotion(showTotal)
    }
    private var trendChart: some View {
        Chart(points) { point in
            LineMark(x: .value("日期", point.index), y: .value("单数", point.value), series: .value("连续日期", point.series + ":" + String(point.segment)))
                .foregroundStyle(by: .value("人员", point.series)).interpolationMethod(.monotone)
            PointMark(x: .value("日期", point.index), y: .value("单数", point.value))
                .foregroundStyle(by: .value("人员", point.series)).symbolSize(18)
        }
        .chartXScale(domain: 0...max(1, buckets.count - 1))
        .chartYScale(domain: 0...max(1, points.map(\.value).max() ?? 1))
        .chartScrollableAxes(.horizontal)
        .chartXVisibleDomain(length: max(2, min(visibleCount, Double(max(2, buckets.count)))))
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { value in
                AxisGridLine(); AxisTick()
                AxisValueLabel {
                    if let index = value.as(Int.self), buckets.indices.contains(index) { Text(buckets[index].label).font(.caption2) }
                }
            }
        }
        .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) }
        .chartForegroundStyleScale(domain: seriesNames, range: seriesColors)
        .chartLegend(.hidden)
        .frame(height: 280)
        .simultaneousGesture(MagnifyGesture().onChanged { value in
            if zoomStart == nil { zoomStart = visibleCount }
            visibleCount = max(2, min(Double(max(2, buckets.count)), (zoomStart ?? visibleCount) / Double(value.magnification)))
        }.onEnded { _ in zoomStart = nil })
        .interfaceMotion(points.map { $0.id + ":" + String($0.value) }).interfaceMotion(visibleCount, duration: 0.2)
    }
    private func seriesTitle(_ person: ManagedPerson) -> String {
        let repeated = people.filter { $0.name == person.name }.count > 1
        return repeated ? person.name + "（" + person.identity + "）" : person.name
    }
    private func requestLandscape() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        if originalOrientation == nil { originalOrientation = scene.interfaceOrientation }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape)) { error in
            Task { @MainActor in orientationError = "系统未允许切换，可手动旋转设备：" + error.localizedDescription }
        }
    }
    private func restoreOrientation() {
        guard let orientation = originalOrientation,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        let mask: UIInterfaceOrientationMask
        switch orientation {
        case .landscapeLeft: mask = .landscapeLeft
        case .landscapeRight: mask = .landscapeRight
        case .portraitUpsideDown: mask = .portraitUpsideDown
        default: mask = .portrait
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        originalOrientation = nil
    }
}

private struct ManagementTrendTable: View {
    let buckets: [ManagedTrendBucket]
    let people: [ManagedPerson]
    let swapped: Bool
    let complete: Bool
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 58
    @ScaledMetric(relativeTo: .body) private var columnWidth: CGFloat = 112
    @State private var horizontalOffset: CGFloat = 0
    private var names: [String] {
        people.map { person in
            people.filter { $0.name == person.name }.count > 1 ? person.name + "（" + person.identity + "）" : person.name
        }
    }
    private var rowTitles: [String] { swapped ? names : buckets.map(\.label) }
    private var columnTitles: [String] { swapped ? buckets.map(\.label) : names }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                fixedRow(swapped ? "人员" : "日期", "合计", header: true)
                movingHeader(driving: rowTitles.isEmpty) {
                    HStack(spacing: 0) { ForEach(columnTitles.indices, id: \.self) { column in cell(columnTitles[column], header: true) } }
                }
            }
            if swapped {
                HStack(spacing: 0) {
                    fixedRow("全部合计", text(fixedTotal(0)), header: true)
                    movingHeader {
                        HStack(spacing: 0) { ForEach(buckets.indices, id: \.self) { column in cell(text(value(0, column)), header: true) } }
                    }
                }
            }
            ScrollView(.vertical) {
                HStack(alignment: .top, spacing: 0) {
                    VStack(spacing: 0) {
                        ForEach(rowTitles.indices, id: \.self) { row in
                            fixedRow(rowTitles[row], text(fixedTotal(swapped ? row + 1 : row)), header: false)
                        }
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .overlay(alignment: .trailing) {
                        LinearGradient(colors: [.black.opacity(0.12), .clear], startPoint: .leading, endPoint: .trailing).frame(width: 8).offset(x: 8).allowsHitTesting(false)
                    }.zIndex(1)
                    ScrollView(.horizontal) {
                        VStack(spacing: 0) {
                            ForEach(rowTitles.indices, id: \.self) { row in
                                HStack(spacing: 0) { ForEach(columnTitles.indices, id: \.self) { column in cell(text(value(swapped ? row + 1 : row, column)), header: false) } }
                            }
                        }
                    }
                    .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.x + $0.contentInsets.leading } action: { _, value in horizontalOffset = value }
                }
            }.frame(height: min(360, CGFloat(rowTitles.count) * rowHeight))
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityLabel("维修处理统计表，空白 — 表示数量尚未明确")
        .onChange(of: swapped) { horizontalOffset = 0 }
    }
    @ViewBuilder private func movingHeader<Content: View>(driving: Bool = false, @ViewBuilder content: () -> Content) -> some View {
        if driving {
            ScrollView(.horizontal) { content() }
                .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.x + $0.contentInsets.leading } action: { _, value in horizontalOffset = value }
                .frame(height: rowHeight)
        } else {
            GeometryReader { _ in
                content().fixedSize(horizontal: true, vertical: false).offset(x: -horizontalOffset)
            }.frame(height: rowHeight).clipped()
        }
    }
    private func fixedRow(_ title: String, _ total: String, header: Bool) -> some View {
        HStack(spacing: 0) {
            Text(title).font(header ? .caption.weight(.semibold) : .caption).frame(width: min(130, columnWidth), alignment: .leading)
            Text(total).font(.caption.monospacedDigit()).frame(width: 58)
        }.padding(.horizontal, 6).frame(height: rowHeight)
            .background(header ? Color.accentColor.opacity(0.1) : Color.clear)
            .overlay(alignment: .bottom) { Divider() }
    }
    private func cell(_ title: String, header: Bool) -> some View {
        Text(title).font(header ? .caption.weight(.semibold) : .caption.monospacedDigit())
            .frame(width: columnWidth, height: rowHeight).padding(.horizontal, 4)
            .background(header ? Color.accentColor.opacity(0.1) : Color(uiColor: .secondarySystemGroupedBackground))
            .overlay(alignment: .bottom) { Divider() }
    }
    private func fixedTotal(_ row: Int) -> Int? {
        let total: Int
        if !swapped { total = buckets[row].total }
        else if row == 0 { total = buckets.reduce(0) { $0 + $1.total } }
        else { total = buckets.reduce(0) { $0 + ($1.counts[people[row - 1].id] ?? 0) } }
        return total > 0 || complete ? total : nil
    }
    private func value(_ row: Int, _ column: Int) -> Int? {
        let count: Int?
        if swapped { count = row == 0 ? buckets[column].total : buckets[column].counts[people[row - 1].id] }
        else { count = buckets[row].counts[people[column].id] }
        return count.flatMap { $0 > 0 || complete ? $0 : nil } ?? (complete ? 0 : nil)
    }
    private func text(_ value: Int?) -> String { value.map(String.init) ?? "—" }
}


#if DEBUG
/// Offline layout fixtures. These routes never read or modify production business data.
struct ManagementTrendLayoutPreview: View {
    @EnvironmentObject private var session: LiveSession
    @StateObject private var model = ManagementAnalyticsModel()
    var body: some View {
        ManagementTrendScreen(model: model)
            .task {
                model.start(session: session, days: 30, pageReader: { slice, window in
                    guard slice.state == "RESOLVED", let window else { return .object(["content": .array([]), "last": .bool(true)]) }
                    let rows: [JSONValue] = (0..<6).map { index in
                        .object(["faultId": .string("fixture-" + String(index)), "faultName": .string("示例维修工单"), "faultState": .string("RESOLVED"),
                                 "repairEndTime": .string(window.from), "repairUser": .object(["userName": .string(index == 5 ? "" : "fixture-account-" + String(index)),
                                                                                         "realName": .string(index < 2 ? "同名人员" : "较长的示例维修人员姓名")])])
                    }
                    return .object(["content": .array(rows), "last": .bool(true), "totalElements": .number(6)])
                })
            }
    }
}

struct ManagementCardLayoutPreview: View {
    var body: some View {
        CardPage {
            CardGroup {
                WorkOrderCard(title: "示例报修名称", status: "待受理", reference: "TEST-ASSET-001", time: "2026-10-22 06:29",
                              facts: [("报修名称", "示例报修名称"), ("资产编码", "TEST-ASSET-001"), ("资产名称", "示例加工中心"), ("报修时间", "2026-10-22 06:29"), ("故障描述", "示例故障描述，保留完整换行内容。")]) {
                    Text("示例工单详情").navigationTitle("示例详情")
                }
            }
        }.navigationTitle("卡片布局检查")
    }
}
#endif
