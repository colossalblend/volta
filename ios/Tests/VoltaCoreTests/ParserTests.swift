import XCTest
@testable import VoltaCore

/// The Android ConfigParserTest cases, plus the shapes iOS adds (WireGuard .conf).
final class ParserTests: XCTestCase {

    private func b64(_ s: String, url: Bool = false) -> String {
        var out = Data(s.utf8).base64EncodedString().trimmingTrailing("=")
        if url { out = out.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_") }
        return out
    }

    func testBase64SubscriptionWithoutLineBreaks() {
        let links = [
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#One",
            "vless://22222222-2222-2222-2222-222222222222@example.org:443?security=tls#Two"
        ].joined(separator: "\n")
        let result = ConfigParser.parseSubscriptionBody(b64(links), subscriptionId: 7)
        XCTAssertEqual(result.servers.count, 2)
        XCTAssertTrue(result.servers.allSatisfy { $0.subscriptionId == 7 })
        XCTAssertEqual(result.servers.map { $0.remark }, ["One", "Two"])
    }

    func testLinksFromHtmlAndOneLine() {
        let body = "<p>vless://id@one.example:443#One</p> vmess://invalid vless://id@two.example:443#Two"
        let result = ConfigParser.parseSubscriptionBody(body)
        XCTAssertEqual(result.servers.map { $0.address }, ["one.example", "two.example"])
    }

    func testProviderBodyWithMetadata() {
        let links = [
            "vless://11111111-1111-1111-1111-111111111111@nl.example:443?security=reality&sni=google.com&pbk=KEY#NL",
            "vless://22222222-2222-2222-2222-222222222222@de.example:443?security=tls#DE",
            "ss://YWVzLTI1Ni1nY206cGFzcw@fi.example:8388#FI"
        ].joined(separator: "\n")
        let body = """
        #profile-title: base64:TmVidWxhR3VhcmQ=
        #subscription-userinfo: upload=0; download=10; total=0; expire=1792690181
        #support-url: https://t.me/example
        #profile-web-page-url: https://sub.example.com/token
        \(b64(links))
        """
        let result = ConfigParser.parseSubscriptionBody(body, subscriptionId: 3)
        XCTAssertEqual(result.title, "NebulaGuard")
        XCTAssertEqual(result.servers.map { $0.address }, ["nl.example", "de.example", "fi.example"])
        XCTAssertEqual(result.expireAt, 1792690181)
        XCTAssertEqual(result.trafficUsed, 10)
        XCTAssertEqual(result.supportUrl, "https://t.me/example")
    }

    func testPlainSubscriptionWithNamesWithSpaces() {
        let body = """
        vless://id@a.example:443?security=tls#🇩🇪 Германия Франкфурт
        trojan://pass@b.example:443?sni=b.example#Нидерланды 2
        """
        let result = ConfigParser.parseSubscriptionBody(body)
        XCTAssertEqual(result.servers.map { $0.remark }, ["🇩🇪 Германия Франкфурт", "Нидерланды 2"])
        XCTAssertEqual(result.servers[1].proto, .trojan)
        XCTAssertEqual(result.servers[1].security, "tls")
    }

    func testXrayJsonArrayOfConfigs() {
        let body = """
        [
          {"remarks": "One", "outbounds": [
            {"tag": "proxy", "protocol": "vless",
             "settings": {"vnext": [{"address": "one.example", "port": 443,
               "users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]}]},
             "streamSettings": {"network": "tcp", "security": "tls"}},
            {"tag": "direct", "protocol": "freedom"}]},
          {"outbounds": [
            {"tag": "proxy", "protocol": "vless",
             "settings": {"vnext": [{"address": "two.example", "port": 8443,
               "users": [{"id": "22222222-2222-2222-2222-222222222222", "encryption": "none"}]}]}}]}
        ]
        """
        let result = ConfigParser.parseSubscriptionBody(body)
        XCTAssertEqual(result.servers.map { $0.address }, ["one.example", "two.example"])
        XCTAssertEqual(result.servers.map { $0.port }, [443, 8443])
        XCTAssertEqual(result.servers[0].remark, "One")
        XCTAssertFalse(result.servers[0].rawJson.isEmpty)
    }

    func testGroupedProviderJson() {
        let body = """
        {"title": "My VPN",
         "wifi": ["vless://id@home.example:443?security=tls#Germany"],
         "lte": [{"link": "vless://id@lte.example:443?security=tls#x", "name": "Moscow LTE"}],
         "balancers": ["vless://id@auto.example:443#Auto"]}
        """
        let result = ConfigParser.parseSubscriptionBody(body)
        XCTAssertEqual(result.title, "My VPN")
        XCTAssertEqual(result.servers.count, 3)
        let byAddress = Dictionary(uniqueKeysWithValues: result.servers.map { ($0.address, $0) })
        XCTAssertEqual(byAddress["home.example"]?.role, .normal)
        XCTAssertEqual(byAddress["lte.example"]?.role, .bypass)
        XCTAssertEqual(byAddress["lte.example"]?.remark, "Moscow LTE")
        XCTAssertEqual(byAddress["auto.example"]?.role, .auto)
        XCTAssertTrue(result.servers.allSatisfy { $0.roleFromProvider })
        // In the order the provider wrote the groups.
        XCTAssertEqual(result.servers.map { $0.address }, ["home.example", "lte.example", "auto.example"])
    }

    func testShadowsocksFullyEncoded() {
        let value = b64("aes-256-gcm:password@ss.example:8388", url: true)
        let s = ConfigParser.parseShareLink("ss://\(value)#Fast")
        XCTAssertEqual(s?.address, "ss.example")
        XCTAssertEqual(s?.port, 8388)
        XCTAssertEqual(s?.encryption, "aes-256-gcm")
        XCTAssertEqual(s?.uuid, "password")
        XCTAssertEqual(s?.remark, "Fast")
    }

    func testShadowsocks2022KeyIntact() {
        let s = ConfigParser.parseShareLink(
            "ss://MjAyMi1ibGFrZTMtYWVzLTI1Ni1nY206MkIzUlRSSlN2SzZQTjhkPQ==@ss.example:443#SS2022"
        )
        XCTAssertEqual(s?.encryption, "2022-blake3-aes-256-gcm")
        XCTAssertEqual(s?.uuid, "2B3RTRJSvK6PN8d=")
    }

    func testHysteria2WithObfsAndBandwidth() throws {
        let link = "hysteria2://s3cret@hy.example:8443/?sni=cdn.example&obfs=salamander" +
            "&obfs-password=abc&upmbps=50&downmbps=200&insecure=1#HY"
        let s = try XCTUnwrap(ConfigParser.parseShareLink(link))
        XCTAssertEqual(s.proto, .hysteria2)
        XCTAssertEqual(s.address, "hy.example")
        XCTAssertEqual(s.port, 8443)
        XCTAssertEqual(s.uuid, "s3cret")
        XCTAssertEqual(s.sni, "cdn.example")
        XCTAssertEqual(s.extra("obfs"), "salamander")
        XCTAssertEqual(s.extra("obfs-password"), "abc")
        XCTAssertEqual(s.extra("upmbps"), "50")
    }

    func testHysteria2PortHopping() throws {
        let s = try XCTUnwrap(ConfigParser.parseShareLink("hy2://pw@hop.example:20000-30000?sni=a.example#Hop"))
        XCTAssertEqual(s.port, 20000)
        XCTAssertEqual(s.extra("mport"), "20000-30000")
    }

    func testSocksAndWireGuard() throws {
        let socks = try XCTUnwrap(ConfigParser.parseShareLink("socks://dXNlcjpwYXNz@sox.example:1080#SOCKS"))
        XCTAssertEqual(socks.proto, .socks)
        XCTAssertEqual(socks.port, 1080)
        XCTAssertEqual(socks.extra("user"), "user")
        XCTAssertEqual(socks.uuid, "pass")

        let wg = try XCTUnwrap(ConfigParser.parseShareLink(
            "wireguard://PRIVKEY%3D@wg.example:51820?publickey=PUBKEY%3D&address=10.7.0.2/32&mtu=1420&reserved=1,2,3#WG"
        ))
        XCTAssertEqual(wg.proto, .wireguard)
        XCTAssertEqual(wg.address, "wg.example")
        XCTAssertEqual(wg.port, 51820)
        XCTAssertEqual(wg.uuid, "PRIVKEY=")
        XCTAssertEqual(wg.pbk, "PUBKEY=")
        XCTAssertEqual(wg.extra("address"), "10.7.0.2/32")
    }

    func testVlessModernTransport() throws {
        let s = try XCTUnwrap(ConfigParser.parseShareLink(
            "vless://11111111-1111-1111-1111-111111111111@node.example:443" +
                "?type=xhttp&mode=stream-one&path=%2Fdl&host=cdn.example&security=reality" +
                "&pbk=PUB&sid=ab&fp=chrome&flow=xtls-rprx-vision#Node"
        ))
        XCTAssertEqual(s.network, "xhttp")
        XCTAssertEqual(s.security, "reality")
        XCTAssertEqual(s.path, "/dl")
        XCTAssertEqual(s.host, "cdn.example")
        XCTAssertEqual(s.pbk, "PUB")
        XCTAssertEqual(s.sid, "ab")
        XCTAssertEqual(s.flow, "xtls-rprx-vision")
        XCTAssertEqual(s.extra("mode"), "stream-one")
    }

    func testLegacyTransportSpellings() {
        let raw = ConfigParser.parseShareLink("vless://id@a.example:443?type=raw&security=none#A")
        XCTAssertEqual(raw?.network, "tcp")
        XCTAssertEqual(raw?.security, "none")
        let split = ConfigParser.parseShareLink("vless://id@b.example:443?type=splithttp&security=1#B")
        XCTAssertEqual(split?.network, "xhttp")
        XCTAssertEqual(split?.security, "tls")
    }

    func testIpv6Literal() {
        let s = ConfigParser.parseShareLink("vless://id@[2001:db8::1]:8443?security=tls#v6")
        XCTAssertEqual(s?.address, "2001:db8::1")
        XCTAssertEqual(s?.port, 8443)
    }

    func testVmessBase64Json() throws {
        let json = #"{"v":"2","ps":"VM Test","add":"vm.example","port":"8443","id":"uuid-1","aid":"0","net":"ws","type":"none","host":"cdn.example","path":"/ws","tls":"tls","sni":"cdn.example"}"#
        let s = try XCTUnwrap(ConfigParser.parseShareLink("vmess://" + Data(json.utf8).base64EncodedString()))
        XCTAssertEqual(s.proto, .vmess)
        XCTAssertEqual(s.remark, "VM Test")
        XCTAssertEqual(s.port, 8443)
        XCTAssertEqual(s.network, "ws")
        XCTAssertEqual(s.security, "tls")
        XCTAssertEqual(s.path, "/ws")
        XCTAssertEqual(s.host, "cdn.example")
    }

    func testUnsupportedAreReported() {
        let body = """
        tuic://uuid:pass@tuic.example:443#TUIC
        ssr://abcdef
        vless://id@ok.example:443#OK
        """
        let result = ConfigParser.parseSubscriptionBody(body)
        XCTAssertEqual(result.servers.count, 1)
        XCTAssertTrue(result.skipped.contains("TUIC"))
        XCTAssertTrue(result.skipped.contains("ShadowsocksR"))
        XCTAssertFalse(ConfigParser.unsupportedProtocols("hysteria2://a@b:443").contains("Hysteria"))
    }

    func testRejectsBrokenLinks() {
        XCTAssertNil(ConfigParser.parseShareLink("vless://@:443#broken"))
        XCTAssertNil(ConfigParser.parseShareLink("nonsense://whatever"))
    }

    func testWireGuardConf() throws {
        let conf = """
        [Interface]
        PrivateKey = cHJpdmF0ZWtleQ==
        Address = 10.8.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = cHVibGlja2V5
        AllowedIPs = 0.0.0.0/0
        Endpoint = wg.example:51820
        """
        let result = ConfigParser.parseSubscriptionBody(conf)
        let s = try XCTUnwrap(result.servers.first)
        XCTAssertEqual(s.proto, .wireguard)
        XCTAssertEqual(s.address, "wg.example")
        XCTAssertEqual(s.extra("publicKey"), "cHVibGlja2V5")

        let awg = conf.replacingOccurrences(of: "DNS = 1.1.1.1", with: "Jc = 4\nJmin = 40")
        let skipped = ConfigParser.parseSubscriptionBody(awg)
        XCTAssertTrue(skipped.servers.isEmpty)
        XCTAssertEqual(skipped.skipped, ["AmneziaWG"])
    }

    func testProfileTitleDecoding() {
        XCTAssertEqual(ConfigParser.decodeProfileTitle("Premium"), "Premium")
        XCTAssertEqual(ConfigParser.decodeProfileTitle("My%20VPN"), "My VPN")
        XCTAssertEqual(ConfigParser.decodeProfileTitle("base64:TmVidWxhR3VhcmQ="), "NebulaGuard")
    }

    func testPercentDecodeKeepsPlus() {
        XCTAssertEqual(Codec.percentDecode("a+b%2Bc%D0%96"), "a+b+cЖ")
    }
}
