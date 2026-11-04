import SwiftUI
import UIKit

/// «Маршруты», as on Android: the app-wide filters (ads, torrents), sites past the VPN
/// (iOS has no per-app VPN outside managed devices), the rule sets and which one each
/// subscription uses.
struct RoutingScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @State private var editing: RoutingProfile?
    @State private var naming = false
    @State private var newName = ""
    @State private var bypassSite = ""

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 10) {
                ScreenHeader(title: L("routing_title")) {
                    RoundIconButton(icon: "plus", label: L("routing_new_profile")) {
                        newName = ""
                        naming = true
                    }
                }
                if model.routingPending { pendingCard }

                SectionHeader(L("routing_filters"))
                VoltaCard(padding: 10) {
                    AdBlockBlock()
                    SwitchRow(icon: "arrow.down.circle", tint: p.poor, title: L("routing_torrents_title"),
                              subtitle: L("routing_torrents_subtitle"),
                              isOn: Binding(get: { model.settings.blockTorrents },
                                            set: { model.settings.blockTorrents = $0; model.markPending() }))
                }

                SectionHeader(L("routing_apps"))
                VoltaCard(spacing: 10) {
                    Text(L("split.ios.title")).font(.vTitleSmall).foregroundColor(p.onSurface)
                    Hint(L("split.ios.text"))
                    HStack(spacing: 8) {
                        VoltaField(text: $bypassSite, placeholder: L("routing_add_site_placeholder"), keyboard: .URL)
                        FieldButton(icon: "plus", text: L("routing_add"), filled: !bypassSite.isBlank) {
                            guard let id = model.activeProfile?.id else { return }
                            if model.addSite(bypassSite, outbound: .direct, to: id) { bypassSite = "" }
                        }
                    }
                    Hint(L("split.ios.where", model.activeProfile.map(RoutingNames.profileName) ?? ""))
                }

                SectionHeader(L("routing_profiles"))
                Hint(L("routing_profiles_hint")).padding(.horizontal, 4)
                ForEach(model.appProfiles) { profile in
                    ProfileRow(profile: profile, active: profile.isActive, owner: nil,
                               select: { model.setActiveProfile(profile.id) }) { editing = profile }
                }

                if !model.subscriptionProfiles.isEmpty {
                    SectionHeader(L("routing_from_subscriptions"))
                    Hint(L("routing_from_subscriptions_hint")).padding(.horizontal, 4)
                    ForEach(model.subscriptionProfiles) { profile in
                        ProfileRow(profile: profile, active: false,
                                   owner: model.subscription(profile.sourceSubscriptionId)?.name, select: nil) {
                            editing = profile
                        }
                    }
                }

                if !model.subscriptions.isEmpty {
                    SectionHeader(L("routing_subscriptions"))
                    Hint(L("routing_subscriptions_hint", L("routing_default"))).padding(.horizontal, 4)
                    VoltaCard(padding: 10) {
                        ForEach(model.subscriptions) { sub in SubscriptionBinding(sub: sub) }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .sheet(item: $editing) { profile in
            ProfileEditor(profileId: profile.id)
                .environment(\.palette, p)
                .environmentObject(model)
                .presentationDragIndicator(.visible)
        }
        .alert(L("routing_new_profile"), isPresented: $naming) {
            TextField(L("routing_name"), text: $newName)
            Button(L("routing_cancel"), role: .cancel) {}
            Button(L("routing_create")) {
                let id = model.createProfile(name: newName)
                editing = model.profile(id)
            }
        }
    }

    private var pendingCard: some View {
        VoltaCard(selected: true) {
            HStack(spacing: 12) {
                Image(systemName: "arrow.triangle.2.circlepath").foregroundColor(p.primary)
                Text(L("routing_pending")).font(.vBodyMedium).foregroundColor(p.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                FieldButton(icon: "checkmark", text: L("routing_apply"), filled: true) { model.applyPendingRouting() }
            }
        }
    }
}

/// Ad blocking: off, or one of Android's three levels, with what each one does.
private struct AdBlockBlock: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    private let levels: [AdBlockMode] = [.standard, .family, .strict]

    var body: some View {
        let on = Binding(get: { model.settings.adBlock != .off },
                         set: { model.settings.adBlock = $0 ? .standard : .off; model.markPending() })
        SwitchRow(icon: "shield.lefthalf.filled", tint: p.good, title: L("routing_adblock_title"),
                  subtitle: L("routing_adblock_subtitle"), isOn: on)
        if model.settings.adBlock != .off {
            VStack(alignment: .leading, spacing: 8) {
                SlidingChoice(options: levels,
                              selection: Binding(get: { model.settings.adBlock },
                                                 set: { model.settings.adBlock = $0; model.markPending() }),
                              label: { level in
                                  switch level {
                                  case .family: return L("routing_adblock_family")
                                  case .strict: return L("routing_adblock_strict")
                                  default: return L("routing_adblock_standard")
                                  }
                              }, height: 38)
                switch model.settings.adBlock {
                case .family: Hint(L("routing_adblock_family_hint"))
                case .strict: Hint(L("routing_adblock_strict_hint"))
                default: Hint(L("routing_adblock_standard_desc"))
                }
                Hint(L("routing_adblock_test_note"))
            }
            .padding(.leading, 48)
            .padding(.bottom, 8)
        }
    }
}

private struct ProfileRow: View {
    var profile: RoutingProfile
    var active: Bool
    var owner: String?
    var select: (() -> Void)?
    var open: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        VoltaCard(selected: active, action: open) {
            HStack(spacing: 12) {
                if let select = select {
                    Button {
                        Haptics.select()
                        select()
                    } label: {
                        Image(systemName: active ? "largecircle.fill.circle" : "circle")
                            .font(.system(size: 22)).foregroundColor(active ? p.primary : p.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("routing_choose_profile"))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(RoutingNames.profileName(profile)).font(.vTitleSmall).foregroundColor(p.onSurface)
                    let description = owner.map { L("routing_profile_owner", $0) } ?? RoutingNames.profileDescription(profile)
                    if !description.isBlank { Hint(description) }
                    HStack(spacing: 6) {
                        if active { StatusPill(text: L("routing_active_now"), color: p.good, icon: nil) }
                        StatusPill(text: profile.isBuiltIn ? L("routing_profile_builtin")
                                   : (owner != nil ? L("routing_sub_sent_rules") : L("routing_profile_custom")),
                                   color: p.onSurfaceVariant, icon: nil)
                        Text(L10n.plural("routing_rules_count", profile.rules.count))
                            .font(.vLabelMedium).foregroundColor(p.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                ChevronView()
            }
        }
    }
}

/// Which set one subscription uses.
private struct SubscriptionBinding: View {
    var sub: Subscription
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p

    var body: some View {
        let choices = RoutingResolver.choicesFor(sub, model.profiles)
        let effective = RoutingResolver.resolve(sub, model.profiles)
        Menu {
            Button(L("routing_default")) { model.bindRouting(subscription: sub.id, profile: 0) }
            ForEach(choices) { p in
                Button(p.sourceSubscriptionId != nil ? L("routing_own_subscription", RoutingNames.profileName(p))
                       : RoutingNames.profileName(p)) {
                    model.bindRouting(subscription: sub.id, profile: p.id)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Circle().fill(Color(hexString: sub.colorHex) ?? p.primary).frame(width: 12, height: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(sub.name).font(.vTitleSmall).foregroundColor(p.onSurface)
                    Hint(sub.routingProfileId == 0
                         ? L("routing_sub_default", effective.map(RoutingNames.profileName) ?? "")
                         : (effective.map(RoutingNames.profileName) ?? ""))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.up.chevron.down").foregroundColor(p.onSurfaceVariant)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// One rule set: quick add, ready-made lists, the rules in order.
struct ProfileEditor: View {
    var profileId: Int64
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var site = ""
    @State private var direction: RouteOutbound = .proxy
    @State private var expanded: String?
    @State private var renaming = false
    @State private var name = ""
    @State private var deleting = false
    @State private var importing = false
    @State private var importText = ""

    var body: some View {
        let profile = model.profile(profileId)
        ScrollView(showsIndicators: false) {
            if let profile = profile {
                LazyVStack(alignment: .leading, spacing: 10) {
                    header(profile)
                    if profile.sourceSubscriptionId != nil { providerBlock(profile) }
                    quickAdd(profile)
                    presets(profile)
                    rules(profile)
                    tools(profile)
                }
                .padding(16)
            }
        }
        .background(p.background.ignoresSafeArea())
        .alert(L("routing_rename"), isPresented: $renaming) {
            TextField(L("routing_name"), text: $name)
            Button(L("routing_cancel"), role: .cancel) {}
            Button(L("routing_save")) { model.renameProfile(profileId, name) }
        }
        .alert(L("routing_delete_title", profile.map(RoutingNames.profileName) ?? ""), isPresented: $deleting) {
            Button(L("routing_cancel"), role: .cancel) {}
            Button(L("routing_delete"), role: .destructive) {
                dismiss()
                model.deleteProfile(profileId)
            }
        } message: {
            Text(L("routing_delete_text"))
        }
        .alert(L("routing_import_export"), isPresented: $importing) {
            TextField("JSON", text: $importText)
            Button(L("routing_cancel"), role: .cancel) {}
            Button(L("routing_add")) { _ = model.importRules(importText, into: profileId) }
        }
    }

    private func header(_ profile: RoutingProfile) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(RoutingNames.profileName(profile)).font(.vTitleLarge).foregroundColor(p.onBackground)
                let d = RoutingNames.profileDescription(profile)
                if !d.isBlank { Hint(d) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !profile.isBuiltIn && profile.sourceSubscriptionId == nil {
                RoundIconButton(icon: "pencil", label: L("routing_rename")) {
                    name = profile.name
                    renaming = true
                }
            }
            RoundIconButton(icon: "checkmark", label: L("routing_done")) { dismiss() }
        }
        .padding(.top, 8)
    }

    private func providerBlock(_ profile: RoutingProfile) -> some View {
        VoltaCard(padding: 10) {
            SwitchRow(icon: "icloud.and.arrow.down", tint: p.home, title: L("routing_provider_rules"),
                      subtitle: profile.useProviderRules ? L("routing_provider_rules_on") : L("routing_provider_rules_off"),
                      isOn: Binding(get: { profile.useProviderRules }, set: { model.setProviderRules(profileId, $0) }))
            LinkRow(icon: "doc.on.doc", tint: p.primary, title: L("routing_make_copy"),
                    subtitle: L("routing_from_subscriptions_hint")) {
                if let id = model.copyProfile(profileId) {
                    dismiss()
                    _ = id
                }
            }
        }
    }

    private func quickAdd(_ profile: RoutingProfile) -> some View {
        VoltaCard(spacing: 10) {
            Text(L("routing_add_site")).font(.vTitleSmall).foregroundColor(p.onSurface)
            VoltaField(text: $site, placeholder: L("routing_add_site_placeholder"), keyboard: .URL)
            SlidingChoice(options: RouteOutbound.allCases, selection: $direction, label: outboundLabel, height: 36)
            PrimaryButton(title: L("routing_add"), enabled: !site.isBlank, height: 44) {
                if model.addSite(site, outbound: direction, to: profileId) { site = "" }
            }
        }
    }

    private func presets(_ profile: RoutingProfile) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(L("routing_presets"))
            Hint(L("routing_presets_hint"))
            FlowChips(items: Presets.sites) { preset in
                VoltaChip(text: preset.shownTitle, selected: model.presetAdded(preset, in: profileId),
                          accent: preset.suggested == .direct ? p.good : nil, icon: nil, rest: p.surface) {
                    Haptics.select()
                    model.togglePreset(preset, in: profileId)
                }
            }
        }
    }

    private func rules(_ profile: RoutingProfile) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(L("routing_rules"))
            Hint(L("routing_rules_hint"))
            if profile.rules.isEmpty {
                VoltaCard { Hint(L("routing_rules_empty")) }
            }
            ForEach(Array(profile.rules.enumerated()), id: \.element.id) { index, rule in
                RuleCard(rule: rule, index: index, count: profile.rules.count, expanded: expanded == rule.id,
                         onToggleExpand: { expanded = expanded == rule.id ? nil : rule.id },
                         onChange: { changed in
                             var list = profile.rules
                             if index < list.count { list[index] = changed }
                             model.updateRules(profileId, list)
                         },
                         onMove: { delta in
                             var list = profile.rules
                             let to = index + delta
                             guard list.indices.contains(to) else { return }
                             list.swapAt(index, to)
                             model.updateRules(profileId, list)
                         },
                         onRemove: {
                             var list = profile.rules
                             list.remove(at: index)
                             model.updateRules(profileId, list)
                         })
            }
            HStack {
                Image(systemName: "arrow.turn.down.right").foregroundColor(p.onSurfaceVariant)
                Text(L("routing_rest_via_vpn")).font(.vBodyMedium).foregroundColor(p.onSurfaceVariant)
            }
            .padding(.horizontal, 6)
        }
    }

    private func tools(_ profile: RoutingProfile) -> some View {
        VoltaCard(padding: 10) {
            LinkRow(icon: "square.and.arrow.up", tint: p.primary, title: L("routing_import_export"),
                    subtitle: L("routing.export.hint")) {
                UIPasteboard.general.string = model.exportRules(profileId)
                model.show(L("routing.export.copied"))
            }
            LinkRow(icon: "doc.on.clipboard", tint: p.primary, title: L("routing.import.title"),
                    subtitle: L("routing.import.hint")) {
                importText = UIPasteboard.general.string ?? ""
                if importText.isBlank { importing = true } else { _ = model.importRules(importText, into: profileId) }
            }
            if !profile.isBuiltIn && profile.sourceSubscriptionId == nil {
                LinkRow(icon: "trash", tint: p.error, title: L("routing_delete"), subtitle: nil) { deleting = true }
            }
        }
    }

    private func outboundLabel(_ o: RouteOutbound) -> String {
        switch o {
        case .proxy: return L("routing_action_vpn")
        case .direct: return L("routing_direct")
        case .block: return L("routing_action_block")
        }
    }
}

/// One rule: where it sends, what it matches, its place in the order.
private struct RuleCard: View {
    var rule: RoutingRule
    var index: Int
    var count: Int
    var expanded: Bool
    var onToggleExpand: () -> Void
    var onChange: (RoutingRule) -> Void
    var onMove: (Int) -> Void
    var onRemove: () -> Void
    @Environment(\.palette) private var p
    @State private var entries = ""

    var body: some View {
        VoltaCard(spacing: 8) {
            HStack(spacing: 10) {
                Button(action: onToggleExpand) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.name.isBlank ? L("routing_rule_untitled") : RoutingNames.ruleName(rule.name))
                            .font(.vTitleSmall).foregroundColor(rule.enabled ? p.onSurface : p.onSurfaceVariant)
                        Hint(summary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Menu {
                    ForEach(RouteOutbound.allCases, id: \.self) { o in
                        Button(label(o)) { var r = rule; r.outbound = o; onChange(r) }
                    }
                } label: {
                    StatusPill(text: short(rule.outbound), color: color(rule.outbound), icon: nil)
                }
                Toggle("", isOn: Binding(get: { rule.enabled }, set: { var r = rule; r.enabled = $0; onChange(r) }))
                    .labelsHidden().tint(p.primary)
            }
            if rule.source == "provider" { Hint(L("routing_rule_from_provider")) }
            if rule.source == "builtin" { Hint(L("routing_rule_builtin")) }
            if expanded {
                Text(L("routing_sites_and_addresses")).font(.vLabelMedium).foregroundColor(p.onSurfaceVariant)
                VoltaField(text: $entries, placeholder: L("routing_add_site_or_address"), multiline: true, minLines: 3, maxLines: 10)
                    .onAppear { entries = (rule.domains + rule.ips).joined(separator: "\n") }
                HStack(spacing: 8) {
                    FieldButton(icon: "checkmark", text: L("routing_save"), filled: true) { save() }
                    FieldButton(icon: "arrow.up", text: L("routing_move_up"), filled: false) { onMove(-1) }
                        .disabled(index == 0)
                    FieldButton(icon: "arrow.down", text: L("routing_move_down"), filled: false) { onMove(1) }
                        .disabled(index >= count - 1)
                    Spacer(minLength: 0)
                    RoundIconButton(icon: "trash", label: L("routing_remove"), action: onRemove)
                }
            }
        }
    }

    private func save() {
        var r = rule
        r.domains = []
        r.ips = []
        for line in entries.components(separatedBy: CharacterSet(charactersIn: "\n,")) {
            switch Presets.parseEntry(line) {
            case let .domain(d)?: if !r.domains.contains(d) { r.domains.append(d) }
            case let .ip(ip)?: if !r.ips.contains(ip) { r.ips.append(ip) }
            case nil: break
            }
        }
        onChange(r)
        onToggleExpand()
    }

    private var summary: String {
        var parts: [String] = []
        if !rule.domains.isEmpty { parts.append(L10n.plural("routing_sites_count", rule.domains.count)) }
        if !rule.ips.isEmpty { parts.append(L10n.plural("routing_addresses_count", rule.ips.count)) }
        if !rule.protocol.isEmpty || !rule.ports.isBlank || !rule.network.isBlank || !rule.attributes.isBlank {
            parts.append(parts.isEmpty ? L("routing_no_conditions") : L("routing_special_conditions"))
        }
        return parts.isEmpty ? L("routing_empty_rule") : parts.joined(separator: " ")
    }

    private func label(_ o: RouteOutbound) -> String {
        switch o {
        case .proxy: return L("routing_action_vpn")
        case .direct: return L("routing_direct")
        case .block: return L("routing_action_block")
        }
    }

    private func short(_ o: RouteOutbound) -> String {
        switch o {
        case .proxy: return "VPN"
        case .direct: return L("routing_direct")
        case .block: return L("routing_short_block")
        }
    }

    private func color(_ o: RouteOutbound) -> Color {
        switch o {
        case .proxy: return p.primary
        case .direct: return p.good
        case .block: return p.poor
        }
    }
}

/// Chips that wrap onto the next line.
struct FlowChips<Item: Identifiable, Chip: View>: View {
    var items: [Item]
    @ViewBuilder var chip: (Item) -> Chip

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items) { chip($0) }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += row + spacing; row = 0 }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}
