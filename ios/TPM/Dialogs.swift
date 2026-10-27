import SwiftUI
import UIKit

/// Ends editing in whichever field has the keyboard, wherever it is.
func endEditing() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

/// The one dialog every confirmation and notice in the app uses: a compact bottom card with a
/// centred title, optional message, one large primary button and, when wanted, a cancel
/// button. It replaces the mix of system action sheets, popover bubbles and alerts, which looked
/// different from each other and were small.
struct DialogSheet: View {
    let title: String
    var message: String? = nil
    let primary: String
    var primaryRole: ButtonRole? = nil
    var showsCancel = true
    /// An optional second choice between the main button and "取消", e.g. "不保存".
    var secondary: String? = nil
    var onSecondary: () -> Void = {}
    let onPrimary: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var contentHeight: CGFloat = 280
    var body: some View {
        VStack(spacing: 22) {
            VStack(spacing: 10) {
                Text(title).font(AppFont.title3.weight(.semibold)).multilineTextAlignment(.center)
                if let message, !message.isEmpty {
                    Text(message).font(AppFont.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        .textSelection(.enabled)
                }
            }.frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 10) {
                Button(role: primaryRole) { dismiss(); onPrimary() } label: {
                    Text(primary).font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28)
                }
                .buttonStyle(.glassProminent).controlSize(.large)
                .tint(primaryRole == .destructive ? .red : .accentColor)
                .accessibilityIdentifier("dialog-primary")
                if let secondary {
                    Button(role: .destructive) { dismiss(); onSecondary() } label: { Text(secondary).font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                        .buttonStyle(.glass).controlSize(.large)
                        .accessibilityIdentifier("dialog-secondary")
                }
                if showsCancel {
                    Button { dismiss() } label: { Text("取消").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
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
    }
}

extension View {
    /// Tells the user something; `onClose` runs however the dialog is closed.
    func noticeDialog(_ title: String, message: String, isPresented: Binding<Bool>, onClose: @escaping () -> Void = {}) -> some View {
        sheet(isPresented: isPresented, onDismiss: onClose) {
            DialogSheet(title: title, message: message, primary: "好", showsCancel: false, onPrimary: {})
        }
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
        scrollDismissesKeyboard(.interactively)
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
                else { TextField(placeholder, text: $text, axis: axis) }
            }
            .keyboardType(keyboard)
            .onChange(of: text) { _, value in if let limit { text = limitedText(value, limit: limit) } }
            if let limit { Text("\(text.utf16.count)/\(limit) 字符").font(AppFont.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .trailing) }
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
            .sheet(isPresented: Binding(get: { error != nil && !acknowledged }, set: { open in
                if !open { acknowledged = true; if clearsOnClose { error = nil } }
            })) {
                DialogSheet(title: title, message: error ?? shownMessage, primary: retry == nil ? "好" : "重试",
                            showsCancel: retry != nil, onPrimary: { retry?() })
            }
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
    /// Padding for the action buttons at the bottom of a page. The bottom padding is negative on purpose:
    /// the buttons then sit as far from the lower screen edge as from the side edges (20pt), which keeps
    /// them concentric with the screen's rounded corners instead of floating above the home indicator.
    func bottomBarPadding() -> some View {
        modifier(BottomBarPadding())
    }
}

/// The negative bottom padding only makes sense against the home indicator. With the keyboard up, the
/// bar's bottom edge is the keyboard's top edge, and pulling it down would hide the buttons' labels
/// behind the keys, so the padding is normal then. Follow the keyboard's own animation timing.
private struct BottomBarPadding: ViewModifier {
    @State private var keyboardUp = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.padding(.horizontal, 20).padding(.top, 8).padding(.bottom, keyboardUp ? 8 : -14)
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
                updateKeyboard(notification)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { notification in
                updateKeyboard(notification)
            }
    }
    private func updateKeyboard(_ notification: Notification) {
        let change = KeyboardMotionChange(notification)
        guard keyboardUp != change.visible else { return }
        withAnimation(change.animation(reduceMotion: reduceMotion)) { keyboardUp = change.visible }
    }
}

/// A short notice that slides in below the navigation bar, speaks itself to VoiceOver and goes away alone.
private struct ToastModifier: ViewModifier {
    @Binding var message: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let message {
                    Text(message).font(AppFont.subheadline.weight(.medium)).multilineTextAlignment(.center)
                        .padding(.horizontal, 18).padding(.vertical, 10)
                        .glassEffect(.regular, in: .capsule)
                        .padding(.top, 8).padding(.horizontal, 24)
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                        .accessibilityIdentifier("toast")
                        .allowsHitTesting(false)
                }
            }
            .interfaceMotion(message, duration: 0.35)
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


// Keyboard dismissal is installed globally on UITextField/UITextView by KeyboardDismissAccessory.
