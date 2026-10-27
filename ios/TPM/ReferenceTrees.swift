import SwiftUI

func matchingFaultTypes(_ nodes: [JSONValue], query: String) -> [JSONValue] {
    guard !query.isEmpty else { return nodes }
    return nodes.compactMap { node in
        if node.object.label.localizedCaseInsensitiveContains(query) { return node }
        let children = matchingFaultTypes(node["children"].array, query: query)
        guard !children.isEmpty else { return nil }
        var value = node.object; value["children"] = .array(children); return .object(value)
    }
}

struct FaultTypeTree: View {
    let nodes: [JSONValue]
    let choose: (JSONValue) -> Void
    var body: some View {
        ForEach(nodes.map { PickerEntry(key: referenceKey(.faultTypes, $0), label: $0.object.label, value: $0) }) { entry in
            FaultTypeNode(node: entry.value, choose: choose)
        }.interfaceMotion(nodes.map { referenceKey(.faultTypes, $0) })
    }
}

struct FaultTypeNode: View {
    let node: JSONValue
    let choose: (JSONValue) -> Void
    @State private var expanded = false
    var body: some View {
        Group {
            if !node["children"].array.isEmpty {
                DisclosureGroup(isExpanded: $expanded) {
                    FaultTypeTree(nodes: node["children"].array, choose: choose)
                } label: { WrappingContent { Text(node.object.label).animatedTextValue(node.object.label) } }
            } else if node["type"].text == "FaultType" && !node["disabled"].bool {
                Button { choose(node) } label: { HStack(spacing: 8) { WrappingContent { Text(node.object.label).animatedTextValue(node.object.label).foregroundStyle(Color.primary) }; ReferenceBadges(value: node) }.frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect) }.buttonStyle(.plain)
                    .referenceMenu(for: node)
            }
        }.interfaceMotion(expanded)
    }
}

struct DepartmentTree: View {
    let nodes: [JSONValue]
    let choose: (JSONValue) -> Void
    var body: some View {
        ForEach(nodes.map { PickerEntry(key: referenceKey(.departments, $0), label: $0.object.label, value: $0) }) { entry in
            DepartmentNode(node: entry.value, choose: choose)
        }.interfaceMotion(nodes.map { referenceKey(.departments, $0) })
    }
}

struct DepartmentNode: View {
    let node: JSONValue
    let choose: (JSONValue) -> Void
    @State private var expanded = false
    var body: some View {
        Group {
            if !node["children"].array.isEmpty {
                DisclosureGroup(isExpanded: $expanded) { DepartmentTree(nodes: node["children"].array, choose: choose) } label: { WrappingContent { Text(node.object.label).animatedTextValue(node.object.label) } }
            } else if !node["disabled"].bool && node["value"].text != "root" {
                Button { choose(node) } label: { HStack(spacing: 8) { WrappingContent { Text(node.object.label).animatedTextValue(node.object.label).foregroundStyle(Color.primary) }; ReferenceBadges(value: node) }.frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect) }.buttonStyle(.plain)
                    .referenceMenu(for: node)
            }
        }.interfaceMotion(expanded)
    }
}
