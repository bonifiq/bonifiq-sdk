// swift-tools-version:5.9
// SDK BonifiQ para iOS (Swift Package Manager). O framework é o XCFramework gerado pelo módulo bonifiq-ui (core + UI).
// O workflow de release (.github/workflows/release.yml) atualiza a URL e o checksum a cada versão.
import PackageDescription

let package = Package(
    name: "BonifiQ",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "BonifiQ", targets: ["BonifiQ"]),
    ],
    targets: [
        .binaryTarget(
            name: "BonifiQ",
            url: "https://github.com/bonifiq/bonifiq-sdk/releases/download/0.3.0/BonifiQ.xcframework.zip",
            checksum: "0000000000000000000000000000000000000000000000000000000000000000"
        ),
    ]
)
