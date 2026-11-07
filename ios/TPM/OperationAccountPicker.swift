import SwiftUI

struct OperationAccountPicker: View {
    let owner: LiveSession
    @Binding var operationSession: LiveSession?
    @Environment(\.dismiss) private var dismiss
    @StateObject private var saved = SavedAccountsModel()
    @State private var pending: SavedAccount?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        operationSession = nil
                        dismiss()
                    } label: {
                        accountRow(owner.authentication.text("name"),
                                   name: owner.authentication.text("fullName"), selected: operationSession == nil)
                    }
                    ForEach(saved.accounts.filter {
                        $0.username.caseInsensitiveCompare(owner.authentication.text("name")) != .orderedSame
                    }) { account in
                        Button { pending = account } label: {
                            accountRow(account.username, name: account.displayName,
                                       selected: operationSession?.authentication.text("name") == account.username)
                        }
                    }
                } footer: {
                    Text("从已记住的账号中选择，仅用于当前工单或本次批量操作。")
                }
                if pending != nil { ProgressView("正在登录本次操作账号") }
            }
            .disabled(pending != nil)
            .navigationTitle("本次操作账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .errorDialog($error, title: "本次账号未更改", clearsOnClose: true)
            .task(id: pending) {
                guard let account = pending else { return }
                defer { pending = nil }
                do {
                    let session = try await owner.startOperation(as: account)
                    try Task.checkCancellation()
                    operationSession = session
                    dismiss()
                } catch {
                    if !isCancellation(error) { self.error = error.localizedDescription }
                }
            }
        }
    }

    private func accountRow(_ username: String, name: String, selected: Bool) -> some View {
        HStack {
            VStack(alignment: .leading) {
                Text(name.isEmpty ? username : name)
                if !name.isEmpty && name != username {
                    Text(username).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if selected { Image(systemName: "checkmark") }
        }
        .foregroundStyle(.primary)
        .contentShape(.rect)
    }
}
