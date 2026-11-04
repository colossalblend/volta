import SwiftUI

/// The app's frame: the five tabs with the Android bottom bar, the «Добавить» screen over
/// them, and one place for short messages.
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let p = Palette.resolve(model.settings.theme, systemDark: scheme == .dark)
        ZStack(alignment: .bottom) {
            p.background.ignoresSafeArea()
            Group {
                switch model.tab {
                case .home: HomeScreen()
                case .servers: ServersScreen()
                case .routing: RoutingScreen()
                case .subscriptions: SubscriptionsScreen()
                case .settings: SettingsScreen()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(.opacity)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VoltaTabBar(selection: $model.tab)
            }
            if let toast = model.toast {
                ToastView(text: toast)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 96)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { model.toast = nil }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.tab)
        .animation(Motion.settle, value: model.toast)
        .environment(\.palette, p)
        .preferredColorScheme(model.settings.theme.followsSystem ? nil : (p.isDark ? .dark : .light))
        .tint(p.primary)
        .fullScreenCover(isPresented: $model.importOpen) {
            ImportScreen()
                .environment(\.palette, p)
                .environmentObject(model)
                .preferredColorScheme(model.settings.theme.followsSystem ? nil : (p.isDark ? .dark : .light))
        }
        .task { await model.onLaunch() }
        .onOpenURL { url in model.open(url: url) }
        .onChange(of: scenePhase) { phase in model.setActive(phase == .active) }
    }
}

/// The tab bar. One copper pill sits behind the chosen tab's icon and slides to the next
/// one; the icon fills in as the pill arrives.
private struct VoltaTabBar: View {
    @Binding var selection: AppTab
    @Environment(\.palette) private var p
    @Environment(\.accessibilityReduceMotion) private var still

    private struct Tab {
        let tab: AppTab
        let title: String
        let icon: String
        let chosenIcon: String
    }

    private var tabs: [Tab] {
        [
            Tab(tab: .home, title: L("tab.home"), icon: "house", chosenIcon: "house.fill"),
            Tab(tab: .servers, title: L("tab.servers"), icon: "server.rack", chosenIcon: "server.rack"),
            Tab(tab: .routing, title: L("home_tab_routing"), icon: "arrow.triangle.branch", chosenIcon: "arrow.triangle.branch"),
            Tab(tab: .subscriptions, title: L("tab.subscriptions"), icon: "icloud", chosenIcon: "icloud.fill"),
            Tab(tab: .settings, title: L("tab.settings"), icon: "gearshape", chosenIcon: "gearshape.fill")
        ]
    }

    var body: some View {
        let index = CGFloat(tabs.firstIndex { $0.tab == selection } ?? 0)
        GeometryReader { geo in
            let slot = geo.size.width / CGFloat(tabs.count)
            ZStack(alignment: .topLeading) {
                Capsule()
                    .fill(p.primary.opacity(0.16))
                    .frame(width: 58, height: 32)
                    .offset(x: slot * index + (slot - 58) / 2, y: 10)
                    .animation(still ? nil : Motion.slide, value: index)
                HStack(spacing: 0) {
                    ForEach(tabs, id: \.tab) { item in
                        let chosen = item.tab == selection
                        Button {
                            if !chosen { Haptics.select() }
                            selection = item.tab
                        } label: {
                            VStack(spacing: 9) {
                                Image(systemName: chosen ? item.chosenIcon : item.icon)
                                    .font(.system(size: 19, weight: chosen ? .semibold : .regular))
                                    .foregroundColor(chosen ? p.primary : p.onSurfaceVariant)
                                    .frame(height: 24)
                                    .scaleEffect(chosen ? 1.06 : 1)
                                    .animation(still ? nil : Motion.press, value: chosen)
                                Text(item.title)
                                    .font(.vLabelSmall)
                                    .foregroundColor(chosen ? p.onSurface : p.onSurfaceVariant)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                            }
                            .padding(.top, 14)
                            .frame(width: slot, height: 68, alignment: .top)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(chosen ? .isSelected : [])
                    }
                }
            }
        }
        .frame(height: 68)
        .background(p.surface.ignoresSafeArea(edges: .bottom))
    }
}

private struct ToastView: View {
    var text: String
    @Environment(\.palette) private var p

    var body: some View {
        Text(text)
            .font(.vBodyMedium)
            .foregroundColor(p.isDark ? Color(hex: 0x1A1C1E) : Color(hex: 0xEEE8DF))
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(p.isDark ? Color(hex: 0xEEE8DF) : Color(hex: 0x22282D),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .shadow(color: Color.black.opacity(0.25), radius: 12, y: 4)
    }
}
