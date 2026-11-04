import CoreText
import SwiftUI
import UIKit

/// Two faces, each with one job. Unbounded, wide and heavy, speaks for the app: titles,
/// the connection status, the numbers you glance at. Onest carries everything you read.
/// Both are variable fonts (bundled, UIAppFonts), so every weight comes from one file
/// through its 'wght' axis. Sizes follow Dynamic Type, within reason.
enum VoltaFont {
    private static let weightAxis = 0x7767_6874 // 'wght'

    static func unbounded(_ size: CGFloat, _ weight: CGFloat, _ style: UIFont.TextStyle = .body) -> Font {
        make("Unbounded-Regular", size, weight, style)
    }

    static func onest(_ size: CGFloat, _ weight: CGFloat, _ style: UIFont.TextStyle = .body) -> Font {
        make("Onest-Regular", size, weight, style)
    }

    static func uiFont(_ name: String, _ size: CGFloat, _ weight: CGFloat) -> UIFont {
        let variation = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)
        let descriptor = UIFontDescriptor(fontAttributes: [
            .name: name,
            variation: [weightAxis: weight]
        ])
        return UIFont(descriptor: descriptor, size: size)
    }

    private static func make(_ name: String, _ size: CGFloat, _ weight: CGFloat, _ style: UIFont.TextStyle) -> Font {
        let base = uiFont(name, size, weight)
        let scaled = UIFontMetrics(forTextStyle: style).scaledFont(for: base, maximumPointSize: size * 1.45)
        return Font(scaled as CTFont)
    }
}

/// The Material type scale of the Android app, by the same names.
extension Font {
    static let vDisplay = VoltaFont.unbounded(30, 600, .largeTitle)
    static let vHeadlineMedium = VoltaFont.unbounded(25, 600, .title1)
    static let vHeadlineSmall = VoltaFont.unbounded(21, 600, .title2)
    static let vTitleLarge = VoltaFont.unbounded(20, 600, .title3)
    static let vTitleMedium = VoltaFont.onest(16, 600, .headline)
    static let vTitleSmall = VoltaFont.onest(14, 600, .subheadline)
    static let vBodyLarge = VoltaFont.onest(16, 400, .body)
    static let vBodyMedium = VoltaFont.onest(14, 400, .callout)
    static let vBodySmall = VoltaFont.onest(12.5, 400, .footnote)
    static let vLabelLarge = VoltaFont.onest(14, 600, .subheadline)
    static let vLabelLargeMedium = VoltaFont.onest(14, 500, .subheadline)
    static let vLabelMedium = VoltaFont.onest(12, 500, .caption1)
    static let vLabelMediumBold = VoltaFont.onest(12, 600, .caption1)
    static let vLabelSmall = VoltaFont.onest(11, 500, .caption2)
    static let vLabelSmallBold = VoltaFont.onest(11, 700, .caption2)
    static let vTitleSmallMedium = VoltaFont.onest(14, 500, .subheadline)
}

/// How things move: answers to a touch are springs, so they keep the finger's energy and
/// settle by themselves.
enum Motion {
    /// A press: quick to give, a little bounce back.
    static let press = Animation.spring(response: 0.28, dampingFraction: 0.5)
    /// A thumb or indicator sliding to where the finger went.
    static let slide = Animation.spring(response: 0.42, dampingFraction: 0.74)
    /// Something arriving: settles without overshoot.
    static let settle = Animation.spring(response: 0.42, dampingFraction: 1)
}
