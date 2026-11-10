// swift-tools-version:5.9
// SDK BonifiQ para iOS (Swift Package Manager). Use UM dos dois produtos; cada um já traz o core e a UI:
// - BonifiQ: core + UI (hub, cashback, chip de saldo, login por código);
// - BonifiQFitness: o mesmo + o módulo Fitness (Apple Saúde). Exige a capability HealthKit no app.
// O workflow de release (.github/workflows/release.yml) atualiza a versão e os checksums abaixo.
import PackageDescription

let version = "0.4.1"
let bonifiqChecksum = "bcf6aef5f04ba3d9481deba025e9bedbc5414a2e21c3a8a33ad8be24befc5e44"
let bonifiqFitnessChecksum = "bfc4e945bf6d7ccd214c67f48e6ac1fcbe223f7637a7b0936668afbbd0fcba40"

let releases = "https://github.com/bonifiq/bonifiq-sdk/releases/download/\(version)"

let package = Package(
    name: "BonifiQ",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "BonifiQ", targets: ["BonifiQ"]),
        .library(name: "BonifiQFitness", targets: ["BonifiQFitness"]),
    ],
    targets: [
        .binaryTarget(
            name: "BonifiQ",
            url: "\(releases)/BonifiQ.xcframework.zip",
            checksum: bonifiqChecksum
        ),
        .binaryTarget(
            name: "BonifiQFitness",
            url: "\(releases)/BonifiQFitness.xcframework.zip",
            checksum: bonifiqFitnessChecksum
        ),
    ]
)
