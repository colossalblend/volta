import SwiftUI
import UIKit

// The small parts every screen is built from, as in the Android Kit: tonal fills a step
// off the background instead of outlines, an outline only for "this one / in focus",
// copper for the chosen thing, springs for answers to a touch.

let cardRadius: CGFloat = 22
let smallRadius: CGFloat = 14
let fieldRadius: CGFloat = 18

/// Text in the app's language: `L("key")`, `L("key", 3)`.
func L(_ key: String, _ args: CVarArg...) -> String { L10n.tr(key, args: args) }

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

/// A press that gives a little and springs back.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.97
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

/// The one card style: a tonal fill a step above the background, no border, no shadow.
/// Only a selected card gets an outline, so an outline always means "this one".
struct VoltaCard<Content: View>: View {
    var selected = false
    var padding: CGFloat = 16
    var spacing: CGFloat = 0
    var action: (() -> Void)?
    @ViewBuilder var content: () -> Content
    @Environment(\.palette) private var p

    init(selected: Bool = false, padding: CGFloat = 16, spacing: CGFloat = 0,
         action: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.selected = selected
        self.padding = padding
        self.spacing = spacing
        self.action = action
        self.content = content
    }

    var body: some View {
        if let action = action {
            Button(action: action) { card }.buttonStyle(PressStyle(scale: 0.985))
        } else {
            card
        }
    }

    private var card: some View {
        let shape = RoundedRectangle(cornerRadius: cardRadius, style: .continuous)
        return VStack(alignment: .leading, spacing: spacing) { content() }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? p.primary.opacity(p.isDark ? 0.10 : 0.08) : p.surface, in: shape)
            .overlay(shape.strokeBorder(selected ? p.primary : Color.clear, lineWidth: 1.5))
            .contentShape(shape)
    }
}

/// A text field as a tonal well: no underline and no outline at rest; the outline comes in
/// while the field has focus, the way a selected card is outlined.
struct VoltaField: View {
    @Binding var text: String
    var placeholder: String = ""
    var label: String?
    var multiline = false
    var minLines = 1
    var maxLines = 1
    var restingOutline = false
    var keyboard: UIKeyboardType = .default
    @FocusState private var focused: Bool
    @Environment(\.palette) private var p

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: fieldRadius, style: .continuous)
        VStack(alignment: .leading, spacing: 4) {
            if let label = label {
                Text(label)
                    .font(.vLabelMedium)
                    .foregroundColor(focused ? p.primary : p.onSurfaceVariant)
            }
            Group {
                if multiline {
                    TextField("", text: $text, prompt: prompt, axis: .vertical)
                        .lineLimit(minLines...max(minLines, maxLines))
                } else {
                    TextField("", text: $text, prompt: prompt)
                        .lineLimit(1)
                }
            }
            .font(.vBodyLarge)
            .foregroundColor(p.onSurface)
            .tint(p.primary)
            .keyboardType(keyboard)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .focused($focused)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(p.surface, in: shape)
        .overlay(
            shape.strokeBorder(
                focused ? p.primary.opacity(0.8) : (restingOutline ? p.outline : Color.clear),
                lineWidth: 1.5
            )
        )
        .animation(.easeOut(duration: 0.18), value: focused)
        .contentShape(shape)
        .onTapGesture { focused = true }
    }

    private var prompt: Text {
        Text(placeholder).foregroundColor(p.onSurfaceVariant.opacity(0.7))
    }
}

/// The search well: a pill with the magnifier in front and a clear button once typed in.
struct VoltaSearchField: View {
    @Binding var text: String
    var placeholder: String
    @Environment(\.palette) private var p

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(p.onSurfaceVariant)
            TextField("", text: $text, prompt: Text(placeholder).foregroundColor(p.onSurfaceVariant.opacity(0.7)))
                .font(.vBodyLarge)
                .foregroundColor(p.onSurface)
                .tint(p.primary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundColor(p.onSurfaceVariant)
                }
                .accessibilityLabel(L("common.clear"))
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 50)
        .background(p.surface, in: Capsule())
    }
}

/// A filter or choice chip: the chosen one takes a wash of its colour, the rest sit on the
/// plain tonal fill.
struct VoltaChip: View {
    var text: String
    var selected: Bool
    var accent: Color?
    var icon: String?
    var rest: Color?
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        let tint = accent ?? p.primary
        let ink = selected ? tint : p.onSurface
        Button {
            Haptics.select()
            action()
        } label: {
            HStack(spacing: 6) {
                if let icon = icon {
                    Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                }
                Text(text)
                    .font(selected ? .vLabelLarge : .vLabelLargeMedium)
                    .lineLimit(1)
            }
            .foregroundColor(ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(selected ? tint.opacity(p.isDark ? 0.18 : 0.14) : (rest ?? p.surface), in: Capsule())
        }
        .buttonStyle(PressStyle())
        .animation(.easeOut(duration: 0.2), value: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A few mutually exclusive options on one track with a sliding thumb.
struct SlidingChoice<T: Hashable>: View {
    var options: [T]
    @Binding var selection: T
    var label: (T) -> String
    var height: CGFloat = 40
    var accent: Color?
    @Environment(\.palette) private var p

    var body: some View {
        let tint = accent ?? p.primary
        let index = CGFloat(options.firstIndex(of: selection) ?? 0)
        GeometryReader { geo in
            let slot = (geo.size.width - 6) / CGFloat(max(options.count, 1))
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(tint.opacity(p.isDark ? 0.2 : 0.15))
                    .frame(width: slot, height: height - 6)
                    .offset(x: slot * index)
                    .animation(Motion.slide, value: index)
                HStack(spacing: 0) {
                    ForEach(options, id: \.self) { option in
                        let on = option == selection
                        Button {
                            if !on { Haptics.select() }
                            selection = option
                        } label: {
                            Text(label(option))
                                .font(on ? .vLabelLarge : .vLabelLargeMedium)
                                .foregroundColor(on ? tint : p.onSurfaceVariant)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                                .frame(width: slot, height: height - 6)
                                .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }
            .padding(3)
        }
        .frame(height: height)
        .background(p.surface, in: Capsule())
    }
}

/// The round coloured badge in front of a row.
struct IconBubble: View {
    var icon: String
    var tint: Color
    var size: CGFloat = 36
    var active = true
    @Environment(\.palette) private var p

    var body: some View {
        ZStack {
            Circle().fill(active ? tint.opacity(0.16) : p.surfaceVariant)
            Image(systemName: icon)
                .font(.system(size: size * 0.45, weight: .semibold))
                .foregroundColor(active ? tint : p.onSurfaceVariant)
        }
        .frame(width: size, height: size)
        .animation(.easeOut(duration: 0.2), value: active)
    }
}

/// A setting that is on or off. The whole row toggles, and the badge lights up with the
/// switch, so a glance down the card shows what is on.
struct SwitchRow: View {
    var icon: String
    var tint: Color
    var title: String
    var subtitle: String
    @Binding var isOn: Bool
    @Environment(\.palette) private var p

    var body: some View {
        HStack(spacing: 12) {
            IconBubble(icon: icon, tint: tint, active: isOn)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.vTitleSmall).foregroundColor(p.onSurface)
                Hint(subtitle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // The row takes the tap, so the switch only shows the state.
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(p.primary)
                .allowsHitTesting(false)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .onTapGesture {
            Haptics.select()
            isOn.toggle()
        }
        .accessibilityElement(children: .combine)
    }
}

/// A row that opens something: badge, text, chevron (or whatever [trailing] puts there).
struct LinkRow<Trailing: View>: View {
    var icon: String
    var tint: Color
    var title: String
    var subtitle: String?
    var action: () -> Void
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.palette) private var p

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                IconBubble(icon: icon, tint: tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.vTitleSmall).foregroundColor(p.onSurface)
                    if let subtitle = subtitle, !subtitle.isEmpty { Hint(subtitle) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                trailing()
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.98))
    }
}

extension LinkRow where Trailing == ChevronView {
    init(icon: String, tint: Color, title: String, subtitle: String?, action: @escaping () -> Void) {
        self.icon = icon
        self.tint = tint
        self.title = title
        self.subtitle = subtitle
        self.action = action
        self.trailing = { ChevronView() }
    }
}

struct ChevronView: View {
    @Environment(\.palette) private var p
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(p.onSurfaceVariant)
    }
}

struct SectionHeader<Action: View>: View {
    var title: String
    @ViewBuilder var action: () -> Action
    @Environment(\.palette) private var p

    var body: some View {
        HStack {
            Text(title).font(.vTitleMedium).foregroundColor(p.onBackground)
            Spacer(minLength: 8)
            action()
        }
        .padding(.top, 4)
        .padding(.bottom, 2)
    }
}

extension SectionHeader where Action == EmptyView {
    init(_ title: String) {
        self.title = title
        self.action = { EmptyView() }
    }
}

struct Hint: View {
    var text: String
    @Environment(\.palette) private var p

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.vBodySmall)
            .foregroundColor(p.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Compact status pill: a dot (or an icon) and a label.
struct StatusPill: View {
    var text: String
    var color: Color
    var icon: String?

    var body: some View {
        HStack(spacing: 6) {
            if let icon = icon {
                Image(systemName: icon).font(.system(size: 11, weight: .bold))
            } else {
                Circle().fill(color).frame(width: 7, height: 7)
            }
            Text(text).font(.vLabelMedium).lineLimit(1)
        }
        .foregroundColor(color)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.14), in: Capsule())
    }
}

struct EmptyState<Action: View>: View {
    var icon: String
    var title: String
    var subtitle: String
    @ViewBuilder var action: () -> Action
    @Environment(\.palette) private var p

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle().fill(p.surfaceVariant)
                Image(systemName: icon).font(.system(size: 22, weight: .semibold)).foregroundColor(p.onSurfaceVariant)
            }
            .frame(width: 56, height: 56)
            Text(title).font(.vTitleMedium).foregroundColor(p.onBackground)
            Text(subtitle)
                .font(.vBodySmall)
                .foregroundColor(p.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            action()
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .padding(.horizontal, 24)
    }
}

extension EmptyState where Action == EmptyView {
    init(icon: String, title: String, subtitle: String) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.action = { EmptyView() }
    }
}

/// Thin usage bar for subscription traffic.
struct UsageBar: View {
    var used: Int64
    var total: Int64
    @Environment(\.palette) private var p

    var body: some View {
        let fraction = total > 0 ? min(1, max(0, Double(used) / Double(total))) : 0
        let color = fraction > 0.9 ? p.poor : (fraction > 0.7 ? p.fair : p.primary)
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(p.surfaceVariant)
                Capsule().fill(color).frame(width: geo.size.width * fraction)
            }
        }
        .frame(height: 5)
    }
}

/// Four bars for a latency reading.
struct SignalBars: View {
    var ms: Int
    @Environment(\.palette) private var p

    var body: some View {
        let level: Int = ms < 0 ? 0 : ms < 120 ? 4 : ms < 250 ? 3 : ms < 500 ? 2 : 1
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(1...4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 2)
                    .fill(i <= level ? p.latency(ms) : p.outline)
                    .frame(width: 3.5, height: 14 * 0.25 * CGFloat(i))
            }
        }
        .frame(height: 14, alignment: .bottom)
        .animation(.easeOut(duration: 0.3), value: level)
    }
}

/// The filled copper button for the one main move on a screen.
struct PrimaryButton: View {
    var title: String
    var busy = false
    var enabled = true
    var height: CGFloat = 54
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            ZStack {
                if busy {
                    ProgressView().tint(p.onPrimary)
                } else {
                    Text(title).font(.vTitleSmall).lineLimit(1)
                }
            }
            .foregroundColor(enabled ? p.onPrimary : p.onSurfaceVariant)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(enabled ? p.primary : p.surfaceVariant, in: Capsule())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled || busy)
    }
}

/// A pill under a field: filled copper for the main move, a copper wash otherwise.
struct FieldButton: View {
    var icon: String
    var text: String
    var filled: Bool
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                Text(text).font(.vLabelLarge)
            }
            .foregroundColor(filled ? p.onPrimary : p.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(filled ? p.primary : p.primary.opacity(p.isDark ? 0.18 : 0.14), in: Capsule())
        }
        .buttonStyle(PressStyle())
    }
}

/// A round button on the page, as in the top bars.
struct RoundIconButton: View {
    var icon: String
    var label: String
    var busy = false
    var filled = false
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            ZStack {
                Circle().fill(filled ? p.surface : Color.clear)
                if busy {
                    ProgressView().tint(p.primary).scaleEffect(0.8)
                } else {
                    Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundColor(p.onSurface)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .accessibilityLabel(label)
    }
}

/// A screen's title on the page itself, with its actions on the right.
struct ScreenHeader<Actions: View>: View {
    var title: String
    @ViewBuilder var actions: () -> Actions
    @Environment(\.palette) private var p

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
                .font(.vTitleLarge)
                .foregroundColor(p.onBackground)
                .lineLimit(1)
            Spacer(minLength: 8)
            actions()
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
    }
}

/// One action in a sheet. A dangerous one is drawn in the error colour.
struct SheetAction: View {
    var icon: String
    var title: String
    var subtitle: String?
    var danger = false
    var action: () -> Void
    @Environment(\.palette) private var p

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(danger ? p.error.opacity(0.12) : p.surfaceVariant)
                    Image(systemName: icon).font(.system(size: 16, weight: .semibold))
                        .foregroundColor(danger ? p.error : p.onSurface)
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.vTitleSmall).foregroundColor(danger ? p.error : p.onSurface)
                    if let subtitle = subtitle, !subtitle.isEmpty { Hint(subtitle) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.98))
    }
}
