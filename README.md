# BonifiQ SDK (Kotlin Multiplatform)

SDK para integrar o programa de fidelidade BonifiQ em apps **Android** e **iOS** sem chamar a API diretamente.
Usa a **API Widget** (`/pub/widget`), feita para o front: autentica um consumidor por vez com um SecureToken, sem
API key privada dentro do app.

| Artefato | O que tem |
| - | - |
| `br.com.bonifiq:bonifiq-core` | API headless: login seguro, saldo, extrato, recompensas, cupons, cashback no checkout, regulamento. |
| `br.com.bonifiq:bonifiq-ui` | Componentes prontos em Compose Multiplatform: hub "Meus pontos" (o widget web da BonifiQ num WebView), cashback do checkout, chip de saldo e login por código. Inclui o core. |

Versão atual: `0.3.0`. Targets: Android (minSdk 24), JVM, iOS (arm64 e simulador arm64). Licença: [Apache 2.0](LICENSE).

## Instalação

**Android e Kotlin Multiplatform**, pelo Maven Central:

```kotlin
// build.gradle.kts (módulo KMP ou Android); o repositório mavenCentral() já vem nos projetos novos
implementation("br.com.bonifiq:bonifiq-ui:0.3.0")   // ou só bonifiq-core
```

**iOS (Swift)**, pelo Swift Package Manager: no Xcode, **File → Add Package Dependencies** com a URL
`https://github.com/bonifiq/bonifiq-sdk`, ou no `Package.swift`:

```swift
.package(url: "https://github.com/bonifiq/bonifiq-sdk", from: "0.3.0")
```

O pacote traz o XCFramework `BonifiQ`, com o core e a UI (iOS 15 ou mais novo).

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

## Desenvolvimento

```bash
./gradlew :bonifiq-core:jvmTest :bonifiq-ui:jvmTest
./gradlew publishToMavenLocal
```

- **Testes de UI:** salvam screenshots em `bonifiq-ui/build/screenshots`.
- **Smoke contra a API real:** use `BONIFIQ_SMOKE=1` (só endpoints públicos).
- **`publishToMavenLocal`:** publica a versão do `libs.versions.toml` no Maven local, sem assinar. Serve para testar o SDK num app (ex.: o app de demonstração) antes da release.
- **CI** (`.github/workflows/ci.yml`): testes e compilação Android em todo PR, e o link do framework iOS num Mac.

## Como publicar uma versão

1. Num PR, suba a versão em `gradle/libs.versions.toml` (`bonifiq-sdk`) e em `BonifiQ.VERSION`. O SDK segue
   versionamento semântico; antes da 1.0, uma versão `0.x` nova pode trazer mudanças incompatíveis.
2. Depois do merge, rode **Actions → Release → Run workflow** com a mesma versão. O workflow, num Mac:
   - roda os testes;
   - assina e envia todos os alvos ao Maven Central;
   - gera o XCFramework, atualiza o `Package.swift` (URL e checksum), commita na `main` e cria a tag;
   - cria a Release no GitHub com o `BonifiQ.xcframework.zip`.
3. Em [central.sonatype.com](https://central.sonatype.com) → **Deployments**, confira e clique em **Publish**. A versão
   aparece no Maven Central em alguns minutos.

Credenciais (segredos do repositório): `MAVEN_CENTRAL_USERNAME` e `MAVEN_CENTRAL_PASSWORD` (token do Central Portal),
`SIGNING_KEY` e `SIGNING_KEY_PASSWORD` (chave PGP `E232C526740A86B7`, publicada em keyserver.ubuntu.com).

## Licença

Copyright 2026 Bonifiq Desenvolvimento e Licenciamento LTDA. Licenciado sob a [Apache License, Version 2.0](LICENSE). O uso da plataforma BonifiQ continua
sujeito ao contrato e aos termos de uso de cada loja.
