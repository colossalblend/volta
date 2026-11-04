import SwiftUI

/// The home screen. The first screenful is the disc and what it needs: the state in a
/// word, the server it goes through and the auto mode. The rest (speed, why this server,
/// the network check) waits below; its top edge peeks out so it is clear there is more.
struct HomeScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        GeometryReader { geo in
            let disc = min(geo.size.width * 0.92, geo.size.height * 0.47, 380)
            ScrollView(showsIndicators: false) {
                VStack(spacing: 12) {
                    hero(disc: disc)
                        .frame(minHeight: max(0, geo.size.height - 44))
                    details
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
        }
    }

    private func hero(disc: CGFloat) -> some View {
        VStack(spacing: 0) {
            HomeTopBar()
            Spacer(minLength: 8)
            VStack(spacing: 0) {
                PowerDisc(
                    state: model.vpnState,
                    working: model.working,
                    // Must stay tappable while connected, or a server that vanished under
                    // the tunnel would leave no way to turn the VPN off.
                    enabled: model.hasServers || model.isConnected || model.vpnState == .connecting,
                    size: disc,
                    activity: trafficActivity(model.traffic),
                    action: model.toggle
                )
                ConnectionHeadline()
                if model.isConnected && !model.working {
                    LiveMeter()
                        .padding(.top, 12)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                if let error = model.lastError, !model.isConnected, !model.working {
                    Text(error)
                        .font(.vBodySmall)
                        .foregroundColor(p.poor)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .padding(.top, 10)
                        .padding(.horizontal, 12)
                        .transition(.opacity)
                }
            }
            .animation(Motion.settle, value: model.isConnected)
            .animation(Motion.settle, value: model.lastError)
            Spacer(minLength: 8)
            if model.hasServers {
                VStack(spacing: 10) {
                    ServerCapsule()
                    ModeSelector()
                    if model.settings.mode == .manual {
                        ManualModeStrip()
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
                .animation(Motion.settle, value: model.settings.mode)
            } else {
                FirstSubscription()
            }
        }
    }

    @ViewBuilder private var details: some View {
        if model.hasServers {
            if model.isConnected && !model.speedHistory.isEmpty {
                SpeedCard()
            }
            if let pick = model.pick, model.settings.mode != .manual, !model.working {
                AutoPickCard(summary: pick)
            }
            if Balancer.hasLte(model.servers) {
                NetworkStatusCard()
            }
        }
    }
}

private struct HomeTopBar: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 10) {
                    Text("Volta").font(.vHeadlineSmall).foregroundColor(p.onBackground)
                    regimePill
                }
                // The address the internet sees right now: the server's while connected.
                Text(addressLine)
                    .font(.vLabelMedium)
                    .monospacedDigit()
                    .foregroundColor(p.onSurfaceVariant)
                    .lineLimit(1)
                    .animation(.easeInOut, value: addressLine)
            }
            Spacer()
            if model.hasServers {
                RoundIconButton(icon: "plus", label: L("home.add.a11y"), filled: true) {
                    model.importDraft = ""
                    model.importOpen = true
                }
            }
        }
        .padding(.top, 10)
    }

    private var addressLine: String {
        guard let info = model.ipInfo else { return " " }
        return [info.country, info.ip].filter { !$0.isBlank }.joined(separator: ", ")
    }

    @ViewBuilder private var regimePill: some View {
        let label: String? = {
            switch model.regime {
            case .open: return "Home"
            case .whitelistOnly: return "LTE"
            case .blocked: return L("home.regime.none")
            case .unknown: return nil
            }
        }()
        if let label = label {
            let color = model.regime == .open ? p.home : (model.regime == .whitelistOnly ? p.lte : p.poor)
            Text(label)
                .font(.vLabelMedium)
                .foregroundColor(color)
                .padding(.horizontal, 9)
                .padding(.vertical, 3)
                .background(color.opacity(0.15), in: Capsule())
        }
    }
}

/// The state in one word, big, and a line under it saying what happens next.
private struct ConnectionHeadline: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        let phase = model.phase.caption
        let server = model.selectedServer.map { Countries.withoutLeadingFlag($0.remark) } ?? ""
        let title: String
        let caption: String
        let color: Color
        if !phase.isEmpty || model.vpnState == .connecting {
            title = L("home.state.connecting")
            caption = phase.isEmpty ? server.ifBlank(L("phase.starting")) : phase
            color = p.onBackground
        } else {
            switch model.vpnState {
            case .connected:
                title = L("home.state.on")
                caption = server
                color = p.good
            case .disconnecting:
                title = L("home.state.disconnecting")
                caption = L("home.caption.closing")
                color = p.onBackground
            case .error:
                title = L("home.state.error")
                caption = L("home.caption.retry")
                color = p.poor
            default:
                title = L("home.state.off")
                caption = model.hasServers ? L("home.caption.tap") : L("home.caption.addFirst")
                color = p.onBackground
            }
        }
        let cancellable = !phase.isEmpty || model.vpnState == .connecting
        return VStack(spacing: 6) {
            Text(title)
                .font(.vHeadlineMedium)
                .foregroundColor(color)
                .id(title)
                .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                        removal: .move(edge: .top).combined(with: .opacity)))
            Text(caption)
                .font(.vBodyMedium)
                .foregroundColor(p.onSurfaceVariant)
                .lineLimit(1)
                .multilineTextAlignment(.center)
                .id("caption-" + caption)
                .transition(.opacity)
            if cancellable {
                Text(L("home.caption.cancel"))
                    .font(.vLabelMedium)
                    .foregroundColor(p.onSurfaceVariant.opacity(0.7))
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: title)
        .animation(.easeInOut(duration: 0.2), value: caption)
        .clipped()
    }
}

/// Time on and live speeds, with figures that roll instead of flicker.
private struct LiveMeter: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        TimelineView(.periodic(from: Date(), by: 1)) { context in
            HStack(spacing: 18) {
                Text(Format.duration(since: model.connectedSince, now: context.date))
                    .foregroundColor(p.onBackground)
                    .contentTransition(.numericText())
                speed("arrow.down", Format.speed(model.traffic.downSpeed), p.good)
                speed("arrow.up", Format.speed(model.traffic.upSpeed), p.primary)
            }
            .font(.vTitleSmall)
            .monospacedDigit()
            .frame(height: 26)
            .animation(.easeOut(duration: 0.25), value: context.date)
        }
    }

    private func speed(_ icon: String, _ value: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Image(systemName: icon).font(.system(size: 12, weight: .bold)).foregroundColor(color)
            Text(value).foregroundColor(p.onSurfaceVariant).contentTransition(.numericText())
        }
    }
}

/// The server the tunnel goes through. Tapping it opens the list.
private struct ServerCapsule: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        let server = model.selectedServer
        Button {
            Haptics.tap()
            model.tab = .servers
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(p.surfaceVariant)
                    Text(server.map { Countries.of($0.remark).flag } ?? "🌐").font(.system(size: 22))
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(server))
                        .font(.vTitleMedium)
                        .foregroundColor(p.onSurface)
                        .lineLimit(1)
                    if let s = server {
                        HStack(spacing: 8) {
                            GroupLabel(role: s.role)
                            if let name = model.subscription(s.subscriptionId)?.name, !name.isBlank {
                                Text(name).font(.vBodySmall).foregroundColor(p.onSurfaceVariant).lineLimit(1)
                            }
                        }
                    } else {
                        Text(model.settings.mode == .manual ? L("capsule.openList") : L("capsule.willFind"))
                            .font(.vBodySmall)
                            .foregroundColor(p.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(server?.id ?? 0)
                .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                        removal: .move(edge: .top).combined(with: .opacity)))
                if let ms = server.map({ $0.ewmaMs >= 0 ? $0.ewmaMs : $0.latencyMs }), ms >= 0 {
                    VStack(alignment: .trailing, spacing: 2) {
                        SignalBars(ms: ms)
                        Text(Format.latency(ms))
                            .font(.vLabelLarge)
                            .monospacedDigit()
                            .foregroundColor(p.latency(ms))
                    }
                }
                ChevronView()
            }
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .padding(.vertical, 8)
            .background(p.surface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .clipped()
        }
        .buttonStyle(PressStyle(scale: 0.98))
        .animation(Motion.settle, value: server?.id)
    }

    private func title(_ server: Server?) -> String {
        if let s = server { return Countries.withoutLeadingFlag(s.remark) }
        return model.settings.mode == .manual ? L("capsule.noServer") : L("capsule.fastest")
    }
}

/// "Home", "LTE" or "Балансер" in the group's colour.
struct GroupLabel: View {
    var role: ServerRole
    var bold = false
    @Environment(\.palette) private var p

    var body: some View {
        Text(Format.groupName(role))
            .font(bold ? .vLabelSmallBold : .vLabelMedium)
            .foregroundColor(groupColor(role, p))
    }
}

func groupColor(_ role: ServerRole, _ p: Palette) -> Color {
    switch role {
    case .normal: return p.home
    case .bypass: return p.lte
    case .auto: return p.onSurfaceVariant
    }
}

/// The three auto groups as one track with a sliding thumb in the group's colour.
private struct ModeSelector: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        if Balancer.hasLte(model.servers) {
            threeGroups
        } else {
            homeOnly
        }
    }

    private func tint(_ mode: ConnectMode) -> Color {
        switch mode {
        case .autoHome: return p.home
        case .autoLte: return p.lte
        default: return p.primary
        }
    }

    private var threeGroups: some View {
        let modes = ConnectMode.autoModes
        let index = modes.firstIndex(of: model.settings.mode)
        return GeometryReader { geo in
            let slot = geo.size.width / CGFloat(modes.count)
            ZStack(alignment: .leading) {
                if let index = index {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(tint(model.settings.mode).opacity(p.isDark ? 0.17 : 0.14))
                        .frame(width: slot)
                        .offset(x: slot * CGFloat(index))
                        .animation(Motion.slide, value: index)
                }
                HStack(spacing: 0) {
                    ForEach(modes, id: \.self) { mode in
                        let on = mode == model.settings.mode
                        Button {
                            if !on { Haptics.select() }
                            model.setMode(mode)
                        } label: {
                            VStack(spacing: 1) {
                                Text(Format.modeShort(mode))
                                    .font(.vTitleSmall)
                                    .foregroundColor(on ? tint(mode) : p.onSurface)
                                    .lineLimit(1)
                                let n = model.count(mode.scope ?? .all)
                                Text(n > 0 ? L10n.plural("servers.count", n) : L("mode.empty"))
                                    .font(.vLabelSmall)
                                    .foregroundColor(p.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            .frame(width: slot)
                            .frame(maxHeight: .infinity)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }
        }
        .frame(height: 50)
        .padding(4)
        .background(p.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// No LTE server anywhere: Home fills most of the track, and a quiet LTE stub says
    /// "нет серверов" rather than disappearing without a word.
    private var homeOnly: some View {
        let auto = model.settings.mode != .manual
        return HStack(spacing: 4) {
            Button {
                if !auto {
                    Haptics.select()
                    model.setMode(.autoHomeLte)
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "wifi").font(.system(size: 15, weight: .semibold))
                        .foregroundColor(auto ? p.home : p.onSurface)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(L("mode.autoHome")).font(.vTitleSmall).foregroundColor(auto ? p.home : p.onSurface)
                        let n = model.count(.normal)
                        Text(n > 0 ? L10n.plural("servers.count", n) : L("mode.empty"))
                            .font(.vLabelSmall).foregroundColor(p.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(auto ? p.home.opacity(p.isDark ? 0.17 : 0.14) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
            .layoutPriority(2)

            Button {
                model.show(L("mode.noLte"))
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "antenna.radiowaves.left.and.right.slash")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(p.onSurfaceVariant.opacity(0.6))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("LTE").font(.vTitleSmall).foregroundColor(p.onSurfaceVariant.opacity(0.75))
                        Text(L("mode.noServers")).font(.vLabelSmall).foregroundColor(p.onSurfaceVariant.opacity(0.6))
                            .lineLimit(1)
                    }
                }
                .padding(.horizontal, 8)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(p.outline, lineWidth: 1))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(width: 118)
        }
        .frame(height: 50)
        .padding(4)
        .background(p.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Shown under the selector while a hand-picked server overrides the auto groups.
private struct ManualModeStrip: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "slider.horizontal.3").font(.system(size: 15, weight: .semibold)).foregroundColor(p.primary)
            Text(L("manual.title")).font(.vBodyMedium).foregroundColor(p.onSurface).lineLimit(1)
            Spacer(minLength: 4)
            Button {
                Haptics.select()
                model.setMode(.autoHomeLte)
            } label: {
                Text(L("manual.backToAuto")).font(.vLabelLarge).foregroundColor(p.primary).padding(6)
            }
            .buttonStyle(PressStyle())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(p.primary.opacity(0.10), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// No servers yet: the one thing to do.
private struct FirstSubscription: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        VStack(spacing: 14) {
            Text(L("home.first.text"))
                .font(.vBodyMedium)
                .foregroundColor(p.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)
            PrimaryButton(title: L("home.first.button"), height: 52) {
                model.importDraft = ""
                model.importOpen = true
            }
        }
        .padding(.bottom, 8)
    }
}

/// The last minute of speed, down and up.
private struct SpeedCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        VoltaCard(spacing: 10) {
            HStack {
                Text(L("speed.title")).font(.vTitleSmall).foregroundColor(p.onSurface)
                Spacer()
                Text(L("speed.total", Format.bytes(model.traffic.downlink), Format.bytes(model.traffic.uplink)))
                    .font(.vLabelMedium)
                    .monospacedDigit()
                    .foregroundColor(p.onSurfaceVariant)
            }
            Canvas { ctx, size in
                let points = model.speedHistory
                let peak = max(1, points.map { max($0.down, $0.up) }.max() ?? 1)
                func line(_ value: (SpeedPoint) -> Int64, _ color: Color) {
                    guard points.count > 1 else { return }
                    var path = Path()
                    for (i, pt) in points.enumerated() {
                        let x = size.width * CGFloat(i) / CGFloat(max(59, points.count - 1))
                        let y = size.height - size.height * 0.9 * CGFloat(value(pt)) / CGFloat(peak)
                        if i == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                }
                line({ $0.up }, p.primary.opacity(0.8))
                line({ $0.down }, p.good)
            }
            .frame(height: 64)
        }
    }
}

/// "Why this server": the finalists of the last auto pick with their numbers.
private struct AutoPickCard: View {
    var summary: PickSummary
    @Environment(\.palette) private var p
    @State private var grown = false

    var body: some View {
        let worst = max(1, summary.rows.map { $0.ms }.max() ?? 1)
        VoltaCard(spacing: 8) {
            Text(L("pick.title")).font(.vTitleSmall).foregroundColor(p.onSurface)
            Hint(L("pick.counts", summary.measured, summary.alive))
            ForEach(Array(summary.rows.enumerated()), id: \.offset) { index, row in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(Countries.of(row.name).flag)
                        Text(Countries.withoutLeadingFlag(row.name))
                            .font(row.chosen ? .vTitleSmall : .vBodyMedium)
                            .foregroundColor(p.onSurface)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        Text(Format.latency(row.ms))
                            .font(.vLabelMedium)
                            .monospacedDigit()
                            .foregroundColor(row.chosen ? p.latency(row.ms) : p.onSurfaceVariant)
                        if row.chosen {
                            Image(systemName: "checkmark.circle.fill").font(.system(size: 14)).foregroundColor(p.good)
                        }
                    }
                    GeometryReader { geo in
                        let share = min(1, max(0.08, CGFloat(row.ms) / CGFloat(worst)))
                        ZStack(alignment: .leading) {
                            Capsule().fill(p.surfaceVariant)
                            Capsule()
                                .fill(row.chosen ? p.latency(row.ms) : p.outline)
                                .frame(width: geo.size.width * share * (grown ? 1 : 0))
                                .animation(.easeOut(duration: 0.65).delay(0.12 * Double(index)), value: grown)
                        }
                    }
                    .frame(height: 5)
                }
            }
        }
        .onAppear { grown = true }
        .onChange(of: summary.at) { _ in
            grown = false
            DispatchQueue.main.async { grown = true }
        }
    }
}

/// What the site check said: Home or LTE network.
private struct NetworkStatusCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @State private var checking = false

    var body: some View {
        let (label, color): (String, Color) = {
            switch model.regime {
            case .open: return (L("network.home"), p.home)
            case .whitelistOnly: return (L("network.lte"), p.lte)
            case .blocked: return (L("network.none"), p.poor)
            case .unknown: return (L("network.unknown"), p.onSurfaceVariant)
            }
        }()
        return VoltaCard(padding: 12) {
            HStack(spacing: 10) {
                Image(systemName: model.regime == .open ? "globe" : "network")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(color)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.vTitleSmall).foregroundColor(color)
                    if model.isConnected { Hint(L("network.whileConnected")) }
                }
                Spacer()
                if !model.isConnected {
                    RoundIconButton(icon: "arrow.clockwise", label: L("network.recheck"), busy: checking) {
                        checking = true
                        Task {
                            model.regime = await NetworkProbe.run()
                            checking = false
                        }
                    }
                }
            }
        }
    }
}
