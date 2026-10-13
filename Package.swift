// swift-tools-version:5.9
// SDK BonifiQ para iOS (Swift Package Manager). Use UM dos dois produtos; cada um já traz o core e a UI:
// - BonifiQ: core + UI (hub, cashback, chip de saldo, login por código);
// - BonifiQFitness: o mesmo + o módulo Fitness (Apple Saúde). Exige a capability HealthKit no app.
// O workflow de release (.github/workflows/release.yml) atualiza a versão e os checksums abaixo.
import PackageDescription

let version = "0.3.0"
let bonifiqChecksum = "f4360431676c829a9bdde573d338052428b928f27a2306a13ed8a63cd0fee522"
let bonifiqFitnessChecksum = "gerado na primeira release com o Fitness"

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
