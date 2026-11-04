import SwiftUI
import UIKit

/// Every subscription the app holds. Their servers are merged into one list (Servers
/// tab) and split by group. Adding lives in the header, as on the other tabs.
struct SubscriptionsScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @State private var editing: Subscription?

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 10) {
                ScreenHeader(title: L("tab.subscriptions")) {
                    if !model.subscriptions.isEmpty {
                        RoundIconButton(icon: "arrow.clockwise", label: L("subs.refreshAll"), busy: model.busy) {
                            Task { await model.refreshAll() }
                        }
                        .disabled(model.busy)
                    }
                    RoundIconButton(icon: "plus", label: L("subs.add")) {
                        model.importDraft = ""
                        model.importOpen = true
                    }
                }

                if model.subscriptions.isEmpty {
                    EmptyState(icon: "icloud.and.arrow.down", title: L("subs.empty.title"), subtitle: L("subs.empty.text")) {
                        PrimaryButton(title: L("home.first.button"), height: 48) {
                            model.importDraft = ""
                            model.importOpen = true
                        }
                        .frame(maxWidth: 260)
                        .padding(.top, 8)
                    }
                } else {
                    Hint(L("subs.hint")).padding(.bottom, 2)
                    ForEach(model.subscriptions) { sub in
                        SubscriptionCard(
                            sub: sub,
                            servers: model.servers.filter { $0.subscriptionId == sub.id },
                            active: model.selectedServer?.subscriptionId == sub.id,
                            refreshing: model.busy
                        ) {
                            editing = sub
                        } onRefresh: {
                            model.refreshSubscription(sub.id)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .sheet(item: $editing) { sub in
            SubscriptionEditor(subscriptionId: sub.id)
                .environment(\.palette, p)
                .environmentObject(model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
}

/// One subscription: a coin in its colour, how its servers split into Home, LTE and
/// balancers, and how much traffic and time is left.
private struct SubscriptionCard: View {
    var sub: Subscription
    var servers: [Server]
    var active: Bool
    var refreshing: Bool
    var onOpen: () -> Void
    var onRefresh: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        let tint = Color(hexString: sub.colorHex) ?? Color(hex: 0xC4784A)
        let home = servers.filter { $0.role == .normal }.count
        let lte = servers.filter { $0.role == .bypass }.count
        let auto = servers.filter { $0.role == .auto }.count
        VoltaCard(selected: active, action: onOpen) {
            HStack(spacing: 12) {
                SubscriptionCoin(name: sub.name, tint: tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(sub.name).font(.vTitleMedium).foregroundColor(p.onSurface).lineLimit(1)
                    Text(Format.updated(sub.lastUpdated)).font(.vBodySmall).foregroundColor(p.onSurfaceVariant).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RoundIconButton(icon: "arrow.clockwise", label: L("subs.refresh"), action: onRefresh)
                    .disabled(refreshing)
            }
            if home + lte + auto > 0 {
                GroupSplit(home: home, lte: lte, auto: auto).padding(.top, 14)
            }
            if sub.trafficTotal > 0 || sub.expireAt > 0 {
                HStack(alignment: .bottom) {
                    Text(sub.trafficTotal > 0
                         ? L("subs.traffic", Format.bytes(sub.trafficUsed), Format.bytes(sub.trafficTotal))
                         : L("subs.unlimited"))
                        .font(.vLabelLarge)
                        .foregroundColor(p.onSurface)
                    Spacer()
                    if sub.expireAt > 0 {
                        let days = Format.daysLeft(sub.expireAt)
                        Text(Format.expiry(sub.expireAt))
                            .font(.vLabelMedium)
                            .foregroundColor(days < 3 ? p.poor : (days < 7 ? p.fair : p.onSurfaceVariant))
                    }
                }
                .padding(.top, 14)
                if sub.trafficTotal > 0 {
                    UsageBar(used: sub.trafficUsed, total: sub.trafficTotal).padding(.top, 6)
                }
            }
            if sub.urlHidden {
                StatusPill(text: L("subs.hidden"), color: p.onSurfaceVariant, icon: "lock.fill").padding(.top, 12)
            }
            if !sub.announce.isBlank {
                // The provider's own words, set apart from the app's.
                HStack(alignment: .top, spacing: 10) {
                    Capsule().fill(tint.opacity(0.6)).frame(width: 3)
                    Text(sub.announce).font(.vBodySmall).foregroundColor(p.onSurfaceVariant).lineLimit(3)
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
            }
        }
    }
}

/// A coin in the subscription's colour with the first letter of its name.
struct SubscriptionCoin: View {
    var name: String
    var tint: Color
    var size: CGFloat = 42
    @Environment(\.palette) private var p

    var body: some View {
        let initial = name.first { $0.isLetter || $0.isNumber }.map { String($0).uppercased() } ?? "V"
        ZStack {
            Circle().fill(tint.opacity(p.isDark ? 0.22 : 0.16))
            Text(initial).font(VoltaFont.unbounded(size * 0.42, 600)).foregroundColor(tint)
        }
        .frame(width: size, height: size)
    }
}

/// How the servers split between the groups: one bar in the group colours, and the
/// counts under it in the same colours.
private struct GroupSplit: View {
    var home: Int
    var lte: Int
    var auto: Int
    @Environment(\.palette) private var p

    var body: some View {
        let neutral = p.onSurfaceVariant.opacity(0.45)
        let parts: [(Int, Color, String, Bool)] = [
            (home, p.home, "Home", false), (lte, p.lte, "LTE", false), (auto, neutral, L("pool.balancers"), true)
        ].filter { $0.0 > 0 }
        let total = max(1, parts.reduce(0) { $0 + $1.0 })
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                let gaps = CGFloat(max(0, parts.count - 1)) * 3
                HStack(spacing: 3) {
                    ForEach(parts.indices, id: \.self) { i in
                        Capsule()
                            .fill(parts[i].1)
                            .frame(width: max(4, (geo.size.width - gaps) * CGFloat(parts[i].0) / CGFloat(total)))
                    }
                }
            }
            .frame(height: 6)
            HStack(spacing: 14) {
                ForEach(parts.indices, id: \.self) { i in
                    HStack(spacing: 5) {
                        Text(parts[i].2).font(.vLabelMedium).foregroundColor(p.onSurfaceVariant)
                        Text("\(parts[i].0)").font(.vLabelLarge).foregroundColor(parts[i].3 ? p.onSurface : parts[i].1)
                    }
                }
            }
        }
    }
}

/// One subscription, opened from its card: rename it, change its address (unless the
/// provider hid it), refresh it now or delete it; deleting asks once, in place.
private struct SubscriptionEditor: View {
    var subscriptionId: Int64
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var confirmDelete = false
    @State private var loaded = false

    var body: some View {
        let sub = model.subscriptions.first { $0.id == subscriptionId }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let sub = sub {
                    header(sub)
                    if confirmDelete {
                        deleteConfirmation
                    } else {
                        form(sub)
                    }
                }
            }
            .padding(20)
        }
        .background(p.surface.ignoresSafeArea())
        .onAppear {
            guard !loaded, let sub = sub else { return }
            loaded = true
            name = sub.name
            // A hidden address is never put into the UI at all.
            url = sub.urlHidden ? "" : sub.url
        }
        .animation(Motion.settle, value: confirmDelete)
    }

    private func header(_ sub: Subscription) -> some View {
        let count = model.servers.filter { $0.subscriptionId == sub.id }.count
        return HStack(spacing: 14) {
            SubscriptionCoin(name: sub.name, tint: Color(hexString: sub.colorHex) ?? p.primary, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(sub.name).font(.vTitleLarge).foregroundColor(p.onSurface).lineLimit(1)
                Hint([count > 0 ? L10n.plural("servers.count", count) : "", Format.updated(sub.lastUpdated)]
                        .filter { !$0.isEmpty }.joined(separator: ", "))
            }
        }
        .padding(.bottom, 20)
    }

    private func form(_ sub: Subscription) -> some View {
        let changed = name.trimmed != sub.name || (!sub.urlHidden && url.trimmed != sub.url)
        return VStack(alignment: .leading, spacing: 12) {
            VoltaField(text: $name, label: L("editor.name"), restingOutline: true)
            if sub.urlHidden {
                HStack(spacing: 10) {
                    Image(systemName: "lock.fill").foregroundColor(p.onSurfaceVariant)
                    Hint(L("editor.hiddenLink"))
                }
                .padding(.horizontal, 4)
            } else {
                VoltaField(text: $url, label: L("editor.link"), multiline: true, minLines: 1, maxLines: 4,
                           restingOutline: true, keyboard: .URL)
            }
            PrimaryButton(title: L("editor.save"), busy: model.busy, enabled: changed && !name.isBlank) {
                Task {
                    if await model.updateSubscription(sub.id, name: name, url: url) { dismiss() }
                }
            }
            .padding(.top, 6)
            SheetAction(icon: "arrow.clockwise", title: L("editor.refresh"), subtitle: L("editor.refresh.hint")) {
                model.refreshSubscription(sub.id)
            }
            if !sub.supportUrl.isBlank, let link = URL(string: sub.supportUrl) {
                SheetAction(icon: "questionmark.bubble", title: L("editor.support"), subtitle: sub.supportUrl) {
                    UIApplication.shared.open(link)
                }
            }
            if !sub.urlHidden {
                SheetAction(icon: "doc.on.doc", title: L("editor.copy")) {
                    UIPasteboard.general.string = sub.url
                    model.show(L("editor.copied"))
                }
            }
            SheetAction(icon: "trash", title: L("editor.delete"), danger: true) {
                confirmDelete = true
            }
        }
    }

    private var deleteConfirmation: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("editor.delete.title")).font(.vTitleMedium).foregroundColor(p.onSurface)
            Text(L("editor.delete.text")).font(.vBodyMedium).foregroundColor(p.onSurfaceVariant)
            Button {
                Haptics.tap()
                model.deleteSubscription(subscriptionId)
                dismiss()
            } label: {
                Text(L("editor.delete.confirm"))
                    .font(.vTitleSmall)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(p.error, in: Capsule())
            }
            .buttonStyle(PressStyle())
            .padding(.top, 8)
            Button {
                confirmDelete = false
            } label: {
                Text(L("common.cancel"))
                    .font(.vTitleSmall)
                    .foregroundColor(p.onSurface)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(p.surfaceVariant, in: Capsule())
            }
            .buttonStyle(PressStyle())
        }
    }
}
