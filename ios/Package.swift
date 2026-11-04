// swift-tools-version:5.9
// The part of Volta for iOS that needs neither UIKit nor the Xray core: share links,
// subscription bodies, server groups, countries and the Xray configs. The app and the
// VPN extension compile Shared/ directly (see project.yml); this package exists so the
// unit tests run with `swift test --package-path ios` on any Mac, without a simulator.
import PackageDescription

let package = Package(
    name: "VoltaCore",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "VoltaCore", targets: ["VoltaCore"])
    ],
    targets: [
        .target(name: "VoltaCore", path: "Shared"),
        .testTarget(
            name: "VoltaCoreTests",
            dependencies: ["VoltaCore"],
            path: "Tests/VoltaCoreTests"
        )
    ],
    swiftLanguageVersions: [.v5]
)
