import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Settings, grouped by what they are about. Every on/off row carries a badge that lights
/// up with its switch; the colour theme is picked from coins painted in each theme's own
/// colours.
struct SettingsScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @State private var sharing: URL?
    @State private var restoring = false
    @State private var pendingRestore: BackupCodec.Contents?
    @State private var languageOpen = false

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 10) {
                ScreenHeader(title: L("tab.settings")) { EmptyView() }

                SectionHeader(L("settings.connection"))
                VoltaCard(padding: 10) {
                    SwitchRow(icon: "bolt.horizontal.circle", tint: p.primary,
                              title: L("settings.keep"), subtitle: L("settings.keep.hint"),
                              isOn: Binding(get: { model.settings.keepConnected }, set: { model.setKeepConnected($0) }))
                    SwitchRow(icon: "wifi", tint: p.home, title: L("settings_follow_network_title"),
                              subtitle: L("settings_follow_network_text"), isOn: $model.settings.followNetwork)
                    SwitchRow(icon: "speedometer", tint: p.good, title: L("settings_recheck_title"),
                              subtitle: L("settings_recheck_text"), isOn: $model.settings.autoRecheck)
                    LinkRow(icon: "gearshape", tint: p.primary, title: L("settings.vpnProfile"),
                            subtitle: L("settings.vpnProfile.hint")) {
                        openSettings()
                    }
                }

                SectionHeader(L("settings.checks"))
                VoltaCard(padding: 10) {
                    SwitchRow(icon: "arrow.triangle.2.circlepath", tint: p.primary,
                              title: L("settings.refreshOnLaunch"), subtitle: L("settings.refreshOnLaunch.hint"),
                              isOn: $model.settings.refreshOnLaunch)
                    SwitchRow(icon: "globe", tint: p.home,
                              title: L("settings.probe"), subtitle: L("settings.probe.hint"),
                              isOn: $model.settings.probeOnLaunch)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("settings.pingMethod")).font(.vTitleSmall).foregroundColor(p.onSurface)
                        HStack(spacing: 8) {
                            ForEach(PingMethod.allCases, id: \.self) { method in
                                VoltaChip(text: pingLabel(method), selected: model.settings.pingMethod == method,
                                          rest: p.surfaceVariant) {
                                    model.settings.pingMethod = method
                                }
                            }
                        }
                        Hint(model.settings.pingMethod == .realDelay ? L("settings.ping.real.hint") : L("settings.ping.tcp.hint"))
                    }
                    .padding(.leading, 48)
                    .padding(.vertical, 8)
                }

                SectionHeader(L("settings.tunnel"))
                VoltaCard(padding: 10) {
                    SwitchRow(icon: "server.rack", tint: p.primary,
                              title: L("settings.dns"), subtitle: L("settings.dns.hint"),
                              isOn: $model.settings.localDns)
                    LinkRow(icon: "arrow.triangle.branch", tint: p.good, title: L("routing_title"),
                            subtitle: L("settings.routing.hint")) {
                        model.tab = .routing
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("settings.engine")).font(.vTitleSmall).foregroundColor(p.onSurface)
                        SlidingChoice(options: TunnelEngine.allCases, selection: $model.settings.engine,
                                      label: { $0 == .hev ? L("settings.engine.main") : L("settings.engine.spare") },
                                      height: 38)
                        Hint(L("settings.engine.hint"))
                    }
                    .padding(.leading, 48)
                    .padding(.vertical, 8)
                    if model.isConnected {
                        Hint(L("settings.applyOnReconnect")).padding(.leading, 48).padding(.bottom, 6)
                    }
                }

                SectionHeader(L("settings.look"))
                VoltaCard {
                    Text(L("settings.theme")).font(.vTitleSmall).foregroundColor(p.onSurface)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(DesignTheme.allCases, id: \.self) { theme in
                                ThemeCoin(theme: theme, selected: model.settings.theme == theme) {
                                    Haptics.select()
                                    withAnimation(.easeInOut(duration: 0.3)) { model.settings.theme = theme }
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                    .padding(.top, 12)
                }

                SectionHeader(L("language_title"))
                VoltaCard(padding: 10) {
                    LinkRow(icon: "character.bubble", tint: p.primary, title: L("settings.language.row"),
                            subtitle: L("settings.language.now", currentLanguage)) {
                        languageOpen = true
                    }
                }

                SectionHeader(L("settings.device"))
                VoltaCard(padding: 10) {
                    SwitchRow(icon: "touchid", tint: p.primary,
                              title: L("settings.hwid"), subtitle: L("settings.hwid.hint"),
                              isOn: $model.settings.sendHwid)
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("settings.hwid.code")).font(.vLabelMedium).foregroundColor(p.onSurface)
                            Hint(Hwid.value)
                        }
                        Spacer()
                        RoundIconButton(icon: "doc.on.doc", label: L("settings.hwid.copy")) {
                            UIPasteboard.general.string = Hwid.value
                            model.show(L("settings.hwid.copied"))
                        }
                    }
                    .padding(.leading, 48)
                }

                SectionHeader(L("settings_section_move"))
                VoltaCard(padding: 10) {
                    LinkRow(icon: "square.and.arrow.up", tint: p.primary, title: L("settings_backup_save_title"),
                            subtitle: L("settings_backup_save_text")) {
                        do { sharing = try model.exportBackup() } catch { model.show(model.message(error)) }
                    }
                    LinkRow(icon: "square.and.arrow.down", tint: p.primary, title: L("settings_backup_restore_title"),
                            subtitle: L("settings_backup_restore_text")) {
                        restoring = true
                    }
                    Hint(L("settings_backup_warning")).padding(.leading, 48).padding(.bottom, 6)
                }

                SectionHeader(L("settings.updates"))
                UpdatesCard()

                SectionHeader(L("settings.about"))
                VoltaCard(padding: 10) {
                    LinkRow(icon: "info.circle", tint: p.primary, title: L("settings.version", appVersion),
                            subtitle: model.coreVersion.isEmpty ? nil : L("settings.core", model.coreVersion)) {
                    } trailing: {
                        EmptyView()
                    }
                    LinkRow(icon: "book", tint: p.primary, title: L("settings.help"), subtitle: L("settings.help.hint")) {
                        if let url = URL(string: "https://colossalblend.github.io/volta/") { UIApplication.shared.open(url) }
                    }
                    LinkRow(icon: "chevron.left.forwardslash.chevron.right", tint: p.primary,
                            title: L("settings.source"), subtitle: "github.com/colossalblend/volta") {
                        if let url = URL(string: "https://github.com/colossalblend/volta") { UIApplication.shared.open(url) }
                    }
                }
                Hint(L("settings.signingNote")).padding(.horizontal, 4).padding(.top, 4)
                Hint(L("settings.iosLimits")).padding(.horizontal, 4)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .sheet(item: $sharing) { url in ActivityView(items: [url]) }
        .sheet(isPresented: $languageOpen) {
            LanguagePicker()
                .environment(\.palette, p)
                .environmentObject(model)
                .presentationDetents([.medium, .large])
        }
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .data, .text]) { result in
            guard case let .success(url) = result else { return }
            do { pendingRestore = try model.readBackup(url) } catch { model.show(model.message(error)) }
        }
        .alert(L("settings_backup_restore_question"), isPresented: Binding(get: { pendingRestore != nil },
                                                                      set: { if !$0 { pendingRestore = nil } })) {
            Button(L("settings_backup_cancel"), role: .cancel) { pendingRestore = nil }
            Button(L("settings_backup_restore_action")) {
                guard let contents = pendingRestore else { return }
                pendingRestore = nil
                Task { model.show(await model.restore(contents)) }
            }
        } message: {
            Text(pendingRestore.map { model.describe($0) } ?? "")
        }
    }

    private var appVersion: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }

    private var currentLanguage: String {
        let code = L10n.language
        let name = Locale(identifier: code).localizedString(forLanguageCode: code) ?? code
        return name.prefix(1).uppercased() + name.dropFirst()
    }

    /// The app's page in iOS Settings: language, VPN and notifications live there.
    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    private func pingLabel(_ m: PingMethod) -> String {
        m == .realDelay ? L("settings.ping.real") : "TCP"
    }
}

/// One colour theme as a coin painted in its own colours: the background as the rim,
/// the surface as the face and the accent as the centre, like the power disc. "System"
/// is half night, half day.
private struct ThemeCoin: View {
    var theme: DesignTheme
    var selected: Bool
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        let (rim, face, core) = Palette.coin(theme)
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    if theme == .system {
                        HalfCircle(left: true).fill(rim)
                        HalfCircle(left: false).fill(Color(hex: 0xEFEAE3))
                        HalfCircle(left: true).fill(Color(hex: 0x22282D)).padding(9)
                        HalfCircle(left: false).fill(face).padding(9)
                    } else {
                        Circle().fill(rim)
                        Circle().fill(face).padding(9)
                    }
                    Circle().fill(core).padding(15)
                }
                .frame(width: 46, height: 46)
                .padding(5)
                .overlay(Circle().strokeBorder(selected ? p.primary : Color.clear, lineWidth: 2))
                Text(themeLabel)
                    .font(selected ? .vLabelMediumBold : .vLabelMedium)
                    .foregroundColor(selected ? p.primary : p.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(width: 64)
        }
        .buttonStyle(PressStyle())
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var themeLabel: String {
        switch theme {
        case .dynamic: return "iOS"
        case .system: return L("theme.system")
        case .volta: return "Volta"
        case .light: return L("theme.light")
        case .ocean: return L("theme.ocean")
        case .ember: return L("theme.ember")
        }
    }
}

private struct HalfCircle: Shape {
    var left: Bool
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r = min(rect.width, rect.height) / 2
        path.move(to: c)
        path.addRelativeArc(center: c, radius: r, startAngle: .degrees(left ? 90 : 270), delta: .degrees(180))
        path.closeSubpath()
        return path
    }
}

extension URL: Identifiable {
    public var id: String { absoluteString }
}

/// The share sheet, for the backup file (save to Files, AirDrop, a messenger…).
struct ActivityView: UIViewControllerRepresentable {
    var items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// «Проверка обновлений» and «Бета-версии». iOS lets no app install its own update, so a
/// new version is a link to its release; the hint says how to put it on the iPhone.
private struct UpdatesCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        VoltaCard(padding: 10) {
            SwitchRow(icon: "arrow.down.app", tint: p.primary, title: L("settings_update_auto_title"),
                      subtitle: model.settings.updateAutoCheck ? L("settings.update.auto.on") : L("settings_update_auto_off"),
                      isOn: Binding(get: { model.settings.updateAutoCheck }, set: { model.setUpdateAutoCheck($0) }))
            SwitchRow(icon: "testtube.2", tint: p.fair, title: L("settings_update_beta_title"),
                      subtitle: L("settings_update_beta_text"),
                      isOn: Binding(get: { model.settings.updateBeta }, set: { model.setUpdateBeta($0) }))
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.vTitleSmall).foregroundColor(p.onSurface)
                    Hint(subtitle)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if case let .available(m) = model.update {
                    FieldButton(icon: "safari", text: L("settings.update.open"), filled: true) {
                        UIApplication.shared.open(m.pageURL)
                    }
                } else {
                    FieldButton(icon: "arrow.clockwise", text: L("settings_update_action_check"), filled: false) {
                        Task { await model.checkUpdates() }
                    }
                    .disabled(model.update == .checking)
                }
            }
            .padding(.leading, 48)
            .padding(.vertical, 6)
            Hint(L("settings.update.howto")).padding(.leading, 48).padding(.bottom, 6)
        }
    }

    private var title: String {
        switch model.update {
        case let .available(m): return L("settings_update_available", m.versionName)
        case .checking: return L("settings_update_checking")
        case .failed: return L("settings_update_failed")
        case .upToDate, .idle: return L("settings.update.current", model.versionName, String(model.buildNumber))
        }
    }

    private var subtitle: String {
        switch model.update {
        case .available: return L("settings.update.available.hint")
        case .failed: return L("settings_update_error_check")
        case let .upToDate(at):
            let f = DateFormatter()
            f.timeStyle = .short
            f.dateStyle = .none
            return L("settings_update_latest_at", f.string(from: at))
        default: return L("settings_update_idle_hint")
        }
    }
}

/// The language, as on Android: «Как в системе» or one of the fifteen, each in its own
/// words. iOS reads it when Volta starts; Volta's page in iOS Settings has the same choice.
private struct LanguagePicker: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("language_title")).font(.vTitleLarge).foregroundColor(p.onBackground).padding(.top, 16)
                VoltaCard(padding: 10) {
                    row(nil, L("language_system"))
                    ForEach(AppModel.languages, id: \.self) { code in
                        row(code, Self.ownName(code))
                    }
                }
                Hint(L("language.restart"))
                PrimaryButton(title: L("settings.vpnProfile"), height: 46) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
            .padding(16)
        }
        .background(p.background.ignoresSafeArea())
    }

    private func row(_ code: String?, _ title: String) -> some View {
        let chosen = model.chosenLanguage == code
        return Button {
            Haptics.select()
            model.chooseLanguage(code)
            dismiss()
        } label: {
            HStack {
                Text(title).font(.vBodyLarge).foregroundColor(p.onSurface)
                Spacer()
                if chosen { Image(systemName: "checkmark").foregroundColor(p.primary) }
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    static func ownName(_ code: String) -> String {
        let name = Locale(identifier: code).localizedString(forIdentifier: code) ?? code
        return name.prefix(1).uppercased() + name.dropFirst()
    }
}
