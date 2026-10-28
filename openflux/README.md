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

OpenFlux and `voltaflux` are licensed under the GNU GPL v3.0 or later. The helper is a
separate program that Volta starts and talks to over a socket and a pipe.

To try a newer OpenFlux, put its commit into `VERSION`.
