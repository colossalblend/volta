import SwiftUI

/// The home screen's one bold thing: a copper disc, like the plates of Volta's pile.
///
///  - off: bare copper, turned like a coin, with the power mark cut into it;
///  - connecting: a spark runs round the rim and patina creeps out from where the finger
///    touched;
///  - on: the patina, the green that protects copper, has covered the disc, and faint
///    rings leave it in time with the traffic;
///  - turning off scrapes the patina back; a failure flashes oxide red and shakes.
///
/// [activity] is 0…1, how busy the tunnel is right now.
struct PowerDisc: View {
    var state: VpnState
    var working: Bool
    var enabled: Bool
    var size: CGFloat
    var activity: Double
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var still
    @State private var anim = DiscAnimation()
    @State private var pressed = false
    @State private var settleTick = 0

    private var connected: Bool { state == .connected }
    private var draining: Bool { state == .disconnecting }
    private var busy: Bool { working || state == .connecting || draining }
    private var failed: Bool { state == .error && !busy }
    private var up: Bool { connected && !busy }

    /// Everything that changes what the disc does, as one value to watch.
    private var mode: Int { (up ? 1 : 0) | (busy ? 2 : 0) | (draining ? 4 : 0) | (failed ? 8 : 0) }

    var body: some View {
        let animating = !still && (busy || up || pressed || anim.isMoving(at: Date()))
        TimelineView(.animation(minimumInterval: busy || pressed ? 1.0 / 60 : 1.0 / 30, paused: !animating)) { timeline in
            Canvas { context, canvasSize in
                draw(context, canvasSize, timeline.date)
            }
        }
        .frame(width: size, height: size)
        .overlay(touchArea)
        .onAppear { anim.reset(up: up, still: still) }
        .onChange(of: mode) { _ in retarget() }
        .onChange(of: pressed) { down in
            anim.press.animate(to: down ? 0.94 : 1, duration: down ? 0.12 : 0.35,
                               curve: down ? .fastOutSlowIn : .backOut, now: Date())
        }
        .accessibilityElement()
        .accessibilityLabel(busy ? L("disc.a11y.cancel") : (connected ? L("disc.a11y.off") : L("disc.a11y.on")))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { if enabled && !draining { action() } }
        .id(settleTick >= 0 ? 0 : 1)
    }

    /// Only the disc itself answers a touch; where it was touched is where the patina starts.
    private var touchArea: some View {
        let discSize = size * DiscGeometry.disc
        return Circle()
            .fill(Color.clear)
            .frame(width: discSize, height: discSize)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !pressed {
                            pressed = true
                            anim.origin = CGPoint(
                                x: min(1, max(0, value.startLocation.x / discSize)),
                                y: min(1, max(0, value.startLocation.y / discSize))
                            )
                        }
                    }
                    .onEnded { value in
                        pressed = false
                        let dx = value.location.x - discSize / 2
                        let dy = value.location.y - discSize / 2
                        let inside = (dx * dx + dy * dy).squareRoot() <= discSize / 2 + 12
                        // Tappable while connecting too: a second tap cancels the attempt.
                        if inside && enabled && !draining {
                            Haptics.tap()
                            action()
                        }
                    }
            )
    }

    private func retarget() {
        let now = Date()
        let a = anim
        if still {
            a.patina.snap(up ? 1 : 0)
        } else if up {
            a.patina.animate(to: 1, duration: 0.9, curve: .fastOutSlowIn, now: now)
        } else if draining {
            a.patina.animate(to: 0, duration: 0.65, curve: .fastOutSlowIn, now: now)
        } else if busy {
            // Creeps and never quite arrives: picking a server can take a while.
            a.patina.animate(to: 0.42, duration: 7, curve: .creep, now: now)
        } else {
            a.patina.animate(to: 0, duration: 0.6, curve: .fastOutSlowIn, now: now)
        }

        if failed {
            a.oxide.snap(0.85)
            a.oxide.animate(to: 0.3, duration: 0.7, curve: .linear, now: now.addingTimeInterval(still ? 0 : 0.33))
            if !still { a.shakeStart = now }
        } else {
            a.oxide.animate(to: 0, duration: 0.3, curve: .linear, now: now)
        }

        if up && !a.wasUp && !still {
            a.burst.snap(0)
            a.burst.animate(to: 1, duration: 1.1, curve: .fastOutSlowIn, now: now)
        }
        a.wasUp = up

        a.sparkAlpha.animate(to: busy ? 1 : 0, duration: 0.45, curve: .linear, now: now)
        a.fieldAlpha.animate(to: up ? 1 : (busy ? 0.45 : 0), duration: 0.8, curve: .linear, now: now)
        a.ringMix.animate(to: up ? 1 : 0, duration: 0.7, curve: .linear, now: now)
        a.sparkSpeed.animate(to: busy ? 300 : 60, duration: 0.9, curve: .linear, now: now)

        // Wake the view once things have settled, so the timeline can pause.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { settleTick &+= 1 }
    }

    // MARK: - Drawing

    private func draw(_ context: GraphicsContext, _ canvasSize: CGSize, _ now: Date) {
        let a = anim
        let dt = a.lastFrame.map { min(0.1, max(0, now.timeIntervalSince($0))) } ?? 0
        a.lastFrame = now
        a.spark = (a.spark + a.sparkSpeed.value(now) * dt).truncatingRemainder(dividingBy: 360)
        a.field = (a.field + (0.22 + 0.55 * min(1, max(0, activity))) * dt).truncatingRemainder(dividingBy: 1)

        var ctx = context
        ctx.translateBy(x: a.shake(now), y: 0)

        let outer = min(canvasSize.width, canvasSize.height) / 2
        let c = CGPoint(x: canvasSize.width / 2, y: canvasSize.height / 2)
        let disc = outer * DiscGeometry.disc

        // Shadow under the disc.
        let sc = CGPoint(x: c.x, y: c.y + disc * 0.12)
        let sr = disc * 1.25
        ctx.fill(circle(sc, sr), with: .radialGradient(
            Gradient(colors: [Color.black.opacity(0.5), Color.black.opacity(0)]),
            center: sc, startRadius: 0, endRadius: sr
        ))

        // Field rings, in time with the traffic.
        let fieldAlpha = a.fieldAlpha.value(now)
        let ringColor = Metals.copper.mix(Metals.patina, a.ringMix.value(now))
        if fieldAlpha > 0.001 {
            for i in 0..<3 {
                let t = (a.field + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                let fade = (1 - t) * (1 - t)
                ctx.stroke(circle(c, disc + (outer - disc) * t),
                           with: .color(ringColor.opacity(0.32 * fade * fieldAlpha)), lineWidth: 1.2)
            }
        }

        // One ring when the tunnel comes up.
        let burst = a.burst.value(now)
        if burst < 0.999 {
            ctx.stroke(circle(c, disc + (outer - disc) * burst),
                       with: .color(Metals.patinaLight.opacity((1 - burst) * 0.55)),
                       lineWidth: 3 * (1 - burst) + 1)
        }

        let sparkAlpha = a.sparkAlpha.value(now)
        if sparkAlpha > 0.001 { drawSpark(ctx, c, disc + 7, a.spark, sparkAlpha) }

        // The disc itself.
        var d = ctx
        let press = a.press.value(now)
        d.translateBy(x: c.x, y: c.y)
        d.scaleBy(x: press, y: press)
        d.translateBy(x: -c.x, y: -c.y)
        d.opacity = enabled ? 1 : 0.5
        let r = disc
        let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
        d.clip(to: Path(ellipseIn: rect))

        drawCopper(d, c, r)
        let from = CGPoint(x: rect.minX + a.origin.x * r * 2, y: rect.minY + a.origin.y * r * 2)
        let distance = hypot(from.x - c.x, from.y - c.y)
        // At 1 the solid part of the patina reaches the far edge, wherever it started.
        let front = a.patina.value(now) * (distance + r * (1 + DiscGeometry.edge))
        if front > 1 {
            drawPatina(d, canvasSize, c, r, from, front)
            drawSpecks(d, c, r, from, front)
        }
        drawBand(d, c, r)
        let passed = min(1, max(0, (front - distance) / (r * 0.35)))
        drawPowerMark(d, c, r, Metals.copperInk.mix(Metals.patinaInk, Double(passed)))
        let oxide = a.oxide.value(now)
        if oxide > 0.001 { d.fill(Path(ellipseIn: rect), with: .color(Metals.oxide.opacity(oxide * 0.55))) }
        drawRim(d, c, r)
    }

    private func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
    }

    /// Bare copper: lit from the top left, with the faint rings of a turned blank.
    private func drawCopper(_ d: GraphicsContext, _ c: CGPoint, _ r: CGFloat) {
        let light = CGPoint(x: c.x - r * 0.35, y: c.y - r * 0.42)
        d.fill(circle(c, r), with: .radialGradient(
            Gradient(stops: [
                .init(color: Metals.copperLight.color, location: 0),
                .init(color: Metals.copper.color, location: 0.55),
                .init(color: Metals.copperDark.color, location: 1)
            ]),
            center: light, startRadius: 0, endRadius: r * 1.7
        ))
        let turns = 16
        for k in 1...turns {
            let color = k % 2 == 0 ? Color.white.opacity(0.035) : Color.black.opacity(0.04)
            d.stroke(circle(c, r * CGFloat(k) / CGFloat(turns)), with: .color(color), lineWidth: 0.8)
        }
        // A brushed sheen across the disc, like light on a coin.
        d.fill(circle(c, r), with: .conicGradient(
            Gradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: Color.white.opacity(0.10), location: 0.12),
                .init(color: .clear, location: 0.25),
                .init(color: .clear, location: 0.62),
                .init(color: Color.white.opacity(0.07), location: 0.75),
                .init(color: .clear, location: 0.88),
                .init(color: .clear, location: 1)
            ]),
            center: c, angle: .zero
        ))
    }

    /// Patina out to [front] from [from], with a soft edge.
    private func drawPatina(_ d: GraphicsContext, _ canvasSize: CGSize, _ c: CGPoint, _ r: CGFloat,
                            _ from: CGPoint, _ front: CGFloat) {
        d.drawLayer { layer in
            let light = CGPoint(x: c.x - r * 0.35, y: c.y - r * 0.42)
            layer.fill(circle(c, r), with: .radialGradient(
                Gradient(stops: [
                    .init(color: Metals.patinaLight.color, location: 0),
                    .init(color: Metals.patina.color, location: 0.5),
                    .init(color: Metals.patinaDark.color, location: 1)
                ]),
                center: light, startRadius: 0, endRadius: r * 1.7
            ))
            layer.blendMode = .destinationIn
            let soft = min(0.9, max(0, r * DiscGeometry.edge / front))
            layer.fill(Path(CGRect(origin: .zero, size: canvasSize)), with: .radialGradient(
                Gradient(stops: [
                    .init(color: .white, location: 0),
                    .init(color: .white, location: 1 - soft),
                    .init(color: Color.white.opacity(0), location: 1)
                ]),
                center: from, startRadius: 0, endRadius: front
            ))
        }
    }

    /// Oxidation spots: a few run ahead of the patina's edge, the rest texture it.
    private func drawSpecks(_ d: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ from: CGPoint, _ front: CGFloat) {
        let reach = front + r * 0.16
        for s in DiscGeometry.specks {
            let p = CGPoint(x: c.x + s.x * r, y: c.y + s.y * r)
            let distance = hypot(p.x - from.x, p.y - from.y)
            let shown = min(1, max(0, (reach - distance) / (r * 0.16)))
            if shown > 0 {
                let metal = s.light ? Metals.patinaLight : Metals.patinaDark
                d.fill(circle(p, s.radius * r), with: .color(metal.opacity(s.alpha * Double(shown))))
            }
        }
    }

    /// The power mark, cut into the metal: a dark stroke with a lit lower lip.
    private func drawPowerMark(_ d: GraphicsContext, _ c: CGPoint, _ r: CGFloat, _ ink: RGB) {
        let radius = r * 0.30
        let style = StrokeStyle(lineWidth: r * 0.085, lineCap: .round)
        func mark(_ offset: CGFloat, _ color: Color) {
            var arc = Path()
            arc.addRelativeArc(center: CGPoint(x: c.x, y: c.y + offset), radius: radius,
                               startAngle: .degrees(-58), delta: .degrees(296))
            d.stroke(arc, with: .color(color), style: style)
            var line = Path()
            line.move(to: CGPoint(x: c.x, y: c.y - radius * 1.18 + offset))
            line.addLine(to: CGPoint(x: c.x, y: c.y - radius * 0.28 + offset))
            d.stroke(line, with: .color(color), style: style)
        }
        mark(1.2, Color.white.opacity(0.22))
        mark(0, ink.opacity(0.88))
    }

    /// The disc's turned edge stays bare copper, so the patina sits inside a rim.
    private func drawBand(_ d: GraphicsContext, _ c: CGPoint, _ r: CGFloat) {
        let width = r * 0.06
        d.stroke(circle(c, r - width / 2), with: .linearGradient(
            Gradient(colors: [Metals.copperLight.color, Metals.copper.color, Metals.copperDark.color]),
            startPoint: CGPoint(x: c.x - r, y: c.y - r), endPoint: CGPoint(x: c.x + r, y: c.y + r)
        ), lineWidth: width)
    }

    /// A thin bevel: light on the top left edge, shade on the bottom right.
    private func drawRim(_ d: GraphicsContext, _ c: CGPoint, _ r: CGFloat) {
        d.stroke(circle(c, r - 0.75), with: .linearGradient(
            Gradient(colors: [Color.white.opacity(0.35), Color.white.opacity(0), Color.black.opacity(0.35)]),
            startPoint: CGPoint(x: c.x - r, y: c.y - r), endPoint: CGPoint(x: c.x + r, y: c.y + r)
        ), lineWidth: 1.5)
    }

    /// The spark on the rim, trailing a tail that fades behind it.
    private func drawSpark(_ d: GraphicsContext, _ c: CGPoint, _ orbit: CGFloat, _ angle: Double, _ alpha: Double) {
        let steps = 28
        let span = 120.0
        for i in 0..<steps {
            let start = angle - 90 - span * Double(i + 1) / Double(steps)
            var arc = Path()
            arc.addRelativeArc(center: c, radius: orbit, startAngle: .degrees(start),
                               delta: .degrees(span / Double(steps) + 0.6))
            d.stroke(arc, with: .color(Metals.copperLight.opacity(alpha * 0.85 * (1 - Double(i) / Double(steps)))),
                     style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        let rad = (angle - 90) * .pi / 180
        let dot = CGPoint(x: c.x + CGFloat(cos(rad)) * orbit, y: c.y + CGFloat(sin(rad)) * orbit)
        d.fill(circle(dot, 14), with: .radialGradient(
            Gradient(colors: [Metals.spark.opacity(0.55 * alpha), Metals.spark.opacity(0)]),
            center: dot, startRadius: 0, endRadius: 14
        ))
        d.fill(circle(dot, 3.5), with: .color(Metals.spark.opacity(alpha)))
    }
}

/// How busy the tunnel is, 0…1, from the speed on a log scale: 1 KB/s → 0, ~10 MB/s → 1.
func trafficActivity(_ traffic: Traffic) -> Double {
    let bps = Double(max(0, traffic.downSpeed + traffic.upSpeed))
    if bps < 1024 { return 0 }
    return min(1, max(0, log(bps / 1024) / log(10_000)))
}

enum DiscGeometry {
    static let disc: CGFloat = 0.62
    /// How wide the patina's soft front is, as a share of the radius.
    static let edge: CGFloat = 0.12

    struct Speck {
        let x: CGFloat
        let y: CGFloat
        let radius: CGFloat
        let alpha: Double
        let light: Bool
    }

    /// Always the same spots: a fixed seed.
    static let specks: [Speck] = {
        var seed: UInt64 = 7
        func next() -> Double {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(seed >> 11) / Double(1 << 53)
        }
        return (0..<46).map { _ -> Speck in
            let a = next() * 2 * .pi
            let d = next().squareRoot() * 0.86
            return Speck(
                x: CGFloat(cos(a) * d),
                y: CGFloat(sin(a) * d),
                radius: CGFloat(0.005 + next() * 0.011),
                alpha: 0.12 + next() * 0.22,
                light: next() < 0.6
            )
        }
    }()
}

/// The disc's moving parts. A class held in @State, so the canvas can advance its clocks
/// every frame without re-rendering the view.
final class DiscAnimation {
    var patina = Tween(0)
    var oxide = Tween(0)
    var burst = Tween(1)
    var sparkAlpha = Tween(0)
    var fieldAlpha = Tween(0)
    var ringMix = Tween(0)
    var sparkSpeed = Tween(60)
    var press = Tween(1)
    var spark: Double = -90
    var field: Double = 0
    var lastFrame: Date?
    var shakeStart: Date?
    var wasUp = false
    var origin = CGPoint(x: 0.5, y: 0.5)

    func reset(up: Bool, still: Bool) {
        patina.snap(up ? 1 : 0)
        fieldAlpha.snap(up ? 1 : 0)
        ringMix.snap(up ? 1 : 0)
        wasUp = up
    }

    func isMoving(at now: Date) -> Bool {
        let tweens = [patina, oxide, burst, sparkAlpha, fieldAlpha, ringMix, sparkSpeed, press]
        if tweens.contains(where: { !$0.isDone(at: now) }) { return true }
        if let s = shakeStart, now.timeIntervalSince(s) < 0.4 { return true }
        return false
    }

    /// The failure shake: 10, -8, 6, -4, 2, 0 points, 55 ms each.
    func shake(_ now: Date) -> CGFloat {
        guard let s = shakeStart else { return 0 }
        let t = now.timeIntervalSince(s)
        let keys: [CGFloat] = [0, 10, -8, 6, -4, 2, 0]
        let step = 0.055
        let i = Int(t / step)
        if i >= keys.count - 1 { return 0 }
        let f = CGFloat((t - Double(i) * step) / step)
        return keys[i] + (keys[i + 1] - keys[i]) * f
    }
}

enum Curve {
    case linear, fastOutSlowIn, creep, backOut

    func apply(_ x: Double) -> Double {
        switch self {
        case .linear: return x
        case .fastOutSlowIn: return Curve.bezier(0.4, 0, 0.2, 1, x)
        case .creep: return Curve.bezier(0.1, 0.6, 0.3, 1, x)
        case .backOut:
            let s = 1.70158
            let t = x - 1
            return t * t * ((s + 1) * t + s) + 1
        }
    }

    /// A CSS-style cubic bezier easing, solved for x by Newton's method.
    static func bezier(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ x: Double) -> Double {
        func sample(_ a1: Double, _ a2: Double, _ t: Double) -> Double {
            let c = 3 * a1
            let b = 3 * (a2 - a1) - c
            let a = 1 - c - b
            return ((a * t + b) * t + c) * t
        }
        func slope(_ a1: Double, _ a2: Double, _ t: Double) -> Double {
            let c = 3 * a1
            let b = 3 * (a2 - a1) - c
            let a = 1 - c - b
            return (3 * a * t + 2 * b) * t + c
        }
        var t = x
        for _ in 0..<8 {
            let dx = sample(x1, x2, t) - x
            let s = slope(x1, x2, t)
            if abs(dx) < 1e-5 || abs(s) < 1e-6 { break }
            t -= dx / s
        }
        return sample(y1, y2, min(1, max(0, t)))
    }
}

struct Tween {
    private var from: Double
    private var to: Double
    private var start = Date.distantPast
    private var duration: Double = 0
    private var curve: Curve = .linear

    init(_ value: Double) {
        from = value
        to = value
    }

    func value(_ now: Date) -> Double {
        if duration <= 0 { return to }
        let x = min(1, max(0, now.timeIntervalSince(start) / duration))
        return from + (to - from) * curve.apply(x)
    }

    func isDone(at now: Date) -> Bool { duration <= 0 || now.timeIntervalSince(start) >= duration }

    mutating func animate(to target: Double, duration: Double, curve: Curve, now: Date) {
        from = value(min(now, Date()))
        to = target
        start = now
        self.duration = duration
        self.curve = curve
    }

    mutating func snap(_ value: Double) {
        from = value
        to = value
        duration = 0
    }
}
