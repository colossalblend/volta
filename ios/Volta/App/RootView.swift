import SwiftUI

/// Temporary root while the screens are being written.
struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let p = Palette.resolve(model.settings.theme, systemDark: scheme == .dark)
        VStack(spacing: 16) {
            Text("Volta").font(.vHeadlineMedium).foregroundColor(p.onBackground)
            Text(model.coreVersion).font(.vBodySmall).foregroundColor(p.onSurfaceVariant)
            PrimaryButton(title: "Connect") { model.toggle() }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(p.background.ignoresSafeArea())
        .environment(\.palette, p)
        .task { await model.onLaunch() }
    }
}
