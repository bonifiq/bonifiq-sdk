# Ponte WebView do hub de fidelidade

> Contrato entre o app e o widget da BonifiQ no modo app. No SDK, a ponte fica em `bonifiq-ui/.../web/`, e o código
> dos apps parceiros é só `BonifiQLoyalty(bonifiq) { evento -> … }`. Apps sem o SDK (React Native, Flutter) implementam
> as mesmas mensagens.

## Por que

O hub ("Meus pontos") é a parte do programa que mais muda: missões, quiz, tiers, indicação, aniversário,
campanhas e layout. Mantendo o hub no widget web:

- uma mudança chega a todos os apps **sem nova versão nas lojas**;
- o hub tem todos os recursos do widget, e não só os reimplementados no SDK;
- funciona igual em apps nativos, React Native e Flutter.

O checkout (cashback) e o chip de saldo continuam nativos no SDK, porque precisam conversar com o carrinho do app.

## Como o SDK abre o hub

`BonifiQLoyalty` resolve o hub a cada exibição (`resolveHub`):

1. **Plataforma:** sem WebView com ponte segura (Desktop/JVM, Android sem `WEB_MESSAGE_LISTENER`), o hub mostra
   "não disponível neste aparelho" e um **Fechar**, que emite `LoyaltyEvent.Close`.
2. **URL do widget:** `BonifiQConfig.widgetUrl` ou, na falta dela, o `ClientURL` de `GET /pub/widget/app/config`.
   Esse endpoint é público, fica em cache por instância e identifica a loja só pelo `X-Bq-Tenant`.
3. **Falha:** se não houver URL, ou se a página não mandar `ready` em **12 s**, ou mandar `error`, o hub mostra
   "Não foi possível carregar o programa de fidelidade" com **Tentar novamente**, que resolve e recria o WebView.

| `BonifiQConfig` | Efeito |
| - | - |
| `widgetUrl = "https://…"` | Usa outro endereço do widget no lugar do `ClientURL` da loja (ex.: em testes). |

O `AppEmbeddedHubEnabled` que o endpoint ainda devolve não é mais usado pelo SDK: toda loja abre o widget.

## Endpoint de configuração

`GET {api}/pub/widget/app/config` (header `X-BQ-Tenant`, sem SecureToken). O formato é o envelope padrão
`{HasError, Message, Item}`.

O `Item` traz os **mesmos valores que o loader (`bqloader.js`) injeta no widget**, gerados pelo
`ApiScriptGenerator.BuildAppConfig` com as mesmas substituições do script:

- `ClientURL`, `ApiUrl`, `Platform`, `TenantKey`;
- `ProgramName`, `CashbackName`;
- `WidgetColor`, `FontFamily`, `FontUrl`, `WidgetDisplay`, `WidgetHideRewards`, `HideHomeObjectives`, `WidgetCustomization`;
- `LocaleCurrency`, `LocaleLanguage`;
- `PointSingularName`, `PointPluralName`;
- `TenantUsePoints`, `TenantUseCashback`, `CashbackMultiplier`;
- `UseWidgetV2`, `AffiliatesEnabled`;
- e mais **`AppEmbeddedHubEnabled`**.

Ficam de fora o checkout, o copiloto e o `ExtraContent`, que são da loja web.

Tipos dos campos, iguais aos do loader:

- `HideHomeObjectives` e `UseWidgetV2` são booleanos;
- os outros valores escalares chegam como texto;
- `WidgetCustomization` é um objeto;
- tokens sem valor (`@@X@@`) viram `null`.

O teste `BuildAppConfig_MatchesValuesRenderedInLoaderScript` garante que o endpoint e o script não divergem.

## Página no modo app

- **URL:** `{ClientURL}/?bqapp=1&tenant=<TenantKey>[&api=<host da API>]`.
  - O `api` só vai quando o app não usa a API padrão (`https://api.bonifiq.com.br`).
  - O widget aceita apenas `https` em domínios da BonifiQ. Loopback só em build de dev.
  - **Nada de token na URL**, porque URLs vão parar em logs, histórico e analytics.
- **Carregamento:**
  - o widget lê os parâmetros uma vez, só no frame principal, e guarda tenant e API em `sessionStorage` para sobreviver a recargas;
  - busca a config acima e monta o mesmo `post` que o loader montaria, com `UseWidgetV2` forçado e o login externo desligado;
  - fica sempre aberto e em tela cheia, e manda `ready` quando está pronto;
  - se a config não chega em **10 s**, ou falha, manda `error` (a falha vai também para o Sentry);
  - com `bqapp=1` e tenant ou API inválidos, manda `error` na hora e descarta o que estava guardado na sessão.
- **Sem cliente:** antes do `auth`, mostra o conteúdo público (recompensas e regulamento). Os CTAs de login viram `loginRequired`.
- **Visual:** cores, textos e ícones continuam vindo do painel BonifiQ.

## Mensagens

Todas são JSON com `type`. A página ignora qualquer coisa fora do contrato, e o SDK também.

### Página → app

| `type` | Campos | O que o SDK faz |
| - | - | - |
| `ready` | `version` (hoje `1`) | Responde `capabilities` (se a plataforma tiver) e `auth` se houver cliente logado. |
| `applyCoupon` | `couponCode` | Emite `LoyaltyEvent.ApplyCoupon(couponCode, redeemedId = null)`, e o app aplica o cupom no carrinho. |
| `loginRequired` | — | Emite `LoyaltyEvent.LoginRequired`, e o app abre o login. |
| `tokenExpired` | — | Renova o token (veja abaixo) e responde `tokenRefreshed`, ou `logout` + `LoginRequired`. |
| `openUrl` | `url` | Abre no navegador externo. Só `http(s)`. |
| `openAuth` | `requestId`, `url`, `appUrl?` | Abre o login de terceiros (ex.: Strava) e responde `authResult`. Veja [Login de terceiros](#login-de-terceiros-ex-strava). |
| `close` | — | Emite `LoyaltyEvent.Close`. |
| `error` | `message` | O hub mostra a falha com **Tentar novamente**. Exemplo: config da loja indisponível. |

Mensagens que o widget já mandava ao loader e como ficam no modo app:

| Mensagem do widget | Vira |
| - | - |
| `widget hide`, `buy-now` | `close` |
| `login redirect url`, `sign up redirect url` | `loginRequired` |
| `widget apply coupon` | `applyCoupon`. No modo app, cada cupom resgatado ganha o botão **"Usar no carrinho"**. |
| `open url`, `open oauth popup` | `openUrl` (só `http(s)`) |
| `open bonifiq` | `openUrl` para `https://www.bonifiq.com.br` |

As mensagens próprias do iframe (estilo, backdrop, logout em todos os iframes) não se aplicam ao modo app.

### App → página

| `type` | Campos | Quando |
| - | - | - |
| `capabilities` | `authSession: {callbackScheme}` | Logo depois de `ready`, se o app consegue abrir o login de terceiros. Sem ela, a página usa o fluxo antigo, pelo navegador. |
| `auth` | `secureToken`, `customer: {email, name, id}` | Depois de `ready` com cliente logado, ou quando o cliente faz login com o hub aberto. Um `auth` antes do `ready` é descartado. |
| `tokenRefreshed` | `secureToken` | Resposta a `tokenExpired`. |
| `logout` | — | O cliente saiu do app, ou o token não pôde ser renovado. |
| `authResult` | `requestId`, `status` (`success`, `cancelled`, `error`), `url?`, `message?` | Fim do `openAuth` com o mesmo `requestId`. Em `success`, `url` é a URL de retorno do provedor, como veio. |

O `customer.id` é o `UserId` do login, e o `customer.name` é o nome. Se algum deles não vier (login por código),
vai o e-mail. A página usa esses dados para o `/account/login` do widget, igual ao cliente que o loader monta. O
backend exige o `Name` no corpo e responde 400 sem ele.

No modo app, o widget marca o cliente com `AppMode: true` no `X-BQ-Params`, junto do `SecureToken`. Com essa marca
e um SecureToken válido, o `/account/login` devolve o cliente **do token** e ignora e-mail e `UserId` do corpo.
Por isso o login por código funciona, mesmo sem o `UserId` da plataforma, e não cria um cliente duplicado.

Sem a marca, como no widget aberto pelo loader da loja, o login continua pelo corpo, como sempre. Forjar a marca
não dá acesso a nada, porque ela só vale junto de um token válido.

### Transporte

| Plataforma | Página → app | App → página |
| - | - | - |
| Android (SDK) | `window.BonifiQNative.postMessage(json)`, injetado por `WebViewCompat.addWebMessageListener` | `replyProxy.postMessage(json)`, que chega em `BonifiQNative.onmessage` |
| iOS (SDK) | `window.webkit.messageHandlers.bonifiq.postMessage(json)`, **sempre texto JSON** | `evaluateJavaScript("window.BonifiQEmbed.receive(<json>)")` |
| React Native | `window.ReactNativeWebView.postMessage(json)` | `injectJavaScript("window.BonifiQEmbed.receive(<json>)")` |
| Flutter | `JavaScriptChannel` com o nome `BonifiQNative` | `runJavaScript("window.BonifiQEmbed.receive(<json>)")` |

A página procura os canais nessa ordem: `BonifiQNative`, depois `webkit`, depois `ReactNativeWebView`.
`window.BonifiQEmbed.receive` aceita texto JSON ou objeto.

### Exemplo de sequência

```
SDK  → abre https://widget…/?bqapp=1&tenant=…
web  → {"type":"ready","version":1}
SDK  → {"type":"auth","secureToken":"…","customer":{"email":"…","name":"…","id":"…"}}
web  → (cliente resgata "R$ 12,00" e toca "Usar no carrinho")
web  → {"type":"applyCoupon","couponCode":"BQ-123"}
SDK  → LoyaltyEvent.ApplyCoupon("BQ-123", null) → app aplica no carrinho
```

## Login de terceiros (ex.: Strava)

Conectar o Strava num desafio de corrida é um OAuth. Dentro de um WebView ele não funciona bem:
- o Google bloqueia "Entrar com Google" em WebView;
- a boa prática (RFC 8252) é usar o navegador do sistema.

Por isso a página pede ao app:

1. **Capacidade.** O SDK anuncia `capabilities.authSession.callbackScheme`, que é `bq.<id do app>`: `applicationId` no Android, bundle id em minúsculas no iOS. Um id que não vira um esquema seguro (com `_`, ou com maiúsculas no Android) não anuncia nada, e a página segue pelo navegador.
2. **URLs.** A página pede ao backend `GET /pub/widget/strava/app-authorization-url?callbackScheme=…&fitnessChallengeId=…`. O backend devolve:
   - `AuthorizationUrl`: `https://www.strava.com/oauth/mobile/authorize?…`;
   - `AppAuthorizationUrl`: `strava://oauth/mobile/authorize?…`.

   O `redirect_uri` é `<callbackScheme>://<domínio de callback do Strava>/strava`.
3. **`openAuth`.** A página manda `{requestId, url, appUrl}`, e o SDK abre:
   - **Android:** o app do Strava pelo próprio `url` (App Link, com `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`, Android 11+). Sem o app, uma Custom Tab. O retorno chega na `BonifiQAuthRedirectActivity`, declarada no manifest do SDK com o esquema `bq.${applicationId}`. **O app host não configura nada.**
   - **iOS:** o `appUrl` (app do Strava), **se** o app host registrou o esquema em `CFBundleURLTypes` e repassa as URLs com `BonifiQAuthRedirect.shared.handle(url:)`. Sem isso, ou se o app do Strava não estiver instalado, abre o `ASWebAuthenticationSession`, que não exige configuração e usa a sessão do Safari.
4. **`authResult`.** O SDK devolve a URL de retorno (`?code=…&state=…&scope=…`, ou `?error=access_denied`). Também devolve `cancelled` se o cliente voltar sem concluir. Um `openAuth` novo cancela o anterior.
5. **Fim.** A página manda `code`, `state` e `scope` para `POST /pub/widget/strava/app-authorization/complete`. O backend confere que o `state` é do cliente logado, troca o código (o `client_secret` só existe no backend) e liga o atleta.

O código de autorização nunca passa por uma página web: vai do Strava para o app, e do app para o backend.

**Segurança:**
- O `url` precisa ser `https`.
- O `appUrl` só pode ser o esquema de outro app; o SDK recusa `http(s)`, `javascript`, `data`, `file`, `content`, `intent` e o próprio esquema de retorno.
- O SDK só aceita retorno com o esquema dele.

## Renovação do token

O SecureToken vale 60 minutos.

1. **Widget:** quando a API responde 401, ele manda `tokenExpired` e espera até **10 s**. Chamadas concorrentes
   compartilham o mesmo pedido.
2. **SDK:** renova pelo core.
   - Login VTEX: chama de novo o `securelogin` com a sessão VTEX e responde `tokenRefreshed`.
   - Login por código de e-mail: não dá para renovar sem um código novo. O SDK responde `logout` e emite
     `LoginRequired`, e o estado do SDK vira `Expired`.
3. **Widget:** com `tokenRefreshed`, refaz **uma vez** a chamada que falhou. Sem resposta, ou com uma segunda
   falha, faz logout e volta ao modo anônimo.
4. **Logout do app no meio da renovação:** quem esperava o token desiste na hora, sem aguardar os 10 s.

## Segurança

- **Origem:** a ponte só existe para a origem do widget, e só no frame principal.
  - **Android:** allowlist do `addWebMessageListener` mais o filtro `isMainFrame`.
  - **iOS:** checa `frameInfo.isMainFrame` e o host de `securityOrigin`. As mensagens do app, que levam o token,
    só saem se o WebView ainda estiver no host do widget.
- **Navegação:** links do frame principal para outro domínio abrem fora do WebView (`openUrl` / navegador do
  sistema). No Android isso fica no `shouldOverrideUrlLoading`, e no iOS no `WKNavigationDelegate`.
- **API:** o widget recusa um `api` que não seja da BonifiQ, para não mandar o token a terceiros.
- **Token:** o SecureToken fica só no estado em memória da página. Não vai para `localStorage`, cookies nem URL.
  O escopo dele é o próprio cliente.
- **Escapes:** o app nunca interpola JSON cru em JavaScript. O SDK usa um literal de string escapado (`jsStringLiteral`).

## Testando

- **Testes automatizados do SDK:** `WebHubTest` (protocolo da ponte e resolução do hub) e `AuthSessionTest` (login de
  terceiros).
- **Num aparelho:** use o app de demonstração. `BonifiQConfig.widgetUrl` e `baseUrl` apontam para outro endereço do
  widget ou da API, quando o time BonifiQ indicar um ambiente de testes.

## Pendências

- **Login de terceiros:** tem testes da ponte, mas falta testar num aparelho, com e sem o app do Strava instalado.
- **iOS:** o CI liga o framework num Mac, mas o hub ainda não foi testado num aparelho.
- **Não implementado:**
  - `cartContext`, para resgates já associados ao carrinho;
  - `height`, para o hub exibido como bloco.
  - O contrato reserva os dois nomes.
- **Versão do contrato:** o campo `version` do `ready` permite evoluir o contrato sem quebrar SDKs antigos.
