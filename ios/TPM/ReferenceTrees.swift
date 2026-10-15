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
        ForEach(Array(nodes.enumerated()), id: \.offset) { _, node in
            FaultTypeNode(node: node, choose: choose)
        }
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
                } label: { MarqueeContent { Text(node.object.label) } }
            } else if node["type"].text == "FaultType" && !node["disabled"].bool {
                Button { choose(node) } label: { MarqueeContent { Text(node.object.label) } }
            }
        }
    }
}

struct DepartmentTree: View {
    let nodes: [JSONValue]
    let choose: (JSONValue) -> Void
    var body: some View {
        ForEach(Array(nodes.enumerated()), id: \.offset) { _, node in
            DepartmentNode(node: node, choose: choose)
        }
    }
}

struct DepartmentNode: View {
    let node: JSONValue
    let choose: (JSONValue) -> Void
    @State private var expanded = false
    var body: some View {
        Group {
            if !node["children"].array.isEmpty {
                DisclosureGroup(isExpanded: $expanded) { DepartmentTree(nodes: node["children"].array, choose: choose) } label: { MarqueeContent { Text(node.object.label) } }
            } else if !node["disabled"].bool && node["value"].text != "root" {
                Button { choose(node) } label: { MarqueeContent { Text(node.object.label) } }
            }
        }
    }
}
