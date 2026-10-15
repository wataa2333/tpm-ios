import SwiftUI

@main
struct TPMApp: App {
    var body: some Scene {
        WindowGroup { WorkspaceView() }
    }
}

struct Document: Identifiable, Hashable {
    let id: String
    let title: String
    let category: String
    let applicant: String
    let department: String
    let date: String
    let summary: String
    let items: [String]

    static let examples: [Document] = [
        .init(id: "WX-20261015-001", title: "加工中心故障维修", category: "维修", applicant: "张明", department: "生产一部", date: "今天 09:30", summary: "主轴运行时出现异常振动，请安排检查并处理。", items: ["加工中心 · CNC-008", "设备位置 · 一车间"]),
        .init(id: "LY-20261015-002", title: "生产设备领用申请", category: "领用", applicant: "李华", department: "生产二部", date: "今天 08:45", summary: "因生产排程调整，申请领用以下设备。", items: ["数显卡尺 · 2 件", "扭力扳手 · 1 件"]),
        .init(id: "BY-20261014-006", title: "空压机定期保养", category: "保养", applicant: "王伟", department: "设备部", date: "昨天 16:20", summary: "按月度计划进行保养，检查润滑、过滤及运行状态。", items: ["空压机 · KY-003", "设备位置 · 动力房"])
    ]
}

struct WorkspaceView: View {
    @State private var query = ""
    @State private var completed: Set<String> = []
    @State private var showCompleted = false

    private var visibleDocuments: [Document] {
        Document.examples.filter {
            completed.contains($0.id) == showCompleted &&
            (query.isEmpty || ($0.title + $0.id + $0.applicant).localizedCaseInsensitiveContains(query))
        }
    }

    var body: some View {
        TabView {
            Tab("工作台", systemImage: "tray") {
                NavigationStack {
                    List {
                        Section {
                            Picker("单据状态", selection: $showCompleted) {
                                Text("待处理").tag(false)
                                Text("已处理").tag(true)
                            }
                            .pickerStyle(.segmented)
                        } footer: {
                            Text("界面预览使用模拟单据，尚未连接业务服务器。")
                        }

                        Section(showCompleted ? "已处理单据" : "待处理单据") {
                            ForEach(visibleDocuments) { document in
                                NavigationLink(value: document) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(document.title).font(.headline)
                                        Text("\(document.category) · \(document.applicant) · \(document.department)")
                                            .font(.subheadline).foregroundStyle(.secondary)
                                        Text(document.date).font(.caption).foregroundStyle(.secondary)
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                            if visibleDocuments.isEmpty {
                                ContentUnavailableView(query.isEmpty ? "暂无单据" : "没有匹配的单据", systemImage: "tray")
                            }
                        }
                    }
                    .navigationTitle("工作台")
                    .searchable(text: $query, prompt: "搜索单据、编号或申请人")
                    .navigationDestination(for: Document.self) { document in
                        DocumentDetailView(document: document, isCompleted: completed.contains(document.id)) {
                            completed.insert(document.id)
                        }
                    }
                }
            }
            Tab("我的", systemImage: "person.crop.circle") {
                NavigationStack {
                    List {
                        Section {
                            Label("个人预览", systemImage: "person.crop.circle")
                            LabeledContent("数据来源", value: "模拟数据")
                        } footer: {
                            Text("登录、工厂与账号权限将在核对原应用业务后接入。")
                        }
                    }
                    .navigationTitle("我的")
                }
            }
        }
    }
}

struct DocumentDetailView: View {
    let document: Document
    let isCompleted: Bool
    let onComplete: () -> Void
    @State private var showingProcessing = false
    @State private var completedLocally = false

    var body: some View {
        List {
            Section {
                LabeledContent("单据编号", value: document.id)
                LabeledContent("状态", value: isCompleted || completedLocally ? "已处理（模拟）" : "待处理")
                LabeledContent("申请人", value: document.applicant)
                LabeledContent("所属部门", value: document.department)
                LabeledContent("提交时间", value: document.date)
            } header: { Text("基本信息") }
            Section("单据说明") { Text(document.summary).textSelection(.enabled) }
            Section("明细") {
                ForEach(document.items, id: \.self) { Text($0) }
            }
            Section("附件") { Text("暂无附件").foregroundStyle(.secondary) }
            Section("处理记录") {
                Label("\(document.applicant) 提交申请", systemImage: "doc.text")
                if isCompleted || completedLocally {
                    Label("已完成本地模拟处理", systemImage: "checkmark.circle")
                }
            }
            if !isCompleted && !completedLocally {
                Section {
                    Button("处理单据", systemImage: "square.and.pencil") { showingProcessing = true }
                } footer: {
                    Text("此操作仅演示界面，不会向服务器提交数据。")
                }
            }
        }
        .navigationTitle(document.title)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingProcessing) {
            ProcessingView {
                completedLocally = true
                onComplete()
            }
        }
    }
}

struct ProcessingView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var opinion = ""
    @State private var confirming = false
    let onComplete: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("填写处理意见", text: $opinion, axis: .vertical)
                        .lineLimit(5...10)
                } header: { Text("处理意见") } footer: {
                    Text("这是界面演示。真实处理动作与必填规则将以原系统为准。")
                }
            }
            .navigationTitle("处理单据")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { confirming = true }
                        .disabled(opinion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .confirmationDialog("完成本地模拟处理？", isPresented: $confirming, titleVisibility: .visible) {
                Button("完成模拟处理") { onComplete(); dismiss() }
                Button("取消", role: .cancel) { }
            } message: { Text("不会提交到业务服务器。") }
        }
    }
}

#Preview { WorkspaceView() }
