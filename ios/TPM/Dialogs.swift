import SwiftUI
import UIKit

/// Ends editing in whichever field has the keyboard, wherever it is.
func endEditing() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

extension View {
    /// Tells the user something; `onClose` runs however the dialog is closed.
    func noticeDialog(_ title: String, message: String, isPresented: Binding<Bool>, onClose: @escaping () -> Void = {}) -> some View {
        alert(title, isPresented: isPresented) {
            Button("好", role: .cancel) {}
        } message: { Text(message) }
        .onChange(of: isPresented.wrappedValue) { _, shown in if !shown { onClose() } }
    }

    /// Shows a failure or validation message in the app's one dialog. With
    /// `retry` the main button reads "重试" and runs it. `clearsOnClose` empties the message on close
    /// (forms, so the same message can appear again); otherwise it stays, so a paged list that
    /// failed does not immediately try again by itself.
    func errorDialog(_ error: Binding<String?>, title: String = "提示", clearsOnClose: Bool = false, retry: (() -> Void)? = nil) -> some View {
        modifier(ErrorDialog(error: error, title: title, clearsOnClose: clearsOnClose, retry: retry))
    }

    /// Dismissing the keyboard by dragging the content, and a "收起键盘" button above the keyboard
    /// for fields that have no return key (numbers, phone numbers).
    func keyboardManaged() -> some View {
        scrollDismissesKeyboard(.interactively).nativeKeyboardToolbar()
    }
}

/// A text field with the character count when the field has a limit. The "收起键盘" button is not here: it is a row above the
/// keyboard (see `keyboardManaged`). Limits count UTF-16 units like the server.
struct EntryField: View {
    let placeholder: String
    @Binding var text: String
    var limit: Int? = nil
    var axis: Axis = .horizontal
    var lines: ClosedRange<Int>? = nil
    var keyboard: UIKeyboardType = .default
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if let lines { TextField(placeholder, text: $text, axis: axis).lineLimit(lines) }
                else if axis == .horizontal {
                    TextField(placeholder, text: $text).submitLabel(.done).onSubmit { endEditing() }
                } else { TextField(placeholder, text: $text, axis: axis) }
            }
            .keyboardType(keyboard)
            .onChange(of: text) { _, value in if let limit { text = limitedText(value, limit: limit) } }
            if let limit { Text("\(text.utf16.count)/\(limit) 字符").monospacedDigit().font(AppFont.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .trailing).transaction { $0.animation = nil } }
        }
        .fullSeparator()
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
            .alert(title, isPresented: Binding(get: { error != nil && !acknowledged }, set: { open in
                if !open { acknowledged = true; if clearsOnClose { error = nil } }
            })) {
                if let retry {
                    Button("重试", action: retry)
                    Button("取消", role: .cancel) {}
                } else { Button("好", role: .cancel) {} }
            } message: { Text(error ?? shownMessage) }
    }
}

/// Messages made by the network layer: a failed request, a server status or a lost connection.
func looksLikeServerError(_ message: String) -> Bool {
    ["请求失败", "HTTP ", "网络错误", "服务器"].contains { message.contains($0) }
}

extension View {
    /// Makes the divider under a row run the full width. Without it the system picks the divider's
    /// start from the row's first text, which gives rows with text fields or icons a shorter line.
    func fullSeparator() -> some View {
        alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
            .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    }
    /// Stretches a row's content over the whole row, so the whole row (not just its text) takes the tap
    /// and the divider, which follows the content's width, runs edge to edge.
    func fullRow() -> some View {
        frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect).fullSeparator()
    }
}

/// An action row of a form ("新建出库单"): the whole row is the button and its divider is full width.
struct FullRowButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void
    init(_ title: String, systemImage: String, action: @escaping () -> Void) {
        self.title = title; self.systemImage = systemImage; self.action = action
    }
    var body: some View {
        Button(action: action) { Label(title, systemImage: systemImage).fullRow() }
    }
}

extension View {
    /// SwiftUI owns keyboard avoidance. Never animate keyboard-dependent padding.
    func bottomBarPadding() -> some View {
        padding(.horizontal, 20).padding(.vertical, 8)
    }
}

/// A brief inline notice made from a system Label, without movement or glass layers.
private struct ToastModifier: ViewModifier {
    @Binding var message: String?
    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let message {
                    Label(message, systemImage: "info.circle").font(AppFont.subheadline).multilineTextAlignment(.center)
                        .padding(.horizontal, 18).padding(.vertical, 10)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 8).padding(.horizontal, 24)
                        .accessibilityIdentifier("toast")
                        .allowsHitTesting(false)
                }
            }
            .onChange(of: message) { _, new in if let new { AccessibilityNotification.Announcement(new).post() } }
            .task(id: message) {
                guard message != nil else { return }
                try? await Task.sleep(for: .seconds(3))
                if !Task.isCancelled { message = nil }
            }
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View { modifier(ToastModifier(message: message)) }
}

// Each input page declares its system keyboard toolbar.
