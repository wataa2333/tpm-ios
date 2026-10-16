import SwiftUI

// Date-range filtering of repair orders, as the original app does it: one filter on the fault time with the
// server's `dateBetween` operator and a two-element array of "yyyy-MM-dd" days (the original client's
// date-range field, see APK接口核对). Both days are included; the server owns the comparison.

struct DateRange: Equatable {
    var from: Date
    var to: Date

    /// The last `days` days up to and including today, like the original "近七日" entry (7 days back to today).
    static func lastDays(_ days: Int, now: Date = Date(), calendar: Calendar = .current) -> DateRange {
        let today = calendar.startOfDay(for: now)
        return DateRange(from: calendar.date(byAdding: .day, value: -days, to: today) ?? today, to: today)
    }
    static func thisMonth(now: Date = Date(), calendar: Calendar = .current) -> DateRange {
        let today = calendar.startOfDay(for: now)
        let start = calendar.date(from: calendar.dateComponents([.year, .month], from: today)) ?? today
        return DateRange(from: start, to: today)
    }

    /// The two days exactly as the server expects them, earlier day first whatever order was picked.
    func serverDays(calendar: Calendar = .current) -> [String] {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        let (first, second) = from <= to ? (from, to) : (to, from)
        return [formatter.string(from: first), formatter.string(from: second)]
    }
    var key: String { serverDays().joined(separator: "~") }
    var summary: String {
        let days = serverDays()
        return days[0] == days[1] ? days[0] : days[0] + " 至 " + days[1]
    }
}

/// `{field, operator: dateBetween, value: [from, to]}` in the same envelope as every other filter.
func dateRangeFilter(_ field: String, _ range: DateRange) -> JSONValue {
    .object(["field": .string(field), "value": .array(range.serverDays().map { JSONValue.string($0) }), "operator": .string("dateBetween"), "logic": .string("and")])
}

/// The small bar under the status strip that says which days the list is limited to, and clears it.
struct DateRangeChip: View {
    let range: DateRange
    let clear: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "calendar")
            Text("故障时间 " + range.summary).lineLimit(2)
            Spacer(minLength: 0)
            Button { clear() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary).frame(minWidth: 44, minHeight: 32) }
                .buttonStyle(.plain).accessibilityLabel("清除日期筛选").accessibilityIdentifier("date-range-clear")
        }
        .font(.subheadline).foregroundStyle(Color.accentColor)
        .padding(.leading, 16).padding(.trailing, 4)
        .accessibilityElement(children: .contain).accessibilityIdentifier("date-range-chip")
    }
}

/// The sheet that picks the days. Changes apply at once; "完成" only closes it.
struct DateRangeSheet: View {
    @Binding var range: DateRange?
    @Environment(\.dismiss) private var dismiss
    private var from: Binding<Date> { Binding(get: { range?.from ?? Date() }, set: { range?.from = $0; if let r = range, r.to < $0 { range?.to = $0 } }) }
    private var to: Binding<Date> { Binding(get: { range?.to ?? Date() }, set: { range?.to = $0; if let r = range, r.from > $0 { range?.from = $0 } }) }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("按故障时间筛选", isOn: Binding(get: { range != nil }, set: { range = $0 ? (range ?? .lastDays(7)) : nil }))
                        .accessibilityIdentifier("date-range-toggle")
                }
                if range != nil {
                    Section {
                        DatePicker("开始日期", selection: from, displayedComponents: .date)
                        DatePicker("结束日期", selection: to, displayedComponents: .date)
                    } footer: { Text("包含开始和结束当天，与搜索框和状态一起生效。") }
                    Section("快捷选择") {
                        Button("近 7 天") { range = .lastDays(7) }
                        Button("近 30 天") { range = .lastDays(30) }
                        Button("本月") { range = .thisMonth() }
                    }
                }
            }
            .navigationTitle("日期范围").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}
