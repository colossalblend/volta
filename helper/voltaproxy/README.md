# voltaproxy: AmneziaWG and Hysteria v1 in Volta

Volta's Xray core can't dial AmneziaWG or Hysteria v1. For those servers Volta starts
this helper, which runs the server's config and serves a SOCKS5 proxy (TCP and UDP) on
127.0.0.1:10860. The core then sends the tunnel's traffic to that port, so routing,
DNS and split tunnelling work exactly as for any other server.

- **AmneziaWG 1.0, 1.5, 2.x and 3** (and plain WireGuard) from a `.conf`, on
  [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) v3 with a userspace
  network stack: junk packets, paddings S1–S4, ranged H1–H4, signature packets I1–I5,
  header protection and the other 3.x options.
- **Hysteria v1** (`hysteria://`, udp mode, xplus obfuscation, port hopping) and
  Hysteria2, on [sing-quic](https://github.com/SagerNet/sing-quic). Volta dials
  Hysteria2 through the core itself; the helper's Hysteria2 is a spare.

## Protocol with the app

The app writes one JSON line to the helper's stdin:

```json
{"kind": "awg", "config": "<.conf text or share link>", "listen": "127.0.0.1:10860"}
```

`kind` is `awg`, `hysteria` or `hysteria2`. The helper answers on stdout with JSON
lines: `{"event":"ready"}` once the proxy listens, or `{"event":"error","message":…}`.
It runs until stdin closes. With `"probe": "<url>"` and `"timeoutMs"` it instead makes
one HTTP request through the server, prints `{"event":"delay","ms":…}` and exits; Volta
pings these servers that way.

## Build

CI runs `go test ./...` here (the tests raise an AmneziaWG 3 server with header
protection and Hysteria v1 and v2 servers on the runner and go through them over
SOCKS5), then builds the helper with the Android NDK for every CPU type into
`app/src/main/jniLibs/<abi>/libvoltaproxy.so` (step "Voltaproxy helper" in
`.github/workflows/android.yml`). Without that step the app builds as usual and these
servers say they are not part of the build.

## License

sing-quic is licensed under the GNU GPL v3.0 or later, so this helper is too. It is a
separate program that Volta starts and talks to over a socket and a pipe.
