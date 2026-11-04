import SwiftUI
import UIKit

/// Settings, grouped by what they are about. Every on/off row carries a badge that lights
/// up with its switch; the colour theme is picked from coins painted in each theme's own
/// colours.
struct SettingsScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 10) {
                ScreenHeader(title: L("tab.settings")) { EmptyView() }

                SectionHeader(L("settings.connection"))
                VoltaCard(padding: 10) {
                    SwitchRow(icon: "bolt.horizontal.circle", tint: p.primary,
                              title: L("settings.keep"), subtitle: L("settings.keep.hint"),
                              isOn: Binding(get: { model.settings.keepConnected }, set: { model.setKeepConnected($0) }))
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
                    SwitchRow(icon: "house", tint: p.good,
                              title: L("settings.russia"), subtitle: L("settings.russia.hint"),
                              isOn: $model.settings.russiaDirect)
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

                SectionHeader(L("settings.language"))
                VoltaCard(padding: 10) {
                    LinkRow(icon: "character.bubble", tint: p.primary, title: L("settings.language.row"),
                            subtitle: L("settings.language.hint", currentLanguage)) {
                        openSettings()
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
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
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
