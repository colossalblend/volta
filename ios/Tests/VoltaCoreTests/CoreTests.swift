import XCTest
@testable import VoltaCore

final class MarkupAndClassifierTests: XCTestCase {

    func testPrefixMarkup() {
        let p = ServerMarkup.prefixRole("[L] Moscow")
        XCTAssertEqual(p?.role, .bypass)
        XCTAssertEqual(p?.remark, "Moscow")
        XCTAssertEqual(ServerMarkup.prefixRole("(home) - Berlin")?.role, .normal)
        XCTAssertEqual(ServerMarkup.prefixRole("【A】Best")?.role, .auto)
        XCTAssertNil(ServerMarkup.prefixRole("Berlin [L]"))
    }

    func testServerTypes() {
        let map = ServerMarkup.parseServerTypes("lte:1,2,5-7;home:3|auto:9")
        XCTAssertEqual(map.roles[1], .bypass)
        XCTAssertEqual(map.roles[6], .bypass)
        XCTAssertEqual(map.roles[3], .normal)
        XCTAssertEqual(map.roles[9], .auto)
        XCTAssertNil(map.roles[4])
        XCTAssertEqual(ServerMarkup.normalize("home:3;lte:2+1"), "lte:1,2;home:3")
        XCTAssertEqual(ServerMarkup.normalize("nonsense"), "")
    }

    func testNameCheck() {
        XCTAssertEqual(ServerClassifier.classify("🇷🇺 Обход белых списков"), .bypass)
        XCTAssertEqual(ServerClassifier.classify("Москва LTE"), .bypass)
        XCTAssertEqual(ServerClassifier.classify("Автовыбор"), .auto)
        XCTAssertEqual(ServerClassifier.classify("Балансер LTE"), .auto)
        XCTAssertEqual(ServerClassifier.classify("🇩🇪 Германия"), .normal)
        // "lte" inside a word is not a marker.
        XCTAssertEqual(ServerClassifier.classify("Deltexa"), .normal)
    }

    func testMergeKeepsUserStateAndMeasurements() {
        var old = Server(subscriptionId: 1, remark: "DE", address: "de.example", port: 443, uuid: "u")
        old.id = 42
        old.isFavorite = true
        old.latencyMs = 80
        old.role = .bypass
        old.roleManual = true
        let incoming = [
            Server(remark: "DE renamed", address: "de.example", port: 443, uuid: "u"),
            Server(remark: "NL", address: "nl.example", port: 443, uuid: "u")
        ]
        let merged = ServerClassifier.mergeProviderUpdate(previous: [old], incoming: incoming, subscriptionId: 1)
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged[0].id, 42)
        XCTAssertTrue(merged[0].isFavorite)
        XCTAssertEqual(merged[0].latencyMs, 80)
        XCTAssertEqual(merged[0].role, .bypass)
        XCTAssertEqual(merged[0].remark, "DE renamed")
        XCTAssertNotEqual(merged[1].id, 0)
        XCTAssertEqual(merged[1].subscriptionId, 1)
    }

    func testServerTypePositionsApply() {
        let incoming = (1...3).map { Server(remark: "S\($0)", address: "s\($0).example", port: 443, uuid: "u") }
        let merged = ServerClassifier.mergeProviderUpdate(previous: [], incoming: incoming, subscriptionId: 5,
                                                          serverTypes: "lte:2")
        XCTAssertEqual(merged.map { $0.role }, [.normal, .bypass, .normal])
        XCTAssertTrue(merged[1].roleFromProvider)
    }
}

final class CountriesTests: XCTestCase {

    func testFlagAndNames() {
        XCTAssertEqual(Countries.codeOf("🇩🇪 Server"), "DE")
        XCTAssertEqual(Countries.codeOf("Германия 1"), "DE")
        XCTAssertEqual(Countries.codeOf("в Финляндии"), "FI")
        XCTAssertEqual(Countries.codeOf("Frankfurt #2"), "DE")
        XCTAssertEqual(Countries.codeOf("Netherlands"), "NL")
        XCTAssertEqual(Countries.codeOf("NL-03"), "NL")
        XCTAssertEqual(Countries.codeOf("Южная Корея"), "KR")
        XCTAssertNil(Countries.codeOf("Fast server"))
        XCTAssertEqual(Countries.flagOf("de"), "🇩🇪")
    }

    func testWithoutLeadingFlag() {
        XCTAssertEqual(Countries.withoutLeadingFlag("🇩🇪 Германия"), "Германия")
        XCTAssertEqual(Countries.withoutLeadingFlag("🇳🇱 | Amsterdam"), "Amsterdam")
        XCTAssertEqual(Countries.withoutLeadingFlag("Plain"), "Plain")
    }
}

final class ConfigBuilderTests: XCTestCase {

    private func config(_ link: String, _ options: ConfigBuilder.Options = .init()) throws -> JSONObject {
        let server = try XCTUnwrap(ConfigParser.parseShareLink(link))
        let text = try ConfigBuilder.build(server: server, options: options)
        return try XCTUnwrap(JSON.object(text))
    }

    private func proxy(_ root: JSONObject) throws -> JSONObject {
        let outbounds = try XCTUnwrap(root.arr("outbounds") as? [JSONObject])
        return try XCTUnwrap(outbounds.first { $0.str("tag") == "proxy" })
    }

    func testRealityVless() throws {
        var options = ConfigBuilder.Options()
        options.socksUser = "u"
        options.socksPass = "p"
        options.socksPort = 23456
        let root = try config(
            "vless://11111111-1111-1111-1111-111111111111@node.example:443?type=tcp&security=reality" +
                "&pbk=PUB&sid=ab&sni=www.example.com&fp=chrome&flow=xtls-rprx-vision#Node",
            options
        )
        let ob = try proxy(root)
        XCTAssertEqual(ob.str("protocol"), "vless")
        let stream = try XCTUnwrap(ob.obj("streamSettings"))
        XCTAssertEqual(stream.str("security"), "reality")
        let reality = try XCTUnwrap(stream.obj("realitySettings"))
        XCTAssertEqual(reality.str("publicKey"), "PUB")
        XCTAssertEqual(reality.str("serverName"), "www.example.com")
        let user = try XCTUnwrap(((ob.obj("settings")?.arr("vnext")?.first as? JSONObject)?.arr("users")?.first) as? JSONObject)
        XCTAssertEqual(user.str("flow"), "xtls-rprx-vision")

        let inbound = try XCTUnwrap((root.arr("inbounds") as? [JSONObject])?.first)
        XCTAssertEqual(inbound.str("protocol"), "socks")
        XCTAssertEqual(inbound.int("port", 0), 23456)
        XCTAssertEqual(inbound.obj("settings")?.str("auth"), "password")
        // Local networks direct, as on Android (the list is written trimmed before connecting).
        let rules = try XCTUnwrap(root.obj("routing")?["rules"] as? [JSONObject])
        XCTAssertTrue(rules.contains { ($0["ip"] as? [String]) == ["geoip:private"] })
    }

    func testFlowDroppedOffRawTcp() throws {
        let ob = try proxy(config("vless://id@n.example:443?type=ws&security=tls&flow=xtls-rprx-vision&path=%2Fws#WS"))
        let user = ((ob.obj("settings")?.arr("vnext")?.first as? JSONObject)?.arr("users")?.first) as? JSONObject
        XCTAssertEqual(user?.str("flow"), "")
        XCTAssertEqual(ob.obj("streamSettings")?.obj("wsSettings")?.str("path"), "/ws")
    }

    func testHysteria2() throws {
        let ob = try proxy(config("hysteria2://pw@hy.example:443?sni=hy.example&obfs=salamander&obfs-password=x#HY"))
        XCTAssertEqual(ob.str("protocol"), "hysteria")
        let stream = try XCTUnwrap(ob.obj("streamSettings"))
        XCTAssertEqual(stream.str("network"), "hysteria")
        XCTAssertEqual(stream.obj("hysteriaSettings")?.str("auth"), "pw")
        XCTAssertNotNil(stream.obj("finalmask"))
    }

    func testTunEngineInbound() throws {
        var options = ConfigBuilder.Options()
        options.engine = .xray
        options.metricsPort = 10813
        let root = try config("trojan://pw@t.example:443#T", options)
        let inbound = try XCTUnwrap((root.arr("inbounds") as? [JSONObject])?.first)
        XCTAssertEqual(inbound.str("protocol"), "tun")
        XCTAssertEqual(root.obj("metrics")?.str("listen"), "127.0.0.1:10813")
    }

    func testRussiaDirectAndDns() throws {
        var options = ConfigBuilder.Options()
        options.profile = RoutingEngine.builtInProfiles()[1]
        let root = try config("trojan://pw@t.example:443#T", options)
        let rules = try XCTUnwrap(root.obj("routing")?.arr("rules") as? [JSONObject])
        XCTAssertEqual(rules.first?.str("outboundTag"), "dns-out")
        XCTAssertTrue(rules.contains { ($0["domain"] as? [String])?.contains("geosite:category-ru") == true })
        XCTAssertEqual(rules.last?.str("outboundTag"), "proxy")
        XCTAssertNotNil(root.obj("dns"))
    }

    func testUnsupportedThrows() {
        var server = Server(remark: "AWG", proto: .amneziawg, address: "a.example", port: 1)
        XCTAssertFalse(ConfigBuilder.canDial(server))
        server.proto = .vless
        XCTAssertFalse(ConfigBuilder.canDial(server), "VLESS without a key")
        server.uuid = "id"
        XCTAssertTrue(ConfigBuilder.canDial(server))
    }

    func testProbeConfigHasOnlyOutbounds() throws {
        let server = try XCTUnwrap(ConfigParser.parseShareLink("vless://id@p.example:443?security=tls#P"))
        let probe = try XCTUnwrap(JSON.object(try ConfigBuilder.buildProbe(server: server)))
        XCTAssertNil(probe["inbounds"])
        XCTAssertEqual((probe.arr("outbounds") as? [JSONObject])?.first?.str("tag"), "proxy")
    }

    func testJsonOutboundKeptVerbatim() throws {
        let body = """
        {"remarks": "Raw", "outbounds": [
          {"tag": "proxy", "protocol": "vless",
           "settings": {"vnext": [{"address": "raw.example", "port": 443, "users": [{"id": "u", "encryption": "none"}]}]},
           "streamSettings": {"network": "xhttp", "security": "tls", "tlsSettings": {"serverName": "raw.example", "allowInsecure": true},
             "xhttpSettings": {"path": "/x", "mode": "packet-up"}}}]}
        """
        let server = try XCTUnwrap(ConfigParser.parseSubscriptionBody(body).servers.first)
        let ob = try ConfigBuilder.outbound(for: server, tag: "proxy")
        XCTAssertEqual(ob.obj("streamSettings")?.obj("xhttpSettings")?.str("mode"), "packet-up")
        XCTAssertNil(ob.obj("streamSettings")?.obj("tlsSettings")?["allowInsecure"])
    }
}

final class LinksTests: XCTestCase {

    func testPathImport() {
        let action = VoltaLinks.parse("volta://import/https://sub.example/abc&server-type=lte:1,2;home:3#My VPN")
        XCTAssertEqual(action, .addSubscription(url: "https://sub.example/abc", name: "My VPN", visible: nil,
                                                serverTypes: "lte:1,2;home:3"))
    }

    func testPathImportKeepsProviderQuery() {
        let action = VoltaLinks.parse("volta://add/https://sub.example/x?token=1&visible_subs=false")
        XCTAssertEqual(action, .addSubscription(url: "https://sub.example/x?token=1", name: "", visible: false,
                                                serverTypes: ""))
    }

    func testQueryImport() {
        let action = VoltaLinks.parse("volta://import?url=https%3A%2F%2Fsub.example%2Fa&name=Test")
        XCTAssertEqual(action, .addSubscription(url: "https://sub.example/a", name: "Test", visible: nil, serverTypes: ""))
    }

    func testControl() {
        XCTAssertEqual(VoltaLinks.parse("volta://connect?mode=lte"), .connect(.autoLte))
        XCTAssertEqual(VoltaLinks.parse("volta://disconnect"), .disconnect)
        XCTAssertEqual(VoltaLinks.parse("volta://toggle"), .toggle)
    }

    func testEncryptedLink() {
        let payload = "AQEESgh6I3xlELqwQR1VB-xntgM2B20j73X99V3aB3uf-Iyn1goLNcPMrZbI5c_YQM4nwaTf1-tRKrq82MmYeVkbolpdDH9KvUGQWQVPaALL2epTjR7IgJgYu46lqELRLQzEb582Syk08o90JqGYyvM8tPyIK066E6wTbRmvnuPNOg"
        XCTAssertEqual(try? SubCrypto.decrypt(payload), "https://sub.example.com/abc?token=1")
        guard case let .addEncrypted(p, _, _) = VoltaLinks.parse("volta://crypt/\(payload)") else {
            return XCTFail("not an encrypted link")
        }
        let opened = VoltaLinks.openEncrypted(payload: p, name: "Outer", serverTypes: "")
        XCTAssertEqual(opened, .addSubscription(url: "https://sub.example.com/abc?token=1", name: "Outer",
                                                visible: false, serverTypes: ""))
        XCTAssertThrowsError(try SubCrypto.decrypt("AQEbroken"))
    }

    func testImportKind() {
        XCTAssertEqual(ImportKind.detect(""), .empty)
        XCTAssertEqual(ImportKind.detect("https://sub.example/abc"), .subscription(hidden: false))
        XCTAssertEqual(ImportKind.detect("vless://id@a.example:443#A"), .server("VLESS"))
        XCTAssertEqual(ImportKind.detect("vless://id@a:1#A\nss://YWVzLTI1Ni1nY206cGFzcw@b:2#B"), .servers(2))
        XCTAssertEqual(ImportKind.detect("volta://crypt/AAAA"), .encrypted)
        XCTAssertEqual(ImportKind.detect("hello"), .unknown)
        XCTAssertEqual(ImportKind.detect("vpn://abc"), .amnezia)
    }
}

final class BalancerTests: XCTestCase {

    func testQuality() {
        let q = Balancer.quality([100, 120, -1, 110])
        XCTAssertEqual(q.medianMs, 110)
        XCTAssertEqual(q.jitterMs, 20)
        XCTAssertEqual(q.loss, 0.25, accuracy: 0.001)
        XCTAssertEqual(Balancer.quality([-1, -1]).medianMs, -1)
    }

    func testHysteresis() {
        var a = Server(remark: "A", address: "a", port: 1, uuid: "u"); a.id = 1
        var b = Server(remark: "B", address: "b", port: 1, uuid: "u"); b.id = 2
        // B is only a little faster: the holder A stays.
        var pick = Balancer.choose(pool: [a, b], samples: [1: [200], 2: [185]], holder: a)
        XCTAssertEqual(pick.selected?.id, 1)
        // B is clearly faster: it wins.
        pick = Balancer.choose(pool: [a, b], samples: [1: [300], 2: [120]], holder: a)
        XCTAssertEqual(pick.selected?.id, 2)
        // A no longer answers.
        pick = Balancer.choose(pool: [a, b], samples: [1: [-1], 2: [400]], holder: a)
        XCTAssertEqual(pick.selected?.id, 2)
    }

    func testPools() {
        var home = Server(remark: "Home", address: "h", port: 1); home.role = .normal
        var lte = Server(remark: "LTE", address: "l", port: 1); lte.role = .bypass
        var auto = Server(remark: "Auto", address: "x", port: 1); auto.role = .auto
        let all = [home, lte, auto]
        XCTAssertEqual(Balancer.pool(all, .all).count, 2)
        XCTAssertEqual(Balancer.filterByRegime(all, .whitelistOnly).map { $0.remark }, ["LTE"])
        XCTAssertEqual(Balancer.filterByRegime(all, .open).map { $0.remark }, ["Home"])
    }
}
