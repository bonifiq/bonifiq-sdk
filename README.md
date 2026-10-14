# BonifiQ SDK (Kotlin Multiplatform)

SDK para integrar o programa de fidelidade BonifiQ em apps **Android** e **iOS** sem chamar a API diretamente.
Usa a **API Widget** (`/pub/widget`), feita para o front: autentica um consumidor por vez com um SecureToken, sem
API key privada dentro do app.

| Artefato | O que tem |
| - | - |
| `br.com.bonifiq:bonifiq-core` | API headless: login seguro, saldo, extrato, recompensas, cupons, cashback no checkout, regulamento. |
| `br.com.bonifiq:bonifiq-ui` | Componentes prontos em Compose Multiplatform: hub "Meus pontos" (o widget web da BonifiQ num WebView), cashback do checkout, chip de saldo e login por código. Inclui o core. |
| `br.com.bonifiq:bonifiq-fitness` | **Opcional.** Passos, treinos e batimentos do Health Connect (Android) e do Apple Saúde (iOS), com o fluxo de conexão pronto em Compose. Veja [Fitness](#fitness-opcional). |

Versão atual: `0.4.0`. Targets: Android (minSdk 24), JVM, iOS (arm64 e simulador arm64). Licença: [Apache 2.0](LICENSE).

## Instalação

**Android e Kotlin Multiplatform**, pelo Maven Central:

```kotlin
// build.gradle.kts (módulo KMP ou Android); o repositório mavenCentral() já vem nos projetos novos
implementation("br.com.bonifiq:bonifiq-ui:0.4.0")       // ou só bonifiq-core
implementation("br.com.bonifiq:bonifiq-fitness:0.4.0")  // opcional: dados de atividade física
```

**iOS (Swift)**, pelo Swift Package Manager: no Xcode, **File → Add Package Dependencies** com a URL
`https://github.com/bonifiq/bonifiq-sdk`, ou no `Package.swift`:

```swift
.package(url: "https://github.com/bonifiq/bonifiq-sdk", from: "0.4.0")
```

O pacote tem dois produtos (iOS 15 ou mais novo). Adicione **um** deles ao app, porque os dois juntos duplicam o SDK:

- `BonifiQ`: core e UI.
- `BonifiQFitness`: core, UI e o módulo Fitness. No Swift, `import BonifiQFitness` no lugar de `import BonifiQ`.
  Exige a capability HealthKit no app; veja [Fitness](#fitness-opcional).

## Uso rápido

```kotlin
val bonifiq = BonifiQ(BonifiQConfig(tenant = "SEU_TENANT"))   // uma instância por app

// 1) Login seguro: depois do login do cliente na sua loja VTEX
bonifiq.auth.loginWithVtex {
    // chamado agora e a cada renovação (o SecureToken vale 60 min): devolva a sessão VTEX atual
    VtexSessionTokens(sessionToken, segmentToken)
}
// ...ou, sem plataforma integrada: requestEmailCode(email) + loginWithEmailCode(email, código)

// 2) Hub de fidelidade pronto (Compose)
BonifiQLoyalty(bonifiq, cartId = orderFormId) { event ->
    when (event) {
        is LoyaltyEvent.ApplyCoupon -> carrinho.aplicarCupom(event.couponCode)
        LoyaltyEvent.LoginRequired -> abrirLogin()
        LoyaltyEvent.Close -> voltar()
    }
}

// 3) Cashback no checkout
CashbackCheckoutCard(bonifiq, cartId = orderFormId, cartTotal = totalEmReais, onCartChanged = { recarregarCarrinho() })

// 4) Saldo na top bar
PointsBalanceChip(bonifiq, onClick = { abrirHub() })

// No logout do app
bonifiq.auth.logout()
```

**Sem UI pronta:** use o core direto e desenhe as próprias telas. Todas as funções são `suspend` e lançam só `BonifiQException`:

```kotlin
val cliente = bonifiq.customer.info()                    // saldo, "1.500 Coins", tier
val recompensas = bonifiq.rewards.list()                 // canUse/useReason por cliente
val cupom = bonifiq.rewards.redeem(id, cartId = orderFormId).couponCode
val extrato = bonifiq.customer.history(page = 1)
val cashback = bonifiq.checkout.cashbackOptions(orderFormId, total)
bonifiq.checkout.applyCashback(orderFormId, valor, total) // RedeemOrigin = 3 (checkout mobile)
bonifiq.checkout.refreshCashback(orderFormId)             // sempre que o carrinho mudar
bonifiq.customer.summary                                  // StateFlow com o último saldo conhecido
```

**iOS (Swift)**, com o hub pronto:

```swift
let config = BonifiQConfig(tenant: "SEU_TENANT", baseUrl: BonifiQConfig.companion.DEFAULT_BASE_URL,
                           enableLogging: false, widgetUrl: nil)
let bonifiq = BonifiQ(config: config, httpEngine: nil)
let vc = LoyaltyViewControllerKt.LoyaltyViewController(bonifiq: bonifiq, cartId: orderFormId) { event in
    if let apply = event as? LoyaltyEventApplyCoupon { cart.apply(coupon: apply.couponCode) }
}
```

As funções do core chegam ao Swift como `async throws`. O erro do Kotlin vem no `userInfo["KotlinException"]` do `NSError`:

```swift
@MainActor
func carregarSaldo() async {
    do {
        let cliente = try await bonifiq.customer.info()
        saldo.text = cliente.pointsText
    } catch {
        if (error as NSError).userInfo["KotlinException"] is BonifiQExceptionUnauthorized {
            abrirLogin()
        } else {
            mostrarAviso(error.localizedDescription)
        }
    }
}
```

## Comportamentos importantes

- **SecureToken:** dura 60 min.
  - **Login VTEX:** o SDK renova sozinho aos 55 min e também se a API responder 401.
  - **Login por código:** não há renovação silenciosa, porque exigiria um novo código. Ao expirar, `auth.state` vira `Expired` e a UI pede login.
- **Independente de plataforma:** o SDK não chama a VTEX. Ele recebe os tokens da sessão e o `cartId` e devolve cupons. Quem aplica o cupom e recarrega o carrinho é o app.
- **Cashback:** a BonifiQ aplica o desconto direto no carrinho da plataforma. Depois de `applyCashback` ou `removeCashback`, recarregue o carrinho.
  - O `CashbackCheckoutCard` tem duas versões: `amount = CashbackAmount.All` (padrão) usa todo o disponível num toque; `CashbackAmount.Custom` deixa o cliente digitar o valor, até o disponível.
- **Erros:** as funções públicas só lançam `BonifiQException` e declaram isso em `@Throws`, para o erro chegar ao Swift em vez de encerrar o app. Falhas do provedor de tokens VTEX e respostas que não são JSON também viram `BonifiQException`.
  - `BonifiQException.Unauthorized`: sem sessão ou token recusado;
  - `Api`: mensagem exibível ao cliente;
  - `Network`: falha de conexão.
  - Toda função `suspend` pública nova precisa de `@Throws(BonifiQException::class, CancellationException::class)`. O `ThrowsDeclarationTest` falha se faltar.
- **Textos:** pt-BR por padrão, substituíveis via `LocalBonifiQStrings`. A cor vem do painel BonifiQ (`BonifiQTheme`).

## Hub: o widget web

`BonifiQLoyalty` mostra o widget web da BonifiQ num WebView, com todos os recursos do widget (missões, quiz, níveis,
indicação...) e atualizações sem release do app. O app só trata os `LoyaltyEvent`.

- **URL do widget:** vem do `ClientURL` da loja (`GET /pub/widget/app/config`). `BonifiQConfig.widgetUrl` troca por
  outra URL, para testes.
- **Falha ao carregar** (config da loja indisponível, widget sem resposta em 12 s ou que manda `error`): o hub mostra
  "Não foi possível carregar o programa de fidelidade" com **Tentar novamente**.
- **Sem WebView com ponte segura** (Desktop/JVM, Android sem `WEB_MESSAGE_LISTENER`): o hub avisa que o programa não
  está disponível no aparelho, e o botão **Fechar** emite `LoyaltyEvent.Close`.
- **`cartId`:** reservado; o widget ainda não associa os resgates ao carrinho.

O protocolo entre o app e a página, para quem integra sem o SDK (React Native, Flutter), está em
[docs/webview-bridge.md](docs/webview-bridge.md).

### Strava e outros logins de terceiros no widget web

Desafios com Strava pedem que o cliente conecte a conta dele. No widget web, o SDK abre esse login fora do WebView:
o app do Strava, se estiver instalado, ou a tela de login do sistema por cima do app. Depois, o SDK devolve o
resultado ao widget.

- **Android:** não precisa de nada. O manifest do SDK declara o retorno `bq.<applicationId>://` (e a permissão
  `INTERNET`).
- **iOS:** funciona sem configuração, com a tela de login do sistema (`ASWebAuthenticationSession`). Para abrir o
  **app** do Strava, registre o esquema `bq.<bundle id em minúsculas>` e repasse as URLs ao SDK:

  ```xml
  <!-- Info.plist -->
  <key>CFBundleURLTypes</key>
  <array>
      <dict>
          <key>CFBundleURLSchemes</key>
          <array><string>bq.com.sualoja.app</string></array>
      </dict>
  </array>
  ```

  ```swift
  // SwiftUI (no UIKit: application(_:open:options:) ou scene(_:openURLContexts:))
  .onOpenURL { url in _ = BonifiQAuthRedirect.shared.handle(url: url.absoluteString) }
  ```

  Um `applicationId` ou bundle id com `_` não vira esquema de URL. Nesse caso o widget usa o navegador externo, como
  antes.

## Fitness (opcional)

O módulo `bonifiq-fitness` lê os dados de atividade que o aparelho já reúne. Ele **só lê**: nada é gravado na
plataforma de saúde nem enviado à BonifiQ.

| Plataforma | Fonte | O que chega |
| - | - | - |
| Android 9+ | [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) | Samsung Health, Fitbit, Mi Fitness (Xiaomi), Zepp (Amazfit), Garmin Connect, Google Fit, Strava e outros apps que sincronizam com o Health Connect |
| iOS 15+ | Apple Saúde (HealthKit) | iPhone, Apple Watch e apps conectados ao Saúde (Garmin, Strava, Zepp, Mi Fitness...) |

Huawei Health não grava no Health Connect e fica fora por enquanto. No Android em português, o Health Connect aparece como **Conexão Saúde**, e os textos do
componente usam esse nome.

**Fluxo pronto (Compose).** Conectar a atividade tem mais passos do que parece: instalar o Health Connect
(Android 9 a 13), dar a permissão e, principalmente, **ligar o envio de dados no app do relógio ou da pulseira**.
O cliente raramente sabe fazer esse último. O `FitnessConnect` cuida de tudo isso e só mostra o conteúdo do app
quando há dados:

```kotlin
val fitness = BonifiQFitness()   // uma instância por app; não precisa de Context

FitnessConnect(fitness) { dados ->            // dados.steps (7 dias) e dados.workouts (30 dias)
    MeuResumoDeAtividade(dados.steps, dados.workouts)
}
```

| Situação | O que o `FitnessConnect` mostra |
| - | - |
| Sem plataforma de saúde (desktop, Android 8) | Aviso de indisponível |
| Android 9 a 13 sem Health Connect | Botão para instalar pela Play Store |
| Ainda não conectado | Botão **Conectar**, que abre o pedido do sistema |
| Recusou o pedido | Atalho para liberar no Health Connect (depois de duas recusas o Android não pergunta mais) |
| Conectado, mas sem dados | **"Falta 1 passo"**: para cada app de saúde instalado (Samsung Health, Mi Fitness, Zepp, Garmin Connect, Fitbit...), o caminho para ligar o envio ao Health Connect e um botão que abre o app |
| Acesso parcial | Aviso acima do conteúdo, com **Gerenciar permissões** |

- **Recarga:** os dados são lidos de novo quando o cliente volta ao app, por exemplo depois de ligar o envio no
  Samsung Health.
- **Estado próprio:** com `rememberFitnessConnectState(fitness)`, o app pode chamar `refresh()` (ex.: botão na top
  bar) e ler `refreshing`.
- **Textos:** vêm de `LocalFitnessStrings`.

**Sem o componente**, use a API direto:

```kotlin
when (fitness.availability()) {
    FitnessAvailability.Available -> Unit
    FitnessAvailability.NeedsInstall -> fitness.openHealthAppInstall()   // Android 9 a 13: Health Connect na Play Store
    FitnessAvailability.Unsupported -> return                            // ex.: Android 8, desktop
}
fitness.requestPermissions()                    // num gesto do cliente ("Conectar"); Granted, Partial ou NotGranted
val passos = fitness.dailySteps(lastDays = 7)   // um item por dia, com 0 nos dias sem dado
val treinos = fitness.workouts(lastDays = 30)   // do mais recente ao mais antigo
```

**O que vem em cada treino (`Workout`):**
- tipo (corrida, caminhada, bike, natação, musculação...), início e fim, distância e calorias ativas;
- `heartRate`: média, mínima e máxima dos batimentos gravados **pelo mesmo app/relógio** durante o treino;
- `hasRoute`: se há trajeto de GPS;
- `source`: o app (`appId`, `appName`) e o aparelho (relógio, pulseira, celular...);
- `recordingMethod`: `ActivelyRecorded` (o cliente iniciou o treino), `Automatic`, `Manual` (digitado à mão) ou
  `Unknown`.

Quando a leitura volta vazia, `installedHealthApps()` lista os apps de saúde instalados (Android), cada um com o passo
a passo para ligar o envio ao Health Connect, e `openHealthApp(app)` abre o app.

Origem, GPS, batimentos e lançamento manual servem de base para validar desafios (anti-fraude).

**Sobre os dados:**
- **Passos por dia:** a plataforma soma os passos sem contar em dobro o que vem de vários apps.
- **Sem permissão:** a leitura volta vazia, com os passos em 0 e os campos do treino em `null`.
- **iOS:** a Apple não informa se o cliente negou a leitura. Por isso, depois do pedido, `permissionStatus()` responde
  `Granted` mesmo quando os dados vão chegar vazios.
- **Erros:** as funções lançam só `FitnessException`:
  - `Unavailable`: sem plataforma de saúde;
  - `Platform`: falha ou limite de consultas.

  Elas declaram isso em `@Throws`, como o core.

### Configuração no Android

- **Visibilidade de pacotes:** o manifest do módulo declara em `<queries>` os apps de saúde conhecidos. Sem isso, o
  SDK não saberia quais estão instalados.
- **Manifest:** o do módulo já declara as permissões de leitura (`android.permission.health.READ_STEPS`,
  `READ_EXERCISE`, `READ_DISTANCE`, `READ_ACTIVE_CALORIES_BURNED`, `READ_HEART_RATE`) e as telas que o Health Connect
  exige.
- **Política de privacidade:** o app só precisa informar a URL dela, que o Health Connect mostra no pedido de
  permissão. Sem ela, o pedido não aparece:

  ```xml
  <!-- AndroidManifest.xml do app, dentro de <application> -->
  <meta-data
      android:name="br.com.bonifiq.sdk.fitness.PRIVACY_POLICY_URL"
      android:value="https://sualoja.com.br/privacidade" />
  ```

- **minSdk:** o módulo funciona com minSdk 24. Abaixo do Android 9, `availability()` responde `Unsupported`.
- **Inicialização:** o contexto do app é capturado pelo `androidx.startup`. Se o app desliga o
  `InitializationProvider`, `availability()` responde `Unsupported`.
- **Play Console:** apps com permissões do Health Connect precisam preencher a **declaração de apps de saúde**
  (Conteúdo do app → Apps de saúde) e justificar cada permissão. Também precisam de política de privacidade
  publicada e da seção de Segurança dos dados preenchida.

### Configuração no iOS

- Ative a capability **HealthKit** no target (Signing & Capabilities). Ela adiciona o entitlement
  `com.apple.developer.healthkit`, e o App ID precisa ter HealthKit no portal da Apple.
- No `Info.plist`, adicione `NSHealthShareUsageDescription` com o motivo da leitura. Sem esse texto, o SDK lança
  `FitnessException.Unavailable` em vez de deixar o HealthKit encerrar o app.
- Apps Swift usam o produto `BonifiQFitness` do Swift Package Manager:

  ```swift
  import BonifiQFitness

  let fitness = BonifiQFitness()
  let status = try await fitness.requestPermissions()
  let passos = try await fitness.dailySteps(lastDays: 7)
  ```

## Desenvolvimento

```bash
./gradlew :bonifiq-core:jvmTest :bonifiq-ui:jvmTest :bonifiq-fitness:jvmTest
./gradlew publishToMavenLocal
```

- **Testes de UI:** salvam screenshots em `bonifiq-ui/build/screenshots`.
- **Smoke contra a API real:** use `BONIFIQ_SMOKE=1` (só endpoints públicos).
- **`publishToMavenLocal`:** publica a versão do `libs.versions.toml` no Maven local, sem assinar. Serve para testar o SDK num app (ex.: o app de demonstração) antes da release.
- **CI** (`.github/workflows/ci.yml`): em todo PR, roda os testes e a compilação Android e, num Mac, faz o link dos frameworks iOS (`BonifiQ` e `BonifiQFitness`).

## Como publicar uma versão

1. Num PR, suba a versão em `gradle/libs.versions.toml` (`bonifiq-sdk`) e em `BonifiQ.VERSION`. O SDK segue
   versionamento semântico; antes da 1.0, uma versão `0.x` nova pode trazer mudanças incompatíveis.
2. Depois do merge, rode **Actions → Release → Run workflow** com a mesma versão. O workflow, num Mac:
   - roda os testes;
   - assina e envia todos os alvos ao Maven Central;
   - gera os XCFrameworks `BonifiQ` e `BonifiQFitness`, atualiza o `Package.swift` (versão e checksums), commita na
     `main` e cria a tag;
   - cria a Release no GitHub com os dois `.xcframework.zip`.
3. Em [central.sonatype.com](https://central.sonatype.com) → **Deployments**, confira e clique em **Publish**.
   Se o workflow falhar depois do envio ao Central (ex.: nos XCFrameworks), rode de novo com **publish_maven**
   desmarcado: ele refaz só XCFrameworks, `Package.swift`, tag e Release, e a versão já enviada segue no Central. A versão
   aparece no Maven Central em alguns minutos.

Credenciais (segredos do repositório): `MAVEN_CENTRAL_USERNAME` e `MAVEN_CENTRAL_PASSWORD` (token do Central Portal),
`SIGNING_KEY` e `SIGNING_KEY_PASSWORD` (chave PGP `E232C526740A86B7`, publicada em keyserver.ubuntu.com).

## Licença

Copyright 2026 Bonifiq Desenvolvimento e Licenciamento LTDA. Licenciado sob a [Apache License, Version 2.0](LICENSE). O uso da plataforma BonifiQ continua
sujeito ao contrato e aos termos de uso de cada loja.
