import SwiftUI
import UIKit

private enum ServerSort: String, CaseIterable {
    case best, name, subscription
}

/// Every server of every subscription in one list, by country or flat. A tap picks the
/// server by hand; a long press offers the rest (check, favourite, group, copy, delete).
struct ServersScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @State private var query = ""
    @State private var pool: ServerPool = .all
    @State private var sort: ServerSort = .best
    @State private var byCountry = true
    @State private var favoritesOnly = false
    @State private var subscriptionFilter: Int64?
    @State private var openCountries: Set<String>?

    var body: some View {
        let hasLte = Balancer.hasLte(model.servers)
        let balancerCount = model.servers.filter { $0.role == .auto }.count
        let visible = visibleServers()
        let countries = grouped(visible)
        let currentCountry = model.selectedServer.map { Countries.of($0.remark).code }
        let open = openCountries ?? Set([currentCountry].compactMap { $0 })

        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 10) {
                ScreenHeader(title: L("tab.servers")) {
                    RoundIconButton(icon: "gauge.with.dots.needle.33percent", label: L("servers.pingAll"),
                                    busy: model.pingingAll) {
                        model.pingAll(visible.isEmpty ? model.servers : visible)
                    }
                    .disabled(model.servers.isEmpty)
                    Menu {
                        ForEach(ServerSort.allCases, id: \.self) { option in
                            Button {
                                sort = option
                            } label: {
                                if sort == option {
                                    Label(sortLabel(option), systemImage: "checkmark")
                                } else {
                                    Text(sortLabel(option))
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "arrow.up.arrow.down")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(p.onSurface)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("servers.sort"))
                }

                SectionHeader(L("servers.bestInGroup"))
                BestPickPads(hasLte: hasLte)

                VoltaSearchField(text: $query, placeholder: L("servers.search"))

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip(.all, L("pool.all"), p.primary)
                        chip(.normal, "Home", p.home)
                        if hasLte { chip(.bypass, "LTE", p.lte) }
                        if balancerCount > 0 { chip(.auto, "\(L("pool.balancers")) \(balancerCount)", p.onSurfaceVariant) }
                        VoltaChip(text: L("servers.favorites"), selected: favoritesOnly, icon: "star.fill") {
                            favoritesOnly.toggle()
                        }
                    }
                }

                if model.subscriptions.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            VoltaChip(text: L("servers.allSubscriptions"), selected: subscriptionFilter == nil) {
                                subscriptionFilter = nil
                            }
                            ForEach(model.subscriptions) { sub in
                                VoltaChip(text: sub.name, selected: subscriptionFilter == sub.id,
                                          accent: Color(hexString: sub.colorHex)) {
                                    subscriptionFilter = subscriptionFilter == sub.id ? nil : sub.id
                                }
                            }
                        }
                    }
                }

                if pool == .auto && balancerCount > 0 {
                    VoltaCard(padding: 10) {
                        SwitchRow(icon: "eye.slash", tint: p.primary, title: L("servers.hideBalancers"),
                                  subtitle: L("servers.hideBalancers.hint"), isOn: $model.settings.hideBalancers)
                    }
                }

                HStack {
                    Text(byCountry ? L("servers.countWithCountries", L10n.plural("servers.count", visible.count), countries.count)
                         : L10n.plural("servers.count", visible.count))
                        .font(.vTitleSmall)
                        .foregroundColor(p.onBackground)
                    Spacer()
                    SlidingChoice(options: [true, false], selection: $byCountry,
                                  label: { $0 ? L("servers.byCountry") : L("servers.flat") }, height: 36)
                        .frame(width: 170)
                }
                .padding(.top, 4)

                if visible.isEmpty {
                    EmptyState(
                        icon: "server.rack",
                        title: model.servers.isEmpty ? L("servers.empty.title") : L("servers.nothing.title"),
                        subtitle: model.servers.isEmpty ? L("servers.empty.text") : L("servers.nothing.text")
                    ) {
                        if model.servers.isEmpty {
                            Button(L("common.add")) {
                                model.importDraft = ""
                                model.importOpen = true
                            }
                            .font(.vTitleSmall)
                            .foregroundColor(p.primary)
                            .padding(.top, 8)
                        }
                    }
                }

                if byCountry {
                    ForEach(countries, id: \.0.code) { country, list in
                        let expanded = !query.isBlank || open.contains(country.code)
                        CountryCard(
                            country: country,
                            count: list.count,
                            bestMs: list.map { $0.shownMs }.filter { $0 >= 0 }.min() ?? -1,
                            expanded: expanded,
                            current: country.code == currentCountry
                        ) {
                            var next = open
                            if next.contains(country.code) { next.remove(country.code) } else { next.insert(country.code) }
                            withAnimation(Motion.settle) { openCountries = next }
                        } content: {
                            ForEach(list) { server in row(server, inGroup: true) }
                        }
                    }
                } else {
                    ForEach(visible) { server in row(server, inGroup: false) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }

    private func chip(_ option: ServerPool, _ text: String, _ accent: Color) -> some View {
        VoltaChip(text: text, selected: pool == option, accent: accent) { pool = option }
    }

    private func row(_ server: Server, inGroup: Bool) -> some View {
        ServerRow(
            server: server,
            selected: model.settings.selectedServerId == server.id,
            subscriptionName: model.subscription(server.subscriptionId)?.name ?? "",
            supported: ConfigBuilder.canDial(server),
            pinging: model.pinging.contains(server.id),
            inGroup: inGroup
        ) {
            model.select(server)
        }
        .contextMenu { ServerMenu(server: server) }
    }

    private func visibleServers() -> [Server] {
        let q = query.trimmed.lowercased()
        let subNames = Dictionary(model.subscriptions.map { ($0.id, $0.name.lowercased()) }, uniquingKeysWith: { a, _ in a })
        let list = model.servers.filter { s in
            guard pool.matches(s.role) else { return false }
            if model.settings.hideBalancers && pool != .auto && s.role == .auto { return false }
            if let f = subscriptionFilter, s.subscriptionId != f { return false }
            if favoritesOnly && !s.isFavorite { return false }
            if q.isEmpty { return true }
            let country = Format.countryName(Countries.of(s.remark)).lowercased()
            return s.remark.lowercased().contains(q) || country.contains(q) || s.address.lowercased().contains(q) ||
                s.proto.label.lowercased().contains(q) || (s.subscriptionId.flatMap { subNames[$0] } ?? "").contains(q)
        }
        switch sort {
        case .best:
            let dialable = Dictionary(list.map { ($0.id, ConfigBuilder.canDial($0)) }, uniquingKeysWith: { a, _ in a })
            return list.sorted { a, b in
                let da = dialable[a.id] ?? true, db = dialable[b.id] ?? true
                if da != db { return da }
                if (a.failStreak > 0) != (b.failStreak > 0) { return a.failStreak == 0 }
                let ma = a.shownMs < 0 ? Int.max : a.shownMs
                let mb = b.shownMs < 0 ? Int.max : b.shownMs
                return ma < mb
            }
        case .name:
            return list.sorted { $0.remark.lowercased() < $1.remark.lowercased() }
        case .subscription:
            var order: [Int64: Int] = [:]
            for (index, sub) in model.subscriptions.enumerated() { order[sub.id] = index }
            return list.sorted { a, b in
                let oa = a.subscriptionId.flatMap { order[$0] } ?? Int.max
                let ob = b.subscriptionId.flatMap { order[$0] } ?? Int.max
                return oa != ob ? oa < ob : a.sortOrder < b.sortOrder
            }
        }
    }

    /// One card per country, alphabetical; unnamed last.
    private func grouped(_ list: [Server]) -> [(Countries.Country, [Server])] {
        var groups: [String: (Countries.Country, [Server])] = [:]
        var order: [String] = []
        for s in list {
            let c = Countries.of(s.remark)
            if groups[c.code] == nil {
                groups[c.code] = (c, [])
                order.append(c.code)
            }
            groups[c.code]?.1.append(s)
        }
        return order.compactMap { groups[$0] }.sorted { a, b in
            let ua = a.0.code == Countries.unknownCode, ub = b.0.code == Countries.unknownCode
            if ua != ub { return !ua }
            return a.0.name.localizedCompare(b.0.name) == .orderedAscending
        }
    }

    private func sortLabel(_ s: ServerSort) -> String {
        switch s {
        case .best: return L("sort.best")
        case .name: return L("sort.name")
        case .subscription: return L("sort.subscription")
        }
    }
}

/// The app's own auto pick as three pads, one per group, in the group's colour.
private struct BestPickPads: View {
    var hasLte: Bool
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        let scopes: [BalancerScope] = hasLte ? [.all, .normal, .bypass] : [.normal]
        HStack(spacing: 8) {
            ForEach(scopes, id: \.self) { scope in
                let tint = scope == .all ? p.primary : (scope == .normal ? p.home : p.lte)
                let busyHere = model.runningScope == scope
                let enabled = !model.servers.isEmpty && model.runningScope == nil
                Button {
                    Haptics.tap()
                    model.runBalancer(scope)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Circle().fill(enabled || busyHere ? tint : tint.opacity(0.4)).frame(width: 8, height: 8)
                            Text(scope == .all ? "Home+LTE" : (scope == .normal ? "Home" : "LTE"))
                                .font(.vTitleSmall).foregroundColor(p.onSurface).lineLimit(1)
                        }
                        if busyHere {
                            HStack(spacing: 6) {
                                ProgressView().tint(tint).scaleEffect(0.6).frame(width: 12, height: 12)
                                Text(L("pads.searching")).font(.vLabelMedium).foregroundColor(tint)
                            }
                        } else {
                            Text(hasLte ? L("pads.pick") : L("pads.pickBest"))
                                .font(.vLabelMedium).foregroundColor(p.onSurfaceVariant).lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(busyHere ? tint.opacity(0.16) : p.surface,
                                in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(PressStyle())
                .disabled(!enabled)
            }
        }
    }
}

/// One server. In a country card it is a flat row; on its own, a card.
struct ServerRow: View {
    var server: Server
    var selected: Bool
    var subscriptionName: String
    var supported: Bool
    var pinging: Bool
    var inGroup: Bool
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        let ms = server.shownMs
        let shape = RoundedRectangle(cornerRadius: inGroup ? 14 : cardRadius, style: .continuous)
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    if inGroup {
                        RoundedRectangle(cornerRadius: 11, style: .continuous).fill(badgeFill)
                    } else {
                        Circle().fill(badgeFill)
                    }
                    if supported {
                        Text(Countries.of(server.remark).flag).font(.system(size: inGroup ? 18 : 20))
                    } else {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 15)).foregroundColor(p.poor)
                    }
                }
                .frame(width: inGroup ? 38 : 40, height: inGroup ? 38 : 40)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if server.isFavorite {
                            Image(systemName: "star.fill").font(.system(size: 12)).foregroundColor(p.primary)
                        }
                        Text(Countries.withoutLeadingFlag(server.remark))
                            .font(selected ? .vTitleSmall : .vTitleSmallMedium)
                            .foregroundColor(p.onSurface)
                            .lineLimit(1)
                    }
                    Text(subscriptionName.ifBlank(L("server.manual")))
                        .font(.vBodySmall).foregroundColor(p.onSurfaceVariant).lineLimit(1)
                    if !supported {
                        Text(L("server.unsupported")).font(.vLabelSmall).foregroundColor(p.poor)
                    } else if server.failStreak > 0 {
                        Text(L("server.recentlyDown")).font(.vLabelSmall).foregroundColor(p.fair)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .trailing, spacing: 3) {
                    HStack(spacing: 5) {
                        if pinging {
                            ProgressView().scaleEffect(0.6).frame(width: 14, height: 14)
                        } else if ms >= 0 {
                            SignalBars(ms: ms)
                        }
                        Text(Format.latency(ms))
                            .font(.vLabelMediumBold)
                            .monospacedDigit()
                            .foregroundColor(ms >= 0 ? p.latency(ms) : p.onSurfaceVariant)
                    }
                    if selected {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 15)).foregroundColor(p.primary)
                    } else {
                        GroupLabel(role: server.role, bold: true)
                    }
                }
            }
            .padding(.horizontal, inGroup ? 10 : 14)
            .padding(.vertical, inGroup ? 10 : 14)
            .background(container, in: shape)
            .overlay(shape.strokeBorder(selected && !inGroup ? p.primary : Color.clear, lineWidth: 1.5))
            .contentShape(shape)
        }
        .buttonStyle(PressStyle(scale: 0.985))
        .animation(.easeOut(duration: 0.2), value: selected)
    }

    private var badgeFill: Color {
        if !supported { return p.poor.opacity(0.16) }
        return selected ? p.primary.opacity(0.18) : p.surfaceVariant
    }

    private var container: Color {
        if selected { return p.primary.opacity(inGroup ? 0.14 : 0.12) }
        return inGroup ? Color.clear : p.surface
    }
}

/// A country with its servers from every subscription. Tapping the header folds it.
private struct CountryCard<Content: View>: View {
    var country: Countries.Country
    var count: Int
    var bestMs: Int
    var expanded: Bool
    var current: Bool
    var toggle: () -> Void
    @ViewBuilder var content: () -> Content
    @Environment(\.palette) private var p

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cardRadius, style: .continuous)
        VStack(spacing: 0) {
            Button(action: toggle) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle().fill(p.surfaceVariant)
                        Text(country.flag).font(.system(size: 25))
                    }
                    .frame(width: 46, height: 46)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text(Format.countryName(country)).font(.vTitleMedium).foregroundColor(p.onSurface).lineLimit(1)
                            if current { StatusPill(text: L("servers.now"), color: p.primary) }
                        }
                        Text(L10n.plural("servers.count", count)).font(.vBodySmall).foregroundColor(p.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if bestMs >= 0 {
                        VStack(alignment: .trailing, spacing: 2) {
                            SignalBars(ms: bestMs)
                            Text(L("servers.from", Format.latency(bestMs)))
                                .font(.vLabelSmall).monospacedDigit().foregroundColor(p.latency(bestMs))
                        }
                    }
                    Image(systemName: "chevron.down")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(p.onSurfaceVariant)
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                Rectangle().fill(p.outline).frame(height: 1)
                VStack(spacing: 2) { content() }
                    .padding(6)
            }
        }
        .background(p.surface, in: shape)
        .overlay(shape.strokeBorder(current ? p.primary.opacity(0.7) : Color.clear, lineWidth: 1.5))
        .clipShape(shape)
        .animation(Motion.settle, value: expanded)
    }
}

/// Long-press actions of a server.
private struct ServerMenu: View {
    var server: Server
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Button { model.ping(server) } label: { Label(L("menu.ping"), systemImage: "speedometer") }
        Button { model.toggleFavorite(server.id) } label: {
            Label(server.isFavorite ? L("menu.unfavorite") : L("menu.favorite"),
                  systemImage: server.isFavorite ? "star.slash" : "star")
        }
        Menu {
            ForEach(ServerRole.allCases, id: \.self) { role in
                Button {
                    model.setRole(server.id, role)
                } label: {
                    if server.role == role {
                        Label(Format.groupName(role), systemImage: "checkmark")
                    } else {
                        Text(Format.groupName(role))
                    }
                }
            }
        } label: {
            Label(L("menu.group"), systemImage: "square.stack.3d.up")
        }
        if !server.rawLink.isEmpty, !(model.subscription(server.subscriptionId)?.urlHidden ?? false) {
            Button {
                UIPasteboard.general.string = server.rawLink
                model.show(L("menu.copied"))
            } label: { Label(L("menu.copy"), systemImage: "doc.on.doc") }
        }
        if server.subscriptionId == nil {
            Button(role: .destructive) { model.deleteServer(server.id) } label: {
                Label(L("menu.delete"), systemImage: "trash")
            }
        }
    }
}
