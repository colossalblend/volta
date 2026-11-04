import Foundation

/// Routing profiles, provider rules and the tunnel plan: Android's VoltaRepository routing
/// part, kept apart from the connecting logic.
extension AppModel {

    // MARK: - Profiles

    func seedProfiles() {
        if let stored = Store.loadProfiles(), !stored.isEmpty {
            profiles = stored
        } else {
            profiles = RoutingEngine.builtInProfiles()
        }
        // Built-in sets this version has and the stored list lacks.
        for stock in RoutingEngine.builtInProfiles() where !profiles.contains(where: { $0.isBuiltIn && $0.name == stock.name }) {
            var s = stock
            s.isActive = false
            profiles.append(s)
        }
        // The old «Российские сайты напрямую» switch is that built-in set now.
        if settings.russiaDirect {
            settings.russiaDirect = false
            if let ru = profiles.first(where: { $0.isBuiltIn && $0.name == RoutingEngine.builtinRuName }) {
                setActiveProfile(ru.id)
            }
        }
        if !profiles.contains(where: { $0.isActive && $0.sourceSubscriptionId == nil }),
           let i = profiles.firstIndex(where: { $0.isBuiltIn }) {
            profiles[i].isActive = true
        }
        saveProfiles()
    }

    func saveProfiles() { Store.save(profiles: profiles) }

    var appProfiles: [RoutingProfile] { profiles.filter { $0.sourceSubscriptionId == nil } }
    var subscriptionProfiles: [RoutingProfile] { profiles.filter { $0.sourceSubscriptionId != nil } }
    var activeProfile: RoutingProfile? { RoutingResolver.appDefault(profiles) }

    func profile(_ id: Int64) -> RoutingProfile? { profiles.first { $0.id == id } }

    func setActiveProfile(_ id: Int64) {
        guard profiles.contains(where: { $0.id == id && $0.sourceSubscriptionId == nil }) else { return }
        for i in profiles.indices where profiles[i].sourceSubscriptionId == nil { profiles[i].isActive = profiles[i].id == id }
        saveProfiles()
        markPending()
    }

    @discardableResult
    func createProfile(name: String) -> Int64 {
        let p = RoutingProfile(id: Server.newId(), name: name.trimmed.ifBlank(L("routing_new_profile")),
                               description: L("home_routing_own_description"), useProviderRules: false)
        profiles.append(p)
        saveProfiles()
        show(L("home_routing_created"))
        return p.id
    }

    func renameProfile(_ id: Int64, _ name: String) {
        guard let i = profiles.firstIndex(where: { $0.id == id }), !profiles[i].isBuiltIn, !name.isBlank else { return }
        profiles[i].name = name.trimmed
        profiles[i].updatedAt = Date().timeIntervalSince1970
        saveProfiles()
    }

    func deleteProfile(_ id: Int64) {
        guard let p = profile(id), !p.isBuiltIn else { return }
        for i in subscriptions.indices where subscriptions[i].routingProfileId == id { subscriptions[i].routingProfileId = 0 }
        profiles.removeAll { $0.id == id }
        if !profiles.contains(where: { $0.isActive && $0.sourceSubscriptionId == nil }),
           let i = profiles.firstIndex(where: { $0.isBuiltIn }) {
            profiles[i].isActive = true
        }
        saveProfiles()
        Store.save(subscriptions: subscriptions)
        markPending()
    }

    /// A subscription's set copied into an ordinary one the user can edit freely.
    @discardableResult
    func copyProfile(_ id: Int64) -> Int64? {
        guard let source = profile(id) else { return nil }
        var copy = source
        copy.id = Server.newId()
        copy.name = L("home_routing_copy_name", RoutingNames.profileName(source))
        copy.description = L("home_routing_copy_description", RoutingNames.profileName(source))
        copy.isActive = false
        copy.isBuiltIn = false
        copy.sourceSubscriptionId = nil
        copy.useProviderRules = false
        copy.rules = source.rules.map { var r = $0; r.source = "user"; return r }
        copy.geoipUrl = ""
        copy.geositeUrl = ""
        copy.updatedAt = Date().timeIntervalSince1970
        profiles.append(copy)
        saveProfiles()
        show(L("home_routing_copy_created"))
        return copy.id
    }

    func updateRules(_ profileId: Int64, _ rules: [RoutingRule]) {
        guard let i = profiles.firstIndex(where: { $0.id == profileId }) else { return }
        profiles[i].rules = rules.enumerated().map { n, r in var x = r; x.sortOrder = n; return x }
        profiles[i].updatedAt = Date().timeIntervalSince1970
        saveProfiles()
        markPending()
    }

    func setProviderRules(_ profileId: Int64, _ on: Bool) {
        guard let i = profiles.firstIndex(where: { $0.id == profileId }) else { return }
        profiles[i].useProviderRules = on
        saveProfiles()
        markPending()
    }

    /// Binds a profile to one subscription (0 = the app's default); another subscription's
    /// profile is refused.
    func bindRouting(subscription subId: Int64, profile profileId: Int64) {
        guard let i = subscriptions.firstIndex(where: { $0.id == subId }) else { return }
        if profileId != 0 {
            guard let p = profile(profileId) else { return show(L("home_err_rules_not_found")) }
            if let owner = p.sourceSubscriptionId, owner != subId { return show(L("home_err_rules_of_other_subscription")) }
        }
        subscriptions[i].routingProfileId = profileId
        Store.save(subscriptions: subscriptions)
        markPending()
    }

    /// Adds a typed site or address to the rule for [outbound] in [profileId]
    /// («Свои сайты через VPN / напрямую», «Заблокированные сайты»).
    @discardableResult
    func addSite(_ raw: String, outbound: RouteOutbound, to profileId: Int64) -> Bool {
        guard let entry = Presets.parseEntry(raw), let p = profile(profileId) else {
            show(L("routing_not_a_site"))
            return false
        }
        let name: String
        switch outbound {
        case .proxy: name = RoutingNames.mySitesVpn
        case .direct: name = RoutingNames.mySitesDirect
        case .block: name = RoutingNames.blockedSites
        }
        var rules = p.rules
        if let i = rules.firstIndex(where: { $0.name == name && $0.source == "user" }) {
            switch entry {
            case let .domain(d): if !rules[i].domains.contains(d) { rules[i].domains.append(d) }
            case let .ip(ip): if !rules[i].ips.contains(ip) { rules[i].ips.append(ip) }
            }
            rules[i].enabled = true
        } else {
            var rule = RoutingRule(name: name, outbound: outbound, source: "user")
            switch entry {
            case let .domain(d): rule.domains = [d]
            case let .ip(ip): rule.ips = [ip]
            }
            // The user's own sites go first: the first rule that matches decides.
            rules.insert(rule, at: 0)
        }
        updateRules(profileId, rules)
        show(L("routing_added", entry.value.removingPrefix("domain:")))
        return true
    }

    func presetAdded(_ preset: SitePreset, in profileId: Int64) -> Bool {
        profile(profileId)?.rules.contains { $0.name == preset.title } ?? false
    }

    func togglePreset(_ preset: SitePreset, in profileId: Int64) {
        guard let p = profile(profileId) else { return }
        var rules = p.rules
        if rules.contains(where: { $0.name == preset.title }) {
            rules.removeAll { $0.name == preset.title }
        } else {
            rules.insert(preset.toRule(), at: 0)
        }
        updateRules(profileId, rules)
    }

    func importRules(_ json: String, into profileId: Int64) -> Bool {
        do {
            let imported = try RoutingEngine.importRules(json).map { r -> RoutingRule in
                var x = r
                x.source = "user"
                x.id = UUID().uuidString
                return x
            }
            guard let p = profile(profileId) else { return false }
            updateRules(profileId, p.rules + imported)
            show(L10n.plural("routing_rules_count", imported.count))
            return true
        } catch {
            show(L("routing_json_broken"))
            return false
        }
    }

    func exportRules(_ profileId: Int64) -> String {
        JSON.string(["rules": (profile(profileId)?.rules ?? []).map { RoutingEngine.ruleToXray($0, proxyBalancerTag: nil) }],
                    pretty: true)
    }

    /// Changes reach a running tunnel on the next connect.
    func markPending() {
        if isConnected { routingPending = true }
    }

    func applyPendingRouting() {
        routingPending = false
        guard isConnected, let server = selectedServer ?? servers.first(where: { $0.id == guardState?.serverId }) else { return }
        reconnectTunnel(to: server)
    }

    // MARK: - Provider rules on refresh

    /// Android's applySubscriptionRouting: the provider's set is stored stamped with the
    /// subscription, the user's own rules on top of it survive a refresh, and its geo
    /// files are fetched again when the provider says they changed.
    func applyProviderRouting(subscriptionId id: Int64, displayName: String, body: String, parsed: ImportResult) async {
        let header = body.components(separatedBy: "\n").first { $0.hasPrefixIgnoringCase("#routing-link:") }
            .map { $0.substringAfter(":").trimmed } ?? ""
        let payload = body.components(separatedBy: "\n").filter { !$0.trimmed.hasPrefix("#") }.joined(separator: "\n").trimmed
        let jsonConfigs = (payload.hasPrefix("[") || payload.hasPrefix("{")) && payload.contains("\"outbounds\"") &&
            !parsed.servers.contains { $0.roleFromProvider }
        var routing = HappRoutingParser.parseRoutingLink(header)
        if routing == nil && !jsonConfigs {
            routing = HappRoutingParser.extractFromBodyAndHeaders(body: body)
            if routing == nil, !parsed.routingJson.isBlank {
                routing = HappRoutingParser.extractFromBodyAndHeaders(body: parsed.routingJson)
            }
        }
        let providerJson: String
        if let r = routing {
            providerJson = r.rawJson.ifBlank(RoutingEngine.serializeRules(r.rules))
        } else {
            providerJson = jsonConfigs ? "" : parsed.routingJson
        }
        guard let si = subscriptions.firstIndex(where: { $0.id == id }) else { return }
        subscriptions[si].providerRoutingJson = providerJson

        if let r = routing, !r.rules.isEmpty {
            let existing = profiles.first { $0.sourceSubscriptionId == id }
            let userRules = existing?.rules.filter { $0.source == "user" } ?? []
            var entity = RoutingProfile(id: existing?.id ?? Server.newId(),
                                        name: r.name.ifBlank(L("home_routing_rules_name", displayName)),
                                        description: L("home_routing_from_subscription", displayName),
                                        sourceSubscriptionId: id,
                                        useProviderRules: existing?.useProviderRules ?? true,
                                        rules: (userRules + r.rules).enumerated().map { n, x in var y = x; y.sortOrder = n; return y },
                                        domainStrategy: r.domainStrategy.ifBlank("AsIs"))
            entity.remoteDns = r.remoteDns.ifBlank(existing?.remoteDns ?? "https://1.1.1.1/dns-query")
            entity.domesticDns = r.domesticDns.ifBlank(existing?.domesticDns ?? "https://dns.google/dns-query")
            entity.geoipUrl = r.geoipUrl
            entity.geositeUrl = r.geositeUrl
            entity.geoVersion = r.geoVersion
            entity.geoUpdatedAt = existing?.geoUpdatedAt ?? 0
            entity.dnsHostsJson = r.dnsHostsJson
            if entity.geoipUrl.isBlank && entity.geositeUrl.isBlank {
                deleteGeo(id)
                entity.geoUpdatedAt = 0
            } else if geoIsStale(id, entity, existing), await downloadGeo(id, entity) {
                entity.geoUpdatedAt = Date().timeIntervalSince1970
            }
            if let i = profiles.firstIndex(where: { $0.id == entity.id }) { profiles[i] = entity } else { profiles.append(entity) }
            // Bound automatically only when the user has not chosen something else.
            if subscriptions[si].routingProfileId == 0 { subscriptions[si].routingProfileId = entity.id }
        } else if providerJson.isBlank, let stale = profiles.first(where: { $0.sourceSubscriptionId == id }) {
            profiles.removeAll { $0.id == stale.id }
            if subscriptions[si].routingProfileId == stale.id { subscriptions[si].routingProfileId = 0 }
            deleteGeo(id)
        }
        saveProfiles()
    }

    func forgetRouting(ofSubscription id: Int64) {
        profiles.removeAll { $0.sourceSubscriptionId == id }
        deleteGeo(id)
        saveProfiles()
    }

    // MARK: - Provider geo files

    private func geoFile(_ id: Int64, site: Bool) -> URL {
        AppGroup.geoSubscriptionsURL.appendingPathComponent(site ? "geosite_s\(id).dat" : "geoip_s\(id).dat")
    }

    private func deleteGeo(_ id: Int64) {
        try? FileManager.default.removeItem(at: geoFile(id, site: true))
        try? FileManager.default.removeItem(at: geoFile(id, site: false))
    }

    private func geoIsStale(_ id: Int64, _ p: RoutingProfile, _ previous: RoutingProfile?) -> Bool {
        let fm = FileManager.default
        if !p.geoipUrl.isBlank && !fm.fileExists(atPath: geoFile(id, site: false).path) { return true }
        if !p.geositeUrl.isBlank && !fm.fileExists(atPath: geoFile(id, site: true).path) { return true }
        guard let prev = previous else { return true }
        if prev.geoipUrl != p.geoipUrl || prev.geositeUrl != p.geositeUrl || prev.geoVersion != p.geoVersion { return true }
        return Date().timeIntervalSince1970 - prev.geoUpdatedAt > 24 * 3600
    }

    private func downloadGeo(_ id: Int64, _ p: RoutingProfile) async -> Bool {
        var ok = true
        for (url, site) in [(p.geoipUrl, false), (p.geositeUrl, true)] where !url.isBlank {
            ok = await fetchGeo(url, to: geoFile(id, site: site)) && ok
        }
        return ok
    }

    private func fetchGeo(_ raw: String, to target: URL) async -> Bool {
        guard raw.hasPrefixIgnoringCase("https://") || raw.hasPrefixIgnoringCase("http://"),
              let url = URL(string: raw) else { return false }
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.setValue(SubscriptionFetcher.userAgent, forHTTPHeaderField: "User-Agent")
        guard let result = try? await URLSession.shared.data(for: request) else { return false }
        let (data, response) = result
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              // A real list is never this small; an HTML error page often is.
              data.count >= 512 else { return false }
        return (try? data.write(to: target, options: [.atomic])) != nil
    }

    // MARK: - The tunnel plan

    struct RoutingChoice {
        var profile: RoutingProfile?
        var providerJson: String
        var geo: GeoNames?
        var preferServerRouting: Bool
    }

    func routingChoice(for server: Server) -> RoutingChoice {
        let sub = subscription(server.subscriptionId)
        let chosen = RoutingResolver.resolve(sub, profiles)
        var geo: GeoNames?
        if let s = sub, chosen?.sourceSubscriptionId == s.id {
            let fm = FileManager.default
            geo = GeoNames.forSubscription(s.id, hasGeoip: fm.fileExists(atPath: geoFile(s.id, site: false).path),
                                           hasGeosite: fm.fileExists(atPath: geoFile(s.id, site: true).path))
        }
        let explicitApp = sub.map { s in s.routingProfileId > 0 && profile(s.routingProfileId)?.sourceSubscriptionId == nil } ?? false
        return RoutingChoice(profile: chosen, providerJson: RoutingResolver.providerJsonFor(sub, chosen), geo: geo,
                             preferServerRouting: sub != nil && !explicitApp)
    }

    func options(for server: Server, alternates: [Server] = []) -> ConfigBuilder.Options {
        var o = ConfigBuilder.Options()
        o.engine = settings.engine
        o.localDns = settings.localDns
        o.adBlock = settings.adBlock
        o.blockTorrents = settings.blockTorrents
        o.mtu = settings.mtu
        let choice = routingChoice(for: server)
        o.profile = choice.profile
        o.providerRoutingJson = choice.providerJson
        o.geo = choice.geo
        o.preferServerRouting = choice.preferServerRouting
        o.alternates = alternates
        o.assetDir = AppGroup.geoRunURL.path
        return o
    }

    /// The full lists the app ships (CI puts v2fly's geosite and geoip into the bundle).
    static let bundledGeo: (site: GeoData.Index?, ip: GeoData.Index?) = {
        func load(_ name: String) -> GeoData.Index? {
            guard let url = Bundle.main.url(forResource: name, withExtension: "dat")
                    ?? Bundle.main.url(forResource: name, withExtension: "dat", subdirectory: "Geo"),
                  let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
            return GeoData.Index(data)
        }
        return (load("geosite"), load("geoip"))
    }()

    private func geoSources(for configs: [JSONObject]) -> GeoData.Sources {
        var sources = GeoData.Sources(geosite: Self.bundledGeo.site, geoip: Self.bundledGeo.ip)
        let text = configs.map { JSON.string($0) }.joined()
        for match in Rx("ext:(geo(?:ip|site)_s\\d+\\.dat):").allGroups(text) where match.count > 1 {
            let name = match[1]
            if sources.ext[name] == nil,
               let data = try? Data(contentsOf: AppGroup.geoSubscriptionsURL.appendingPathComponent(name)) {
                sources.ext[name] = GeoData.Index(data)
            }
        }
        return sources
    }

    /// Builds the configs (the server and, for auto modes, the rest of its group so the
    /// extension's guard can move to them), writes the trimmed geo lists, then the plan.
    func writeTunnelPlan(for server: Server, mode: ConnectMode) throws {
        let dialable = servers.filter { ConfigBuilder.canDial($0) }
        let effective = GuardPolicy.effectiveMode(mode, hasLte: GuardPolicy.hasLte(dialable))
        func ranked(_ role: ServerRole) -> [Server] {
            var list = Balancer.pool(dialable, role == .bypass ? .bypass : .normal).sorted { $0.score < $1.score }
            if server.role == role, let i = list.firstIndex(where: { $0.id == server.id }) { list.remove(at: i) }
            if server.role == role { list.insert(server, at: 0) }
            return Array(list.prefix(5))
        }
        var groups: [ServerRole: [Server]] = [:]
        switch effective {
        case .autoHomeLte: groups = [.normal: ranked(.normal), .bypass: ranked(.bypass)]
        case .autoHome: groups = [.normal: ranked(.normal)]
        case .autoLte: groups = [.bypass: ranked(.bypass)]
        case .manual: groups = [:]
        }
        if !groups.values.contains(where: { $0.contains { $0.id == server.id } }) { groups = [:] }

        // One config per candidate; the live server's own one first.
        var built: [(server: Server, role: ServerRole, root: JSONObject, alternates: [String: Int64])] = []
        func build(_ s: Server, role: ServerRole, group: [Server]) throws {
            let others = ConfigBuilder.usableAlternates(s, Array(group.filter { $0.id != s.id }.prefix(4)))
            let root = try ConfigBuilder.buildObject(server: s, options: options(for: s, alternates: others))
            var tags: [String: Int64] = [:]
            for (n, alt) in others.enumerated() { tags[ConfigBuilder.alternatePrefix + String(n)] = alt.id }
            built.append((server: s, role: role, root: root, alternates: tags))
        }
        try build(server, role: server.role == .bypass ? .bypass : .normal,
                  group: groups[server.role == .bypass ? .bypass : .normal] ?? [])
        for (role, group) in groups {
            for s in group where s.id != server.id { try? build(s, role: role, group: group) }
        }

        // Geo lists: one trimmed set for every config together.
        let sources = geoSources(for: built.map { $0.root })
        var files: [String: Data] = [:]
        var finalConfigs: [String] = []
        for item in built {
            let prepared = try GeoData.prepare(item.root, sources: sources)
            for (name, data) in prepared.files { files[name] = files[name].map { GeoData.merge($0, data) } ?? data }
            finalConfigs.append(JSON.string(prepared.config))
        }
        let dir = AppGroup.geoRunURL
        for old in (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [] {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(old))
        }
        for (name, data) in files { try data.write(to: dir.appendingPathComponent(name), options: [.atomic]) }

        var guardPlan: GuardPlan?
        if !groups.isEmpty {
            var plan = GuardPlan(mode: effective, followNetwork: settings.followNetwork, autoRecheck: settings.autoRecheck,
                                 group: server.role == .bypass ? .bypass : .normal)
            for (i, item) in built.enumerated() {
                let c = GuardCandidate(id: item.server.id, name: item.server.remark, address: item.server.address,
                                       port: item.server.port, config: finalConfigs[i],
                                       usualMs: item.server.ewmaMs > 0 ? item.server.ewmaMs : item.server.latencyMs,
                                       alternates: item.alternates)
                if item.role == .bypass { plan.lte.append(c) } else { plan.home.append(c) }
            }
            // Each group in its ranked order (the live server's config was built first).
            plan.home.sort { a, b in (groups[.normal]?.firstIndex { $0.id == a.id } ?? 0) < (groups[.normal]?.firstIndex { $0.id == b.id } ?? 0) }
            plan.lte.sort { a, b in (groups[.bypass]?.firstIndex { $0.id == a.id } ?? 0) < (groups[.bypass]?.firstIndex { $0.id == b.id } ?? 0) }
            guardPlan = plan
        }

        // The chosen server first, then the others, so the app's pings bypass the tunnel.
        var addresses = [server.address]
        for s in servers where !addresses.contains(s.address) && !s.address.isBlank { addresses.append(s.address) }
        let dnsInCore = settings.localDns || settings.adBlock != .off
        var plan = TunnelPlan(
            xrayConfig: finalConfigs[0],
            engine: settings.engine,
            serverName: server.remark,
            serverId: server.id,
            excludedAddresses: Array(addresses.prefix(96)),
            mtu: settings.mtu,
            dnsServers: dnsInCore ? [ConfigBuilder.tunnelDns] : ["1.1.1.1", "8.8.8.8"]
        )
        plan.guardPlan = guardPlan
        try AppGroup.writePlan(plan)
        AppGroup.writeGuardState(GuardState(serverId: server.id, serverName: server.remark,
                                            group: server.role == .bypass ? .bypass : .normal,
                                            switchedAt: Date().timeIntervalSince1970, reason: nil))
    }
}
