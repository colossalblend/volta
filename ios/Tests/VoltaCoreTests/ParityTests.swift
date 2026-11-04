import XCTest
@testable import VoltaCore

/// Encrypted links must open the same in the provider tool, on Android and here.
final class CryptoInteropTests: XCTestCase {

    /// Vectors from Android's VoltaLinksTest (made by tools/volta_crypt.py).
    func testProviderToolVectorsOpen() {
        let plain = "AQEEiz2QtC8zTkBrHcMezKfX1oeoqreqVx_lVRq7__VKm_48YOyj5zmGifePJKPBgSPZzU2p2w62uPK_H1S0_UAsSvN4pDYwZ042R2ZhFSPOi5WeYyPiViaNyottS2_rkzn6nEWxKMDMRz-2tickeHHtIUqaHy3l-82qjdA"
        guard case let .addEncrypted(p, n, t) = VoltaLinks.parse("volta://crypt/\(plain)") else {
            return XCTFail("not an encrypted link")
        }
        guard case let .addSubscription(url, _, visible, _) = VoltaLinks.openEncrypted(payload: p, name: n, serverTypes: t) else {
            return XCTFail("did not open")
        }
        XCTAssertEqual(url, "https://sub.example/py?token=7")
        XCTAssertNotEqual(visible, true)

        let withJson = "AQEExqoPL4ZwFmoQwnfo6UvidEQ4bfWF0Ke3yuGBgWphc7sbLRWpRypAfNUHiYQlkTPVeenqgBAp6kRitsBGKkkC-FoAzGV5RTjkEXFTU5AGZE_pNGf63e-4MMrKjCq-A1zmpbSYjvH_7uJZ9U8V4PvhEoR66Vr7AzklnGkCrguZXA-2cULRwQNdtK9P_KM6jTR536BnCK9-5kk-sPIaqrPF75xgGvSK"
        guard case let .addEncrypted(p2, n2, t2) = VoltaLinks.parse("volta://crypt/\(withJson)"),
              case let .addSubscription(url2, name2, visible2, types2) = VoltaLinks.openEncrypted(payload: p2, name: n2, serverTypes: t2) else {
            return XCTFail("did not open the JSON link")
        }
        XCTAssertEqual(url2, "https://sub.example/py2")
        XCTAssertEqual(name2, "Py VPN")
        XCTAssertEqual(types2, "lte:1,2")
        XCTAssertNotEqual(visible2, true)
    }

    func testEncryptRoundTripAndEachIsDifferent() throws {
        let a = try SubCrypto.encrypt("https://a.example/sub?x=1")
        let b = try SubCrypto.encrypt("https://a.example/sub?x=1")
        XCTAssertNotEqual(a, b)
        XCTAssertEqual(try SubCrypto.decrypt(a), "https://a.example/sub?x=1")
        XCTAssertFalse(a.contains("=") || a.contains("+") || a.contains("/"), "base64url without padding")
    }

    func testTamperedPayloadIsRefused() throws {
        var chars = Array(try SubCrypto.encrypt("https://sub.example/secret"))
        let i = chars.count - 5
        chars[i] = chars[i] == "A" ? "B" : "A"
        XCTAssertThrowsError(try SubCrypto.decrypt(String(chars)))
        XCTAssertThrowsError(try SubCrypto.decrypt("not-a-payload"))
    }
}

final class RoutingTests: XCTestCase {

    private func server() -> Server {
        var s = Server(remark: "T", proto: .trojan, address: "t.example", port: 443)
        s.uuid = "pw"
        s.security = "tls"
        return s
    }

    private func rules(_ root: JSONObject) -> [JSONObject] {
        (root.obj("routing")?["rules"] as? [JSONObject]) ?? []
    }

    func testBuiltInProfilesAsOnAndroid() {
        let profiles = RoutingEngine.builtInProfiles()
        XCTAssertEqual(profiles.map(\.name), ["Весь трафик через VPN", "Российские сайты напрямую"])
        XCTAssertTrue(profiles[0].isActive)
        XCTAssertEqual(profiles[1].domainStrategy, "IPIfNonMatch")
        XCTAssertTrue(profiles[1].rules[0].domains.contains("geosite:category-ru"))
        XCTAssertEqual(profiles[1].rules[0].ips, ["geoip:ru", "geoip:private"])
    }

    func testRuleOrderFiltersFirstThenProfileThenCatchAll() throws {
        var o = ConfigBuilder.Options()
        o.profile = RoutingEngine.builtInProfiles()[1]
        o.adBlock = .strict
        o.blockTorrents = true
        let root = try ConfigBuilder.buildObject(server: server(), options: o)
        let r = rules(root)
        XCTAssertEqual(r[0].str("outboundTag"), "dns-out")
        XCTAssertEqual(r[1]["ip"] as? [String], [ConfigBuilder.tunnelDns])
        XCTAssertEqual(r[2].str("outboundTag"), "block")
        XCTAssertEqual(r[3]["ip"] as? [String], ["geoip:private"])
        let ads = r[4]["domain"] as? [String] ?? []
        XCTAssertEqual(ads.first, "geosite:category-ads-all")
        XCTAssertTrue(ads.contains("full:s.youtube.com"), "strict list")
        XCTAssertEqual(r[5]["protocol"] as? [String], ["bittorrent"])
        XCTAssertEqual(r[6]["domain"] as? [String], ["geosite:category-public-tracker"])
        XCTAssertEqual(r[7].str("outboundTag"), "direct")
        XCTAssertEqual(r.last?.str("outboundTag"), "proxy")
        XCTAssertEqual(root.obj("routing")?.str("domainStrategy"), "IPIfNonMatch")
        // AdGuard answers every lookup; Volta's own list is answered by the core.
        let dns = try XCTUnwrap(root.obj("dns"))
        XCTAssertEqual(dns["servers"] as? [String], ["https://dns.adguard-dns.com/dns-query", "localhost"])
        XCTAssertEqual((dns.obj("hosts")?["domain:ads.vk.com"] as? [String]), ["0.0.0.0", "::"])
    }

    func testFamilyAddsAdultListAndOwnDns() throws {
        var o = ConfigBuilder.Options()
        o.adBlock = .family
        let root = try ConfigBuilder.buildObject(server: server(), options: o)
        XCTAssertTrue(rules(root).contains { ($0["domain"] as? [String]) == ["geosite:category-porn"] })
        XCTAssertEqual((root.obj("dns")?["servers"] as? [String])?.first, "https://family.adguard-dns.com/dns-query")
    }

    func testNoDnsModuleWithoutLocalDnsOrAdBlock() throws {
        var o = ConfigBuilder.Options()
        o.localDns = false
        let root = try ConfigBuilder.buildObject(server: server(), options: o)
        XCTAssertNil(root["dns"])
        XCTAssertFalse(rules(root).contains { $0.str("outboundTag") == "dns-out" })
    }

    func testDomesticDnsForRussianDomainsWhenAdBlockOff() throws {
        let root = try ConfigBuilder.buildObject(server: server(), options: ConfigBuilder.Options())
        let servers = try XCTUnwrap(root.obj("dns")?["servers"] as? [Any])
        let domestic = try XCTUnwrap(servers[1] as? JSONObject)
        XCTAssertEqual(domestic["expectIPs"] as? [String], ["geoip:ru"])
        XCTAssertEqual(servers.last as? String, "localhost")
    }

    func testNeverBlockListIsNeverMatched() {
        for mode in AdBlockMode.allCases {
            for host in AdBlockLists.neverBlock {
                XCTAssertFalse(AdBlockLists.matches(host, AdBlockLists.domains(for: mode)), "\(host) in \(mode)")
            }
        }
        XCTAssertTrue(AdBlockLists.matches("o0.ingest.sentry.io", AdBlockLists.standard))
    }

    func testUserRulesWinAndDuplicatesCollapse() {
        let user = RoutingRule(name: "Mine", outbound: .direct, domains: ["domain:a.com"], source: "user")
        let provider = RoutingRule(name: "P", outbound: .direct, domains: ["domain:a.com"], source: "provider")
        let builtin = RoutingRule(name: "B", outbound: .block, domains: ["domain:b.com"], source: "builtin")
        let merged = RoutingEngine.mergeRules(profileRules: [builtin, provider, user], useProviderRules: true,
                                              providerRoutingJson: "", serverJsonRouting: "")
        XCTAssertEqual(merged.map(\.name), ["Mine", "B"])
        let noProvider = RoutingEngine.mergeRules(profileRules: [provider], useProviderRules: false,
                                                  providerRoutingJson: "[{\"outbound\":\"PROXY\"}]", serverJsonRouting: "")
        XCTAssertTrue(noProvider.isEmpty)
    }

    func testHappJsonBecomesSeparateDomainAndIpRules() throws {
        let json = """
        {"Name": "Shop", "GlobalProxy": "false", "DirectSites": ["ru", "geosite:category-ru", "*.vk.com"],
         "DirectIp": ["10.0.0.0/8"], "ProxySites": ["youtube.com"], "BlockSites": "ads.example",
         "DomainStrategy": "IPIfNonMatch", "RemoteDNSType": "DoH", "RemoteDNSDomain": "dns.example",
         "Geoipurl": "https://g.example/geoip.dat", "LastUpdated": "7", "DnsHosts": {"x.example": "1.2.3.4"}}
        """
        let p = try XCTUnwrap(HappRoutingParser.parseHappJson(json))
        XCTAssertEqual(p.name, "Shop")
        XCTAssertEqual(p.rules.map(\.name), ["Локальная сеть", "Блок (из подписки)", "Через VPN (из подписки)",
                                             "Напрямую (из подписки)", "Напрямую (из подписки) · IP",
                                             "Остальное напрямую (из подписки)"])
        XCTAssertEqual(p.rules[3].domains, ["domain:ru", "geosite:category-ru", "domain:vk.com"])
        XCTAssertEqual(p.rules.last?.network, "tcp,udp")
        XCTAssertEqual(p.remoteDns, "https://dns.example/dns-query")
        XCTAssertEqual(p.geoipUrl, "https://g.example/geoip.dat")
        XCTAssertEqual(JSON.object(p.dnsHostsJson)?.str("x.example"), "1.2.3.4")
    }

    func testRoutingHeaderAndBodyLink() throws {
        let happ = #"{"Name":"H","ProxySites":["a.com"]}"#
        let b64 = Data(happ.utf8).base64EncodedString()
        XCTAssertEqual(HappRoutingParser.parseRoutingLink("happ://routing/onadd/\(b64)")?.name, "H")
        let body = "vless://id@a:443#A\nhapp://routing/add/\(b64)\n"
        let found = try XCTUnwrap(HappRoutingParser.extractFromBodyAndHeaders(body: body))
        XCTAssertFalse(found.activate)
        XCTAssertEqual(found.rules.last?.domains, ["domain:a.com"])
    }

    func testXrayRulesImportDropsDnsPlumbing() throws {
        let rules = try RoutingEngine.importRules("""
        {"routing": {"rules": [
          {"type": "field", "port": "53", "outboundTag": "dns-out"},
          {"type": "field", "domain": ["geosite:google"], "outboundTag": "proxy"},
          {"type": "field", "ip": ["1.1.1.1"], "outboundTag": "blocked"},
          {"type": "field", "protocol": ["bittorrent"], "outboundTag": "direct", "attrs": {":method": "GET"}}
        ]}}
        """)
        XCTAssertEqual(rules.map(\.outbound), [.proxy, .block, .direct])
        XCTAssertEqual(RoutingEngine.ruleToXray(rules[2], proxyBalancerTag: nil).obj("attrs")?.str(":method"), "GET")
        XCTAssertThrowsError(try RoutingEngine.importRules("{\"rules\": 5}"))
    }

    func testPresetEntryParsing() {
        XCTAssertEqual(Presets.parseEntry("https://www.YouTube.com/watch?v=1"), .domain("domain:www.youtube.com"))
        XCTAssertEqual(Presets.parseEntry("*.example.org"), .domain("domain:example.org"))
        XCTAssertEqual(Presets.parseEntry("10.0.0.0/8"), .ip("10.0.0.0/8"))
        XCTAssertEqual(Presets.parseEntry("geoip:ru"), .ip("geoip:ru"))
        XCTAssertEqual(Presets.parseEntry("user@1.2.3.4:8080"), .ip("1.2.3.4"))
        XCTAssertNil(Presets.parseEntry("localhost"))
        XCTAssertEqual(Presets.sites.count, 10)
        XCTAssertEqual(Presets.sites.first { $0.id == "ru" }?.suggested, .direct)
    }

    func testResolverKeepsSubscriptionsApart() {
        var all = RoutingEngine.builtInProfiles()
        var own = RoutingProfile(id: 77, name: "Own", sourceSubscriptionId: 1)
        own.useProviderRules = true
        all.append(own)
        let s1 = Subscription(id: 1, name: "A", url: "https://a")
        var s2 = Subscription(id: 2, name: "B", url: "https://b")
        XCTAssertEqual(RoutingResolver.resolve(s1, all)?.id, 77)
        s2.routingProfileId = 77
        XCTAssertEqual(RoutingResolver.resolve(s2, all)?.name, "Весь трафик через VPN", "not another subscription's")
        XCTAssertFalse(RoutingResolver.choicesFor(s2, all).contains { $0.id == 77 })
    }

    func testSubscriptionGeoFilesAreReferencedByExtName() throws {
        var o = ConfigBuilder.Options()
        var p = RoutingProfile(id: 5, name: "Sub", sourceSubscriptionId: 9)
        p.rules = [RoutingRule(name: "RU", outbound: .direct, domains: ["geosite:ru"], ips: ["geoip:ru", "geoip:private"])]
        o.profile = p
        o.geo = GeoNames.forSubscription(9, hasGeoip: true, hasGeosite: true)
        let r = rules(try ConfigBuilder.buildObject(server: server(), options: o))
        let rule = try XCTUnwrap(r.first { ($0["domain"] as? [String])?.first?.hasPrefix("ext:") == true })
        XCTAssertEqual(rule["domain"] as? [String], ["ext:geosite_s9.dat:ru"])
        XCTAssertEqual(rule["ip"] as? [String], ["ext:geoip_s9.dat:ru", "geoip:private"])
    }

    func testAlternatesAreWatchedByTheObservatory() throws {
        var o = ConfigBuilder.Options()
        o.metricsPort = 10900
        var other = server()
        other.id = 2
        other.address = "u.example"
        o.alternates = [other]
        let root = try ConfigBuilder.buildObject(server: server(), options: o)
        let tags = (root["outbounds"] as? [JSONObject])?.map { $0.str("tag") } ?? []
        XCTAssertTrue(tags.contains("alt-0"))
        XCTAssertEqual(root.obj("observatory")?["subjectSelector"] as? [String], ["proxy", "alt-"])
        // Nothing is routed to an alternate.
        XCTAssertFalse(rules(root).contains { $0.str("outboundTag").hasPrefix("alt-") })
    }
}

final class GeoDataTests: XCTestCase {

    /// A tiny .dat: entries (field 1) whose first field is the code.
    private func dat(_ entries: [(String, [UInt8])]) -> Data {
        var out = Data()
        for (code, body) in entries {
            var entry = Data([0x0A])
            GeoData.appendVarint(&entry, UInt64(code.utf8.count))
            entry.append(contentsOf: Array(code.utf8))
            entry.append(contentsOf: body)
            out.append(0x0A)
            GeoData.appendVarint(&out, UInt64(entry.count))
            out.append(entry)
        }
        return out
    }

    func testIndexAndTrim() {
        let file = dat([("RU", [0x12, 0x02, 0x08, 0x02]), ("CN", [0x12, 0x01, 0x00]), ("PRIVATE", [])])
        let index = GeoData.Index(file)
        XCTAssertEqual(index.codes, ["CN", "PRIVATE", "RU"])
        XCTAssertTrue(index.has("ru"))
        let small = GeoData.Index(index.trimmed(to: ["RU"]))
        XCTAssertEqual(small.codes, ["RU"])
        XCTAssertEqual(small.size("ru"), index.size("ru"))
    }

    func testPrepareKeepsFoundListsAndDropsMissingOnes() throws {
        let site = GeoData.Index(dat([("CATEGORY-RU", [0x12, 0x00]), ("CATEGORY-ADS-ALL", [])]))
        let ip = GeoData.Index(dat([("RU", [])]))
        let ruleList: [JSONObject] = [
            ["type": "field", "ip": ["geoip:private"], "outboundTag": "direct"],
            ["type": "field", "domain": ["geosite:category-ru", "domain:ru"], "ip": ["geoip:ru"], "outboundTag": "direct"],
            ["type": "field", "domain": ["geosite:nothing-here"], "outboundTag": "block"],
            ["type": "field", "network": ["tcp", "udp"], "outboundTag": "proxy"]
        ]
        let dnsServers: [Any] = [
            ["address": "x", "domains": ["geosite:category-ru"], "expectIPs": ["geoip:ru"]] as JSONObject,
            "localhost"
        ]
        let config: JSONObject = [
            "routing": ["rules": ruleList] as JSONObject,
            "dns": ["servers": dnsServers] as JSONObject
        ]
        let result = try GeoData.prepare(config, sources: GeoData.Sources(geosite: site, geoip: ip))
        let rules = result.config.obj("routing")?["rules"] as? [JSONObject] ?? []
        XCTAssertEqual(rules.count, 3, "the rule left with nothing to match is gone")
        XCTAssertEqual(rules[0]["ip"] as? [String], ConfigBuilder.privateRanges, "no PRIVATE list: spelled out")
        XCTAssertEqual(result.missing, ["geosite:nothing-here"])
        XCTAssertEqual(GeoData.Index(result.files["geosite.dat"] ?? Data()).codes, ["CATEGORY-RU"])
        XCTAssertEqual(GeoData.Index(result.files["geoip.dat"] ?? Data()).codes, ["RU"])
    }

    func testOversizedListIsRefusedPlainly() {
        let big = GeoData.Index(dat([("US", [UInt8](repeating: 0, count: GeoData.maxListBytes + 10))]))
        let ruleList: [JSONObject] = [["type": "field", "ip": ["geoip:us"], "outboundTag": "direct"]]
        let config: JSONObject = ["routing": ["rules": ruleList] as JSONObject]
        XCTAssertThrowsError(try GeoData.prepare(config, sources: GeoData.Sources(geosite: nil, geoip: big)))
    }
}

final class BackupTests: XCTestCase {

    /// Written by Android's BackupCodec (Gson, pretty-printed), trimmed to what matters.
    static let androidSample = """
    {
      "format": "volta-backup",
      "version": 1,
      "createdAt": 1793664000000,
      "app": "1.1.140",
      "subscriptions": [
        {
          "name": "Мой VPN",
          "nameLocked": true,
          "userAgent": "",
          "updateIntervalHours": 12,
          "isEnabled": true,
          "sortOrder": 0,
          "hwidEnabled": false,
          "colorHex": "#4A8FC4",
          "serverTypes": "lte:1,2",
          "url": "https://sub.example/abc",
          "routing": "Мои правила"
        },
        {
          "name": "Скрытая",
          "nameLocked": false,
          "updateIntervalHours": 6,
          "isEnabled": true,
          "sortOrder": 1,
          "hwidEnabled": true,
          "colorHex": "#C4784A",
          "serverTypes": "",
          "hiddenUrl": "AQEEiz2QtC8zTkBrHcMezKfX1oeoqreqVx_lVRq7__VKm_48YOyj5zmGifePJKPBgSPZzU2p2w62uPK_H1S0_UAsSvN4pDYwZ042R2ZhFSPOi5WeYyPiViaNyottS2_rkzn6nEWxKMDMRz-2tickeHHtIUqaHy3l-82qjdA"
        }
      ],
      "servers": [
        {
          "remark": "Дом",
          "protocol": "VLESS",
          "address": "home.example",
          "port": 443,
          "uuid": "11111111-1111-1111-1111-111111111111",
          "flow": "xtls-rprx-vision",
          "encryption": "none",
          "network": "tcp",
          "security": "reality",
          "sni": "www.example.com",
          "fp": "chrome",
          "pbk": "PUB",
          "sid": "ab",
          "spx": "",
          "alpn": "",
          "path": "",
          "host": "",
          "serviceName": "",
          "headerType": "",
          "rawLink": "vless://...",
          "rawJson": "",
          "extraJson": "{\\"mode\\":\\"auto\\"}",
          "isFavorite": true,
          "role": "BYPASS_WHITELIST",
          "roleManual": true,
          "sortOrder": 3,
          "tags": "",
          "roleFromProvider": false
        }
      ],
      "routing": [
        {
          "name": "Мои правила",
          "description": "Свой набор правил",
          "isBuiltIn": false,
          "useProviderRules": false,
          "rulesJson": "[{\\"id\\":\\"r1\\",\\"name\\":\\"YouTube\\",\\"enabled\\":true,\\"outbound\\":\\"PROXY\\",\\"domains\\":[\\"domain:youtube.com\\"],\\"ips\\":[],\\"ports\\":\\"\\",\\"sourcePorts\\":\\"\\",\\"network\\":\\"\\",\\"protocol\\":[],\\"inboundTags\\":[],\\"users\\":[],\\"attributes\\":\\"\\",\\"source\\":\\"user\\",\\"sortOrder\\":0}]",
          "domainStrategy": "IPIfNonMatch",
          "remoteDns": "https://1.1.1.1/dns-query",
          "domesticDns": "https://dns.google/dns-query",
          "dnsHostsJson": ""
        }
      ],
      "activeRouting": "Мои правила",
      "settings": {
        "auto_failover": false,
        "local_dns": true,
        "block_torrents": true,
        "design_theme": "OCEAN",
        "ad_block": "STRICT",
        "connect_mode": "AUTO_HOME",
        "mtu": 1380,
        "per_app_packages": ["com.whatsapp", "org.telegram.messenger"]
      }
    }
    """

    func testAndroidFileOpens() throws {
        let c = try BackupCodec.decode(Self.androidSample)
        XCTAssertEqual(c.createdAt, 1793664000000)
        XCTAssertEqual(c.subscriptions.count, 2)
        XCTAssertEqual(c.subscriptions[0].subscription.url, "https://sub.example/abc")
        XCTAssertEqual(c.subscriptions[0].routingName, "Мои правила")
        XCTAssertFalse(c.subscriptions[0].subscription.hwidEnabled)
        XCTAssertEqual(c.subscriptions[0].subscription.serverTypes, "lte:1,2")
        XCTAssertEqual(c.subscriptions[1].subscription.url, "https://sub.example/py?token=7")
        XCTAssertTrue(c.subscriptions[1].subscription.urlHidden)
        let s = try XCTUnwrap(c.servers.first)
        XCTAssertEqual(s.proto, .vless)
        XCTAssertEqual(s.role, .bypass)
        XCTAssertEqual(s.extras["mode"], "auto")
        XCTAssertTrue(s.isFavorite)
        XCTAssertEqual(c.routing.first?.rules.first?.domains, ["domain:youtube.com"])
        XCTAssertEqual(c.activeRouting, "Мои правила")

        var settings = AppSettings()
        let applied = settings.restore(c.settings)
        XCTAssertEqual(applied, 8)
        XCTAssertEqual(settings.theme, .ocean)
        XCTAssertEqual(settings.adBlock, .strict)
        XCTAssertEqual(settings.mode, .autoHome)
        XCTAssertEqual(settings.mtu, 1380)
        XCTAssertTrue(settings.blockTorrents)
        XCTAssertEqual(settings.foreign["per_app_packages"], .list(["com.whatsapp", "org.telegram.messenger"]))
    }

    func testRoundTripKeepsEverythingAndTheAndroidShape() throws {
        let c = try BackupCodec.decode(Self.androidSample)
        var settings = AppSettings()
        _ = settings.restore(c.settings)
        var subs = c.subscriptions.map(\.subscription)
        for i in subs.indices { subs[i].id = Int64(i + 1) }
        let text = try BackupCodec.encode(BackupCodec.Snapshot(
            subscriptions: subs, subscriptionRouting: [1: "Мои правила"], servers: c.servers, routing: c.routing,
            activeRouting: "Мои правила", settings: settings.backupValues(), appVersion: "1.1.200", createdAt: 42
        ))
        let root = try XCTUnwrap(JSON.object(text))
        let subsJson = try XCTUnwrap(root["subscriptions"] as? [JSONObject])
        XCTAssertNil(subsJson[1]["url"], "a hidden address never goes out in plain text")
        XCTAssertNotNil(subsJson[1]["hiddenUrl"])
        XCTAssertEqual((root["servers"] as? [JSONObject])?.first?.str("protocol"), "VLESS")
        XCTAssertNil((root["servers"] as? [JSONObject])?.first?["latencyMs"])
        XCTAssertTrue(((root["routing"] as? [JSONObject])?.first?["rulesJson"]) is String)

        let back = try BackupCodec.decode(text)
        XCTAssertEqual(back.subscriptions.map { $0.subscription.url }, c.subscriptions.map { $0.subscription.url })
        XCTAssertEqual(back.servers.map(BackupCodec.sameServerKey), c.servers.map(BackupCodec.sameServerKey))
        XCTAssertEqual(back.routing.first?.rules.map(\.domains), c.routing.first?.rules.map(\.domains))
        var again = AppSettings()
        _ = again.restore(back.settings)
        XCTAssertEqual(again.adBlock, .strict)
        XCTAssertEqual(again.foreign["auto_failover"], .bool(false), "Android-only settings travel back")
    }

    func testBrokenEntriesAreSkippedNotFatal() throws {
        let text = """
        {"format": "volta-backup", "version": 1,
         "subscriptions": [{"name": "Без адреса"}, {"name": "Без защиты", "url": "http://plain.example/sub"},
           {"name": null, "url": "https://ok.example/sub", "sortOrder": null, "futureField": 1},
           {"name": "Не открыть", "hiddenUrl": "garbage"}, "not an object"],
         "servers": [{"protocol": "TELEPORT", "address": "a", "port": 1},
           {"protocol": "TROJAN", "address": "b.example", "port": 443, "role": "FUTURE_ROLE", "remark": "T"}],
         "routing": [{"description": "без имени"}],
         "settings": {"mtu": 1400, "nested": {"x": 1}}}
        """
        let back = try BackupCodec.decode(text)
        XCTAssertEqual(back.subscriptions.map { $0.subscription.url }, ["https://ok.example/sub"])
        XCTAssertEqual(back.subscriptions.first?.subscription.name, "Подписка")
        XCTAssertEqual(back.servers.first?.role, .normal)
        XCTAssertEqual(back.servers.first?.encryption, "none")
        XCTAssertTrue(back.routing.isEmpty)
        XCTAssertEqual(back.settings, ["mtu": .int(1400)])
        XCTAssertEqual(back.skipped, 6)
    }

    func testForeignFilesAreRefusedWithAReason() {
        func reason(_ text: String) -> String? {
            do { _ = try BackupCodec.decode(text); return nil } catch { return (error as? BackupCodec.BackupError)?.key }
        }
        XCTAssertEqual(reason("not json"), "settings_backup_error_damaged")
        XCTAssertEqual(reason("{\"subscriptions\": []}"), "settings_backup_error_not_volta")
        XCTAssertEqual(reason("{\"format\": \"volta-backup\", \"version\": 99}"), "settings_backup_error_newer")
        XCTAssertEqual(try BackupCodec.decode("\u{FEFF}{\"format\": \"volta-backup\", \"version\": 1}").isEmpty, true)
    }

    func testUntouchedBuiltInIsNotWorthKeeping() {
        let stock = RoutingEngine.builtInProfiles()
        XCTAssertFalse(BackupCodec.worthKeeping(stock[0], defaults: RoutingEngine.builtInProfiles()))
        var edited = stock[0]
        edited.rules.append(RoutingRule(name: "x", domains: ["domain:x.com"]))
        XCTAssertTrue(BackupCodec.worthKeeping(edited, defaults: RoutingEngine.builtInProfiles()))
    }
}

final class UpdatesTrafficGuardTests: XCTestCase {

    func testManifestAndNewestChannel() throws {
        let json = #"{"versionCode": 210, "versionName": "1.1.110", "tag": "apk-main", "ipa": "volta-abc-ios-unsigned.ipa"}"#
        let beta = try XCTUnwrap(Updates.parse(Data(json.utf8)))
        let stable = Updates.Manifest(versionCode: 205, versionName: "1.2.0", tag: "v1.2.0")
        XCTAssertEqual(Updates.newest(stable: stable, beta: beta, current: 200)?.versionCode, 210)
        XCTAssertEqual(Updates.newest(stable: stable, beta: nil, current: 200)?.tag, "v1.2.0")
        XCTAssertNil(Updates.newest(stable: stable, beta: beta, current: 210))
        XCTAssertEqual(beta.pageURL.absoluteString, "https://github.com/colossalblend/volta/releases/tag/apk-main")
        XCTAssertEqual(beta.ipaURL?.absoluteString,
                       "https://github.com/colossalblend/volta/releases/download/apk-main/volta-abc-ios-unsigned.ipa")
    }

    func testTrafficMonthsAndDays() {
        // 2026-11-04 is day 20761.
        let today = 20761
        var t = TrafficDays()
        t.add(day: today, up: 10, down: 90)
        t.add(day: today - 4, up: 0, down: 50)       // 31 October: last month
        t.add(day: today - 40, up: 0, down: 1_000)   // September: neither
        t.add(day: today + 3, up: 0, down: 7)        // clock set back: no total
        let s = TrafficTally.summarize(t, today: today)
        XCTAssertEqual(s.today, 100)
        XCTAssertEqual(s.thisMonth, 100)
        XCTAssertEqual(s.lastMonth, 50)
        XCTAssertEqual(s.thisMonthOf, 11)
        XCTAssertEqual(s.lastMonthOf, 10)
        XCTAssertEqual(s.recent.count, 14)
        XCTAssertEqual(s.recent.last, 100)
        t.prune(today: today)
        XCTAssertNil(t.days[today - 40 - 40])
    }

    func testInternetCheck() {
        XCTAssertEqual(InternetCheck.decide(.init(satisfied: true, hasNonCellular: true, hasCellular: false)), .ok)
        XCTAssertEqual(InternetCheck.decide(.init(satisfied: false, hasNonCellular: false, hasCellular: false)), .noNetwork)
        XCTAssertTrue(InternetCheck.Status.noNetwork.blocksConnect)
    }

    private func plan() -> GuardPlan {
        let home = [GuardCandidate(id: 1, name: "H1", address: "a", port: 1, config: "{}", usualMs: 100, alternates: ["alt-0": 2]),
                    GuardCandidate(id: 2, name: "H2", address: "b", port: 1, config: "{}", usualMs: 120, alternates: ["alt-0": 1])]
        let lte = [GuardCandidate(id: 3, name: "L1", address: "c", port: 1, config: "{}")]
        return GuardPlan(mode: .autoHomeLte, group: .normal, home: home, lte: lte)
    }

    private func input(_ obs: [String: GuardPolicy.Observation], wanted: ServerRole? = nil, down: Int64 = 0,
                       internet: Bool = true, full: Bool = false) -> GuardPolicy.Input {
        GuardPolicy.Input(plan: plan(), liveId: 1, wantedGroup: wanted, observations: obs,
                          downloadedSinceLastCheck: down, internetWorks: internet, fullDue: full, optionalAllowed: true)
    }

    func testGuardMovesToTheGroupTheNetworkWants() {
        XCTAssertEqual(GuardPolicy.decide(input([:], wanted: .bypass)), .switchTo(id: 3, reason: .network, optional: false))
    }

    func testDeadServerIsReplacedOnlyWhenNothingProvesItAlive() {
        let dead = ["proxy": GuardPolicy.Observation(alive: false, delayMs: 0),
                    "alt-0": GuardPolicy.Observation(alive: true, delayMs: 200)]
        XCTAssertEqual(GuardPolicy.decide(input(dead)), .switchTo(id: 2, reason: .dead, optional: false))
        XCTAssertEqual(GuardPolicy.decide(input(dead, down: 100_000)), .stay, "data went through it")
        XCTAssertEqual(GuardPolicy.decide(input(dead, internet: false)), .stay, "no internet is not its fault")
    }

    func testSlowServerMovesOnlyToAClearlyFasterOne() {
        let slow = ["proxy": GuardPolicy.Observation(alive: true, delayMs: 950),
                    "alt-0": GuardPolicy.Observation(alive: true, delayMs: 180)]
        XCTAssertEqual(GuardPolicy.decide(input(slow)), .switchTo(id: 2, reason: .slow, optional: true))
        let fine = ["proxy": GuardPolicy.Observation(alive: true, delayMs: 110),
                    "alt-0": GuardPolicy.Observation(alive: true, delayMs: 100)]
        XCTAssertEqual(GuardPolicy.decide(input(fine, full: true)), .stay, "hysteresis")
        XCTAssertTrue(GuardPolicy.isSlow(900, usualMs: 0))
        XCTAssertTrue(GuardPolicy.isSlow(400, usualMs: 100))
        XCTAssertFalse(GuardPolicy.isSlow(300, usualMs: 100))
    }

    func testSwitchLimiter() {
        var l = GuardPolicy.SwitchLimiter()
        for t in [0.0, 10, 20] {
            XCTAssertTrue(l.allow(t))
            l.record(t)
        }
        XCTAssertFalse(l.allow(30))
        XCTAssertTrue(l.allow(20 + 601))
    }
}
