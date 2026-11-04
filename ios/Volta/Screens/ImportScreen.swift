import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// «Добавить»: one field for whatever the VPN service gave — a subscription address, an
/// encrypted volta:// link, a server link, a WireGuard .conf or a JSON config. The line
/// under the field says what Volta made of it as it is typed, and the button is named
/// after what will happen, so there is no "which box was it" step.
struct ImportScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var name = ""
    @State private var fileOpen = false
    @State private var working = false

    var body: some View {
        let kind = ImportKind.detect(input)
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                Button { dismiss() } label: {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(p.onSurface)
                        .frame(width: 44, height: 44)
                        .background(p.surface, in: Circle())
                }
                .buttonStyle(PressStyle(scale: 0.9))
                .accessibilityLabel(L("common.back"))
                .padding(.top, 10)

                Text(L("import.title"))
                    .font(.vHeadlineMedium)
                    .foregroundColor(p.onBackground)
                    .padding(.top, 18)
                Text(L("import.subtitle"))
                    .font(.vBodyMedium)
                    .foregroundColor(p.onSurfaceVariant)
                    .padding(.top, 4)

                VoltaField(text: $input, placeholder: L("import.placeholder"), multiline: true,
                           minLines: 4, maxLines: 9, restingOutline: true, keyboard: .URL)
                    .padding(.top, 20)

                // Pasting is what nearly everyone comes here to do, so the button is always
                // there. The clipboard is read only on the tap, so iOS asks only then.
                HStack(spacing: 8) {
                    FieldButton(icon: "doc.on.clipboard", text: L("import.paste"), filled: input.isEmpty) {
                        Haptics.tap()
                        let text = UIPasteboard.general.string?.trimmed ?? ""
                        if text.isEmpty { model.show(L("import.clipboardEmpty")) } else { input = text }
                    }
                    if !input.isEmpty {
                        FieldButton(icon: "xmark", text: L("common.clear"), filled: false) { input = "" }
                    }
                }
                .padding(.top, 10)

                DetectedLine(kind: kind)
                    .padding(.top, 12)
                    .animation(.easeInOut(duration: 0.2), value: kind)

                if kind.isSubscription {
                    VoltaField(text: $name, label: L("import.ownName"))
                        .padding(.top, 14)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }

                PrimaryButton(title: action(kind), busy: working || model.busy,
                              enabled: kind.canSubmit && !working && !model.busy) {
                    submit(kind)
                }
                .padding(.top, 18)

                VoltaCard(padding: 8) {
                    LinkRow(icon: "doc", tint: p.primary, title: L("import.openFile"),
                            subtitle: L("import.openFile.hint")) {
                        fileOpen = true
                    }
                }
                .padding(.top, 14)

                SectionHeader(L("import.what")).padding(.top, 24)
                VoltaCard(spacing: 12) {
                    ForEach(formats, id: \.0) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.0).font(.vTitleSmall).foregroundColor(p.onSurface)
                            Hint(item.1)
                        }
                    }
                }
                .padding(.top, 6)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
            .animation(Motion.settle, value: kind.isSubscription)
        }
        .background(p.background.ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            if !model.importDraft.isEmpty {
                input = model.importDraft
                model.importDraft = ""
            }
        }
        .fileImporter(isPresented: $fileOpen, allowedContentTypes: [.data, .text, .json, .plainText]) { result in
            guard case let .success(url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            // A config file is a few kilobytes; anything far bigger is not one.
            guard let data = try? Data(contentsOf: url), data.count < 512 * 1024 else {
                model.show(L("import.fileUnreadable"))
                return
            }
            input = String(decoding: data, as: UTF8.self).trimmed
        }
    }

    private func submit(_ kind: ImportKind) {
        let value = input.trimmed
        working = true
        Task {
            let ok: Bool
            if kind.isSubscription {
                ok = await model.addSubscription(url: value, name: name.trimmed)
            } else {
                ok = await model.importText(value)
            }
            working = false
            if ok { dismiss() }
        }
    }

    /// The button says what it will do.
    private func action(_ kind: ImportKind) -> String {
        switch kind {
        case .subscription, .encrypted: return L("import.action.subscription")
        case .server: return L("import.action.server")
        case .servers: return L("import.action.servers")
        case .conf, .json: return L("import.action.import")
        case .control: return L("import.action.run")
        case .empty, .unknown, .amnezia: return L("common.add")
        }
    }

    private var formats: [(String, String)] {
        [
            (L("import.fmt.sub"), L("import.fmt.sub.text")),
            (L("import.fmt.server"), L("import.fmt.server.text")),
            (L("import.fmt.volta"), L("import.fmt.volta.text")),
            (L("import.fmt.file"), L("import.fmt.file.text"))
        ]
    }
}

/// What Volta made of the field, said back in a line with a badge.
private struct DetectedLine: View {
    var kind: ImportKind
    @Environment(\.palette) private var p

    var body: some View {
        let look = self.look
        HStack(spacing: 12) {
            IconBubble(icon: look.icon, tint: look.tint, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(look.title).font(.vTitleSmall)
                    .foregroundColor(kind == .empty ? p.onSurfaceVariant : p.onBackground)
                Hint(look.body)
            }
        }
        .id(look.title)
        .transition(.opacity)
    }

    private var look: (icon: String, tint: Color, title: String, body: String) {
        switch kind {
        case .empty:
            return ("link", p.onSurfaceVariant, L("detect.empty"), L("detect.empty.text"))
        case let .subscription(hidden):
            return hidden
                ? ("lock.fill", p.primary, L("detect.hiddenSub"), L("detect.hiddenSub.text"))
                : ("arrow.triangle.2.circlepath", p.primary, L("detect.sub"), L("detect.sub.text"))
        case .encrypted:
            return ("lock.shield", p.good, L("detect.encrypted"), L("detect.encrypted.text"))
        case let .server(proto):
            return ("server.rack", p.home, L("detect.server", proto), L("detect.server.text"))
        case let .servers(n):
            return ("server.rack", p.home, L10n.plural("servers.count", n), L("detect.servers.text"))
        case .conf:
            return ("doc.text", p.home, L("detect.conf"), L("detect.conf.text"))
        case .json:
            return ("curlybraces", p.home, L("detect.json"), L("detect.json.text"))
        case .control:
            return ("power", p.primary, L("detect.control"), L("detect.control.text"))
        case .amnezia:
            return ("key", p.poor, L("detect.amnezia"), L("detect.amnezia.text"))
        case .unknown:
            return ("questionmark", p.poor, L("detect.unknown"), L("detect.unknown.text"))
        }
    }
}
