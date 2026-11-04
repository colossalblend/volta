# OpenFlux in Volta

Volta connects to OpenFlux servers (`openflux://` links) through a helper program
built from [OpenFlux](https://github.com/p1neappleXpress/OpenFlux) at the commit in
`VERSION`, together with `voltaflux/main.go`: a small client on top of OpenFlux's own
`mobile` package that serves a SOCKS5 proxy on 127.0.0.1:10850 for the link it is
given. Volta's Xray core sends the tunnel's traffic to that port.

CI builds it for every CPU type with the Android NDK (step "OpenFlux helper" in
`.github/workflows/android.yml`) into `app/src/main/jniLibs/<abi>/libopenflux.so`,
the place Android unpacks programs an app may run. Without that step the app builds
as usual and OpenFlux servers say they are not part of the build.

## Servers, groups and auto modes

- **One link is one server.** An `openflux://` link describes one exit (OpenFlux's
  `share.Config`). Several transports in it (a Yandex document, Mail.ru, `direct`…)
  are several roads to that exit: the core runs them in one session and uses the
  best one that reaches the exit, falling back by priority. Volta lists such a link
  as one server and leaves the choice of carrier to the helper.
- **Several exits are several servers:** a subscription or a pasted list with several
  links, a JSON array of OpenFlux configurations (`{"name", "secret",
  "transports": [...]}`, what `openflux --make-link` takes), or JSON groups
  (`{"wifi": [...], "lte": [...]}`) holding links or configurations.
- **Home and LTE** work as for every protocol: `[L]`/`[H]` before the name (a
  `#[L] name` may follow the link, Volta keeps it and gives the core the bare link),
  `server-type`, JSON groups, the name check. A link nobody marked that rides only
  documents and other Russian services goes to LTE; one with `direct` stays Home.
- **Auto modes and the background check** pick OpenFlux servers like any other. Only
  one helper runs at a time: moving to another OpenFlux server replaces it, moving to
  an Xray server stops it, with the VPN interface kept up meanwhile. An exit that
  does not answer within 30 seconds counts as a dead node: with automatic switching
  on, the tunnel moves on to the next server of the group.
- **Latency.** The server in use is measured through the helper, which is through its
  exit. The others cannot be without starting a helper (an exit serves one client at
  a time), so their carriers are reached from outside: the exit's port for `direct`,
  the service for a document. The figure is three handshakes of the best carrier
  that answered, never less than what the server gave the last time it was in use.
  Such a guess never pushes a working server out; it counts once the server has
  carried traffic, or when the server in use fails.

OpenFlux and `voltaflux` are licensed under the GNU GPL v3.0 or later. The helper is a
separate program that Volta starts and talks to over a socket and a pipe.

To try a newer OpenFlux, put its commit into `VERSION`.
