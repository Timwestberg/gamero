// swift-tools-version:5.9
import PackageDescription

// Spike 1: boot Home Assistant OS on Apple's Virtualization framework. Not part of the app build.
let package = Package(
    name: "HaVmSpike",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "HaVmSpike",
            path: "Sources/HaVmSpike",
            linkerSettings: [.linkedFramework("Virtualization")]
        )
    ]
)
