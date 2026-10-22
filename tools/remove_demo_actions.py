from pathlib import Path
p=Path('ios/TPM/TPMApp.swift');s=p.read_text(encoding='utf8');a=s.index('    var action: String? {');b=s.index('\n}',a);s=s[:a]+s[b:]
a=s.index('    func transition(_ id: String, opinion: String) {');b=s.index('\n}\n',a);s=s[:a]+s[b:]
s=s.replace('#Preview { MainTabs().environmentObject(DemoStore()) }','#Preview { MainTabs().environmentObject(DemoStore()).environmentObject(LiveSession()) }')
p.write_text(s,encoding='utf8')
