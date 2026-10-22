from pathlib import Path
p=Path('ios/TPM/TPMApp.swift');s=p.read_text(encoding='utf-8-sig')
s=s.replace('@StateObject private var store = DemoStore()','@StateObject private var store = DemoStore()\n    @StateObject private var session = LiveSession()',1)
s=s.replace('WindowGroup { PreviewRouter().environmentObject(store) }','WindowGroup { PreviewRouter().environmentObject(store).environmentObject(session) }',1)
s=s.replace('} else { MainTabs() }','} else { AuthenticatedRoot() }',1).replace('#else\n        MainTabs()','#else\n        AuthenticatedRoot()',1)
start=s.index('struct NewFaultView: View {');end=s.index('\nstruct AssetListView:',start)
s=s[:start]+'''struct NewFaultView: View {
    init(prefilled: Bool = false) {}
    var body: some View { OriginalFaultForm(preview: true) }
}
'''+s[end:]
start=s.index('struct FaultProcessingView: View {');end=s.index('\nstruct NewFaultView:',start)
s=s[:start]+'''struct FaultProcessingView: View {
    let id: String
    var body: some View { OriginalRepairForm(faultID: id, task: .object(["name": .string("维修处理")]), preview: true) }
}
'''+s[end:]
s=s.replace('.toolbar { ToolbarItem(placement: .primaryAction) { Button("新建报修", systemImage: "plus") { creating = true } } }','''.toolbar {
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("新建报修", systemImage: "plus") { creating = true }.labelStyle(.iconOnly).buttonStyle(.glassProminent)
            }
        }''')
start=s.index('                .safeAreaInset(edge: .bottom) {');end=s.index('                .sheet(isPresented: $processing)',start)
s=s[:start]+'''                .toolbar {
                    ToolbarSpacer(.flexible, placement: .bottomBar)
                    ToolbarItem(placement: .bottomBar) { Button("维修处理") { processing = true }.buttonStyle(.glassProminent) }
                }
'''+s[end:]
s=s.replace('case "new-fault": NavigationStack { NewFaultView(prefilled: true) }','''case "new-fault": NavigationStack { OriginalFaultForm(preview: true) }
        case "erp-form": NavigationStack { OriginalERPForm(preview: true) }
        case "workflow": NavigationStack { WorkflowNodesView(processID: "", preview: true) }
        case "loading": ProgressView("正在加载…")''')
p.write_text(s,encoding='utf-8')
