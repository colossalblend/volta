import SwiftUI
import UIKit

extension DesignTheme {
    /// Light or dark comes from iOS.
    var followsSystem: Bool { self == .system || self == .dynamic }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }

    /// "#C4784A" → colour; nil when it is not one.
    init?(hexString: String) {
        let clean = hexString.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
        guard clean.count == 6, let value = UInt32(clean, radix: 16) else { return nil }
        self.init(hex: value)
    }
}

/// Every colour a screen uses, one set per theme, so all screens speak the same language:
/// zinc-grey surfaces, copper for action, the green patina for "protected".
struct Palette {
    var background: Color
    var surface: Color
    var surfaceVariant: Color
    var onBackground: Color
    var onSurface: Color
    var onSurfaceVariant: Color
    var primary: Color
    var onPrimary: Color
    var outline: Color
    var error: Color
    var heroTop: Color
    var good: Color
    var fair: Color
    var poor: Color
    /// Home group colour (Wi-Fi / open internet).
    var home: Color
    /// LTE group colour (mobile whitelist).
    var lte: Color
    var isDark: Bool

    /// Colour for a latency reading; -1 means "not measured".
    func latency(_ ms: Int) -> Color {
        if ms < 0 { return poor.opacity(0.35) }
        if ms < 120 { return good }
        if ms < 300 { return fair }
        return poor
    }

    static let volta = Palette(
        background: Color(hex: 0x101316), surface: Color(hex: 0x181D21), surfaceVariant: Color(hex: 0x22282D),
        onBackground: Color(hex: 0xEEE8DF), onSurface: Color(hex: 0xEEE8DF), onSurfaceVariant: Color(hex: 0x8E979E),
        primary: Color(hex: 0xD08350), onPrimary: Color(hex: 0x2B1206), outline: Color(hex: 0x2D343A),
        error: Color(hex: 0xE46F5C), heroTop: Color(hex: 0x1C2227),
        good: Color(hex: 0x63C2A6), fair: Color(hex: 0xE2B755), poor: Color(hex: 0xE46F5C),
        home: Color(hex: 0x5AA9E6), lte: Color(hex: 0xE0B341), isDark: true
    )

    static let light = Palette(
        background: Color(hex: 0xF6F3EE), surface: Color(hex: 0xFFFFFF), surfaceVariant: Color(hex: 0xEFEAE3),
        onBackground: Color(hex: 0x1A1C1E), onSurface: Color(hex: 0x1A1C1E), onSurfaceVariant: Color(hex: 0x6B6259),
        primary: Color(hex: 0xB05F28), onPrimary: .white, outline: Color(hex: 0xDCD3C8),
        error: Color(hex: 0xBE3B32), heroTop: Color(hex: 0xFFE3CE),
        good: Color(hex: 0x2C7F5B), fair: Color(hex: 0xB07C15), poor: Color(hex: 0xBE3B32),
        home: Color(hex: 0x2F74B5), lte: Color(hex: 0xA9710E), isDark: false
    )

    static let ocean = Palette(
        background: Color(hex: 0x0B1A20), surface: Color(hex: 0x12242C), surfaceVariant: Color(hex: 0x193239),
        onBackground: Color(hex: 0xCBE7E3), onSurface: Color(hex: 0xCBE7E3), onSurfaceVariant: Color(hex: 0x88A8AE),
        primary: Color(hex: 0x3DBFB4), onPrimary: Color(hex: 0x04211F), outline: Color(hex: 0x25464F),
        error: Color(hex: 0xE46F5C), heroTop: Color(hex: 0x143B42),
        good: Color(hex: 0x56CFA2), fair: Color(hex: 0xE2B755), poor: Color(hex: 0xE46F5C),
        home: Color(hex: 0x5AA9E6), lte: Color(hex: 0xE0B341), isDark: true
    )

    static let ember = Palette(
        background: Color(hex: 0x161210), surface: Color(hex: 0x221B16), surfaceVariant: Color(hex: 0x2E251E),
        onBackground: Color(hex: 0xF4E7DC), onSurface: Color(hex: 0xF4E7DC), onSurfaceVariant: Color(hex: 0xB6A292),
        primary: Color(hex: 0xF08A3C), onPrimary: Color(hex: 0x2A1305), outline: Color(hex: 0x3F3227),
        error: Color(hex: 0xE46F5C), heroTop: Color(hex: 0x3A2415),
        good: Color(hex: 0x63C79A), fair: Color(hex: 0xE2B755), poor: Color(hex: 0xE46F5C),
        home: Color(hex: 0x5AA9E6), lte: Color(hex: 0xE0B341), isDark: true
    )

    /// iOS's own colours and accent, light or dark with the system (Android's Material You
    /// counterpart: an iPhone gives apps no wallpaper colours, but it has its system tint).
    static func dynamic(dark: Bool) -> Palette {
        let accent = Color(UIColor.systemBlue)
        return Palette(
            background: Color(UIColor.systemGroupedBackground), surface: Color(UIColor.secondarySystemGroupedBackground),
            surfaceVariant: Color(UIColor.tertiarySystemFill), onBackground: Color(UIColor.label),
            onSurface: Color(UIColor.label), onSurfaceVariant: Color(UIColor.secondaryLabel),
            primary: accent, onPrimary: .white, outline: Color(UIColor.separator), error: Color(UIColor.systemRed),
            heroTop: Color(UIColor.secondarySystemGroupedBackground),
            good: Color(UIColor.systemGreen), fair: Color(UIColor.systemOrange), poor: Color(UIColor.systemRed),
            home: Color(UIColor.systemTeal), lte: Color(UIColor.systemYellow), isDark: dark
        )
    }

    static func resolve(_ theme: DesignTheme, systemDark: Bool) -> Palette {
        switch theme {
        case .dynamic: return .dynamic(dark: systemDark)
        case .system: return systemDark ? .volta : .light
        case .volta: return .volta
        case .light: return .light
        case .ocean: return .ocean
        case .ember: return .ember
        }
    }

    /// The three colours of a theme's coin in Settings: rim, face, centre.
    static func coin(_ theme: DesignTheme) -> (Color, Color, Color) {
        switch theme {
        case .volta: return (Color(hex: 0x101316), Color(hex: 0x22282D), Color(hex: 0xD08350))
        case .light: return (Color(hex: 0xEFEAE3), Color(hex: 0xFFFFFF), Color(hex: 0xB05F28))
        case .ocean: return (Color(hex: 0x0B1A20), Color(hex: 0x193239), Color(hex: 0x3DBFB4))
        case .ember: return (Color(hex: 0x161210), Color(hex: 0x2E251E), Color(hex: 0xF08A3C))
        case .system: return (Color(hex: 0x101316), Color(hex: 0xFFFFFF), Color(hex: 0xD08350))
        case .dynamic: return (Color(UIColor.systemGroupedBackground), Color(UIColor.secondarySystemGroupedBackground),
                               Color(UIColor.systemBlue))
        }
    }
}

/// The two metals of the power disc. They do not follow the theme: copper is copper
/// whatever the background, and so is its patina.
enum Metals {
    static let copperLight = RGB(0xF0B486)
    static let copper = RGB(0xC9773F)
    static let copperDark = RGB(0x7B3F1B)
    static let copperInk = RGB(0x3E1A08)
    static let patinaLight = RGB(0xA6E3CF)
    static let patina = RGB(0x52B094)
    static let patinaDark = RGB(0x1E5E4E)
    static let patinaInk = RGB(0x0B2F27)
    static let oxide = RGB(0xD9573F)
    /// The spark that runs round the disc: hot, almost white.
    static let spark = RGB(0xFFE6C7)
}

/// A colour that can be mixed with another, for the disc's animations.
struct RGB {
    var r: Double, g: Double, b: Double

    init(_ hex: UInt32) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
    }

    init(r: Double, g: Double, b: Double) {
        self.r = r
        self.g = g
        self.b = b
    }

    var color: Color { Color(.sRGB, red: r, green: g, blue: b, opacity: 1) }

    func opacity(_ a: Double) -> Color { Color(.sRGB, red: r, green: g, blue: b, opacity: max(0, min(1, a))) }

    func mix(_ other: RGB, _ t: Double) -> RGB {
        let k = max(0, min(1, t))
        return RGB(r: r + (other.r - r) * k, g: g + (other.g - g) * k, b: b + (other.b - b) * k)
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.volta
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
