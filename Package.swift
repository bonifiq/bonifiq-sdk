// swift-tools-version:5.9
// SDK BonifiQ para iOS (Swift Package Manager). Use UM dos dois produtos; cada um já traz o core e a UI:
// - BonifiQ: core + UI (hub, cashback, chip de saldo, login por código);
// - BonifiQFitness: o mesmo + o módulo Fitness (Apple Saúde). Exige a capability HealthKit no app.
// O workflow de release (.github/workflows/release.yml) atualiza a versão e os checksums abaixo.
import PackageDescription

let version = "0.4.0"
let bonifiqChecksum = "b1933917d9cfa76f1ae1f19ec7fcdee28696194e24ae4995ba463e071032dce4"
let bonifiqFitnessChecksum = "e00061ea781827521062711751bc398611d1fd360e10f46f2ae92cf26cefcb3d"

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
