import SwiftUI

/// The parent starts with no applied range. Editing this draft cannot start any request.
struct ManagementDateRangePicker: View {
    let apply: (DateRange) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft: DateRange
    init(initial: DateRange?, apply: @escaping (DateRange) -> Void) {
        self.apply = apply
        let today = Calendar.current.startOfDay(for: Date())
        _draft = State(initialValue: initial ?? DateRange(from: Calendar.current.date(byAdding: .day, value: -6, to: today) ?? today, to: today))
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("开始日期", selection: $draft.from, in: ...draft.to, displayedComponents: .date)
                    DatePicker("结束日期", selection: $draft.to, in: draft.from...Date(), displayedComponents: .date)
                } footer: { Text("包含开始和结束当天。超过 7 个自然日，读取前会提示将获取大量工单。") }
                Section("快捷选择") {
                    shortcut("这个月", range: preset(days: nil))
                    ForEach([7, 30, 90, 365], id: \.self) { days in
                        shortcut(days == 365 ? "近一年" : "近 \(days) 天", range: preset(days: days))
                    }
                }
            }
            .environment(\.locale, Locale(identifier: "zh_CN"))
            .navigationTitle("设定日期范围").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("应用日期范围") { apply(draft); dismiss() } }
            }
        }
    }
    private func preset(days: Int?) -> DateRange {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let start = days.map { calendar.date(byAdding: .day, value: -($0 - 1), to: today) ?? today }
            ?? calendar.date(from: calendar.dateComponents([.year, .month], from: today)) ?? today
        return DateRange(from: start, to: today)
    }
    private func shortcut(_ title: String, range: DateRange) -> some View {
        let selected = Calendar.current.isDate(draft.from, inSameDayAs: range.from)
            && Calendar.current.isDate(draft.to, inSameDayAs: range.to)
        return Button { draft = range } label: {
            HStack {
                Text(title)
                Spacer()
                if selected { Image(systemName: "checkmark.circle.fill") }
            }.frame(maxWidth: .infinity).contentShape(.rect)
        }.accessibilityAddTraits(selected ? .isSelected : [])
    }
}
