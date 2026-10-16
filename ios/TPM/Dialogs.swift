import SwiftUI
import UIKit

/// Ends editing in whichever field has the keyboard, wherever it is.
func endEditing() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

/// The one dialog every confirmation and notice in the app uses: a compact bottom card with a
/// centred title, optional message, one large primary button and, when wanted, a copy/cancel
/// button. It replaces the mix of system action sheets, popover bubbles and alerts, which looked
/// different from each other and were small.
struct DialogSheet: View {
    let title: String
    var message: String? = nil
    let primary: String
    var primaryRole: ButtonRole? = nil
    var copyText: String? = nil
    var showsCancel = true
    let onPrimary: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var contentHeight: CGFloat = 280
    var body: some View {
        VStack(spacing: 22) {
            VStack(spacing: 10) {
                Text(title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                if let message, !message.isEmpty {
                    Text(message).font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        .textSelection(.enabled)
                }
            }.frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 10) {
                Button(role: primaryRole) { dismiss(); onPrimary() } label: {
                    Text(primary).font(.headline).frame(maxWidth: .infinity, minHeight: 28)
                }
                .buttonStyle(.glassProminent).controlSize(.large)
                .tint(primaryRole == .destructive ? .red : .accentColor)
                .accessibilityIdentifier("dialog-primary")
                // Copying is for sending a server or network error to someone; a plain "please choose
                // an approver" has nothing worth copying.
                if let copyText, looksLikeServerError(copyText) { CopyButton(text: copyText, title: "复制错误信息").buttonStyle(.glass).controlSize(.large).frame(maxWidth: .infinity) }
                if showsCancel {
                    Button { dismiss() } label: { Text("取消").font(.headline).frame(maxWidth: .infinity, minHeight: 28) }
                        .buttonStyle(.glass).controlSize(.large)
                        .accessibilityIdentifier("dialog-cancel")
                }
            }
        }
        .padding(.horizontal, 24).padding(.top, 32).padding(.bottom, 20)
        .frame(maxWidth: .infinity)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        .presentationDetents([.height(min(contentHeight + 12, 640))])
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(32)
        .presentationBackground(.regularMaterial)
    }
}

extension View {
    /// Asks before doing something. The action runs after the dialog closes.
    func confirmDialog(_ title: String, message: String? = nil, isPresented: Binding<Bool>, confirmTitle: String, action: @escaping () -> Void) -> some View {
        sheet(isPresented: isPresented) {
            DialogSheet(title: title, message: message, primary: confirmTitle, onPrimary: action)
        }
    }

    /// Tells the user something (a validation problem, a failed request). `copyText` adds a
    /// copy button; `onClose` runs however the dialog is closed.
    func noticeDialog(_ title: String, message: String, isPresented: Binding<Bool>, copyText: String? = nil, onClose: @escaping () -> Void = {}) -> some View {
        sheet(isPresented: isPresented, onDismiss: onClose) {
            DialogSheet(title: title, message: message, primary: "好", copyText: copyText, showsCancel: false, onPrimary: {})
        }
    }

    /// Shows a failure or validation message in the app's one dialog, with a copy button. With
    /// `retry` the main button reads "重试" and runs it. `clearsOnClose` empties the message on close
    /// (forms, so the same message can appear again); otherwise it stays, so a paged list that
    /// failed does not immediately try again by itself.
    func errorDialog(_ error: Binding<String?>, title: String = "提示", clearsOnClose: Bool = false, retry: (() -> Void)? = nil) -> some View {
        modifier(ErrorDialog(error: error, title: title, clearsOnClose: clearsOnClose, retry: retry))
    }

    /// Dismissing the keyboard by dragging the content, and a "收起键盘" button above the keyboard
    /// for fields that have no return key (numbers, phone numbers).
    func keyboardManaged() -> some View {
        scrollDismissesKeyboard(.interactively)
    }
}

/// A text field with its own small footer: a "收起键盘" button while the field is being edited,
/// and the character count when the field has a limit. Limits count UTF-16 units like the server.
struct EntryField: View {
    let placeholder: String
    @Binding var text: String
    var limit: Int? = nil
    var axis: Axis = .horizontal
    var lines: ClosedRange<Int>? = nil
    var keyboard: UIKeyboardType = .default
    @FocusState private var focused: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if let lines { TextField(placeholder, text: $text, axis: axis).lineLimit(lines) }
                else { TextField(placeholder, text: $text, axis: axis) }
            }
            .keyboardType(keyboard)
            .focused($focused)
            .onChange(of: text) { _, value in if let limit { text = limitedText(value, limit: limit) } }
            HStack {
                // Always laid out and only faded, so showing it never changes the form's height
                // while the keyboard is animating in.
                Button { focused = false } label: { Label("收起键盘", systemImage: "keyboard.chevron.compact.down").font(.footnote) }
                    .buttonStyle(.bordered).controlSize(.small)
                    .opacity(focused ? 1 : 0).allowsHitTesting(focused).accessibilityHidden(!focused)
                    .accessibilityIdentifier("keyboard-dismiss")
                Spacer()
                if let limit { Text("\(text.utf16.count)/\(limit) 字符").font(.footnote).foregroundStyle(.secondary) }
            }
        }
    }
}

struct ErrorDialog: ViewModifier {
    @Binding var error: String?
    let title: String
    let clearsOnClose: Bool
    let retry: (() -> Void)?
    @State private var acknowledged = false
    @State private var shownMessage = ""
    func body(content: Content) -> some View {
        content
            .onChange(of: error) { _, new in
                acknowledged = false
                if let new { shownMessage = new }
            }
            .onAppear { if let error { shownMessage = error } }
            .sheet(isPresented: Binding(get: { error != nil && !acknowledged }, set: { open in
                if !open { acknowledged = true; if clearsOnClose { error = nil } }
            })) {
                DialogSheet(title: title, message: error ?? shownMessage, primary: retry == nil ? "好" : "重试",
                            copyText: error ?? shownMessage, showsCancel: retry != nil, onPrimary: { retry?() })
            }
    }
}

/// Messages made by the network layer: a failed request, a server status or a lost connection.
func looksLikeServerError(_ message: String) -> Bool {
    ["请求失败", "HTTP ", "网络错误", "服务器"].contains { message.contains($0) }
}
