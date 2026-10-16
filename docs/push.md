# Módulo Push: guia de integração

Este guia reúne o que é preciso para usar o módulo `bonifiq-push`: a configuração única da loja no Firebase e no
Google Cloud, a integração no app Android e iOS e o que conferir quando a notificação não chega. O texto foi escrito
para virar a página do módulo no developer docs.

O módulo leva ao cliente as notificações push do programa de fidelidade (pontos ganhos, recompensa disponível, novo
nível...). A BonifiQ envia pelo **Firebase Cloud Messaging (FCM) do projeto Firebase da própria loja**, o mesmo que o
app usa (versão 0.5.0).

## Como funciona

```
painel BonifiQ ──► BonifiQ ──► FCM (projeto Firebase da loja) ──► Android / APNs ──► app da loja
 (push ligado,      (envia com a      (o token do aparelho só                        (SDK BonifiQ: registra o
  ID do projeto)     conta de serviço   vale neste projeto)                            aparelho e trata o toque)
                     autorizada)
```

- **Registro do aparelho:** quando o cliente faz login no app, o SDK envia o token FCM do aparelho à BonifiQ,
  identificando o cliente pelo SecureToken. No logout, o SDK remove o aparelho.
- **Só as mensagens da BonifiQ:** elas levam `bq=1` nos dados. O SDK ignora as outras, que continuam com o app.
- **Sem Firebase no framework iOS:** o app usa o Firebase iOS SDK e repassa o token e os toques ao SDK. No Android, o
  módulo depende do `firebase-messaging`, mas quem configura o Firebase é o app (`google-services.json`).

## Configuração única da loja

Faça uma vez por loja, antes de ligar o push no painel.

1. **Projeto Firebase:** se o app já usa Firebase, use **o mesmo projeto**: o token FCM do aparelho só vale no
   projeto do app. Se ainda não usa, crie um em [console.firebase.google.com](https://console.firebase.google.com).
2. **Apps no projeto:** adicione o app Android (pelo `applicationId`) e o app iOS (pelo bundle id). Baixe o
   `google-services.json` e o `GoogleService-Info.plist` e coloque-os no projeto do app.
3. **Chave de APNs (iOS):** no portal da Apple (Certificates, Identifiers & Profiles → Keys), crie uma chave com
   **Apple Push Notifications service (APNs)** e baixe o `.p8`. No Firebase, em Configurações do projeto → Cloud
   Messaging → Configuração do app Apple, envie a chave com o Key ID e o Team ID. Sem ela, nada chega ao iPhone.
4. **Permissão para a BonifiQ enviar:** no [Google Cloud Console](https://console.cloud.google.com), com o projeto
   do Firebase selecionado, abra **IAM e administrador → IAM → Conceder acesso**:
   - **Novos principais:** a conta de serviço da BonifiQ. O e-mail dela aparece no painel BonifiQ, em
     **Integrações → App (Push)**, com um botão para copiar.
   - **Papel:** **Firebase Cloud Messaging API Admin**.
5. **Painel BonifiQ:** em **Integrações → App (Push)**, informe o **ID do projeto** Firebase (em Configurações do
   projeto → Geral; ex.: `minha-loja-app`, não o nome nem o número do projeto), clique em **Testar conexão** e ligue o
   push. O painel só liga o push com a conexão funcionando. Depois, em Comunicação, escolha quais avisos também vão
   por push (todos começam desligados).

## Integração no app

Crie o `BonifiQPush` uma vez, logo depois do `BonifiQ`, e mantenha a instância enquanto o app estiver aberto. O
resto é automático:

| Quando | O que o SDK faz |
| - | - |
| O app abre | Lê a config da loja. Com o push ligado, cria o canal de notificação (Android) e confere o projeto Firebase. |
| O cliente faz login | Registra o token do aparelho para o cliente, com o SecureToken. |
| O token muda | Registra o token novo. O mesmo token e o mesmo cliente não são registrados duas vezes na sessão. |
| A sessão expira | Nada: o aparelho continua do cliente até o logout. |
| O cliente faz logout (`bonifiq.auth.logout()`) | Remove o aparelho, sem SecureToken (ele pode já ter expirado). |
| Chega uma notificação com o app aberto | Android: o SDK mostra a notificação (o FCM só mostra sozinho em segundo plano). iOS: veja `willPresent` abaixo. |
| O cliente toca na notificação | Registra a abertura na BonifiQ e emite em `push.opens`. |

O push só registra o aparelho depois do login no app. Sem login, o cliente não recebe as notificações do programa.

### Android

1. **Dependência:** `implementation("br.com.bonifiq:bonifiq-push:0.5.0")`. Ela traz o `firebase-messaging`.
2. **Firebase no app:** coloque o `google-services.json` no módulo do app e aplique o plugin
   `com.google.gms.google-services` nele. O SDK não aplica o plugin. Sem o Firebase configurado, o SDK avisa no
   Logcat e o push fica desligado. Se o app desliga a inicialização automática do FCM (ex.: até o consentimento do
   cliente), o SDK não pede o token; ele chega quando o app ligar o FCM (`setAutoInitEnabled(true)`) ou na próxima
   abertura.
3. **Instância:**
   ```kotlin
   val bonifiq = BonifiQ(BonifiQConfig(tenant = "SEU_TENANT"))
   val push = BonifiQPush(bonifiq)   // uma por app, junto do BonifiQ; não precisa de Context
   ```
4. **Permissão (Android 13 ou mais novo):** o manifest do módulo já declara `POST_NOTIFICATIONS`. Peça num momento
   que faça sentido para o cliente, por exemplo depois do login ou num botão "Ativar notificações":
   ```kotlin
   when (push.requestPermission()) {
       PushPermissionStatus.Granted -> Unit
       PushPermissionStatus.Denied -> mostrarAtalho { push.openNotificationSettings() }
       PushPermissionStatus.NotDetermined -> Unit
   }
   ```
   - Depois de duas recusas, o Android não mostra mais o pedido; só dá para liberar nas configurações.
   - Antes do Android 13 não há pedido: as notificações vêm ligadas, e `Denied` só aparece se o cliente desligou.
5. **Toque na notificação:** chame `handleIntent` no `onCreate` e no `onNewIntent` da Activity de launcher, que é a
   que a notificação abre. Se ela é só um splash, repasse o `Intent` dela:
   ```kotlin
   override fun onCreate(savedInstanceState: Bundle?) {
       super.onCreate(savedInstanceState)
       if (BonifiQPush.handleIntent(intent)) abrirHub()
   }

   override fun onNewIntent(intent: Intent) {
       super.onNewIntent(intent)
       if (BonifiQPush.handleIntent(intent)) abrirHub()
   }
   ```
   No lugar do retorno, dá para coletar `push.opens` e abrir o hub ali; use um dos dois. O `PushOpen` traz o tipo do
   evento (`type`), um destino sugerido (`target`) e se foi envio de teste (`isTest`).
6. **Ícone e cor:** o SDK usa os mesmos meta-data do FCM, para a notificação ficar igual com o app aberto ou fechado.
   Sem ícone, o Android usa o ícone do app, que aparece como um quadrado branco. Use um ícone monocromático:
   ```xml
   <!-- AndroidManifest.xml do app, dentro de <application> -->
   <meta-data
       android:name="com.google.firebase.messaging.default_notification_icon"
       android:resource="@drawable/ic_notificacao" />
   <meta-data
       android:name="com.google.firebase.messaging.default_notification_color"
       android:resource="@color/cor_da_marca" />
   ```
7. **Canal de notificação:** o SDK cria o canal com o id e o nome que vêm da BonifiQ (padrão `bonifiq_loyalty`,
   "Programa de fidelidade"). O cliente pode silenciar só as notificações do programa nas configurações do app.

#### Se o app já tem um FirebaseMessagingService

O Android entrega as mensagens do FCM a um serviço só. Se o app já tem o próprio serviço, remova o do SDK no manifest
do app e repasse as chamadas:

```xml
<!-- AndroidManifest.xml do app, dentro de <application>; o <manifest> precisa de xmlns:tools -->
<service
    android:name="br.com.bonifiq.sdk.push.BonifiQMessagingService"
    tools:node="remove" />
```

```kotlin
class MeuMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        BonifiQPush.onNewToken(token)
        // o código do app
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (BonifiQPush.onMessageReceived(message)) return   // da BonifiQ: o SDK mostrou a notificação
        // as mensagens do próprio app
    }
}
```

As duas funções funcionam antes de o app criar o `BonifiQPush`.

**Registro por Firebase Installation ID:** o `firebase-messaging` 25.1 marcou `getToken` e `onNewToken` como
obsoletos em favor de um registro opcional por Firebase Installation ID (`onRegistered`, ligado pelo meta-data
`firebase_messaging_installation_id_enabled`). Os dois modos seguem suportados, e a BonifiQ envia pelo **token**. Não
repasse ao SDK o valor de `onRegistered`: é outro identificador, e o envio por ele não funciona.

### iOS

1. **Swift Package Manager:** o push já vem nos produtos `BonifiQ` e `BonifiQFitness`. Não há framework a mais.
2. **Firebase iOS SDK no app:** adicione o pacote `https://github.com/firebase/firebase-ios-sdk` com o produto
   **FirebaseMessaging**, coloque o `GoogleService-Info.plist` no target e chame `FirebaseApp.configure()` ao abrir.
3. **Capability Push Notifications** no target (Signing & Capabilities). Ela adiciona o entitlement
   `aps-environment`, e o App ID precisa ter Push Notifications no portal da Apple. Sem ele, o iOS não entrega o
   token do APNs e nada chega.
4. **Repasse o token e os toques ao SDK.** No `AppDelegate` (num app SwiftUI, pelo `@UIApplicationDelegateAdaptor`):
   ```swift
   import BonifiQ
   import FirebaseCore
   import FirebaseMessaging
   import UserNotifications

   class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {
       func application(_ application: UIApplication,
                        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
           FirebaseApp.configure()
           Messaging.messaging().delegate = self
           UNUserNotificationCenter.current().delegate = self
           application.registerForRemoteNotifications()
           return true
       }

       // Token FCM novo ou atualizado.
       func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
           BonifiQPush.companion.setToken(token: fcmToken)
       }

       // Toque na notificação.
       func userNotificationCenter(_ center: UNUserNotificationCenter,
                                   didReceive response: UNNotificationResponse,
                                   withCompletionHandler completionHandler: @escaping () -> Void) {
           let userInfo = response.notification.request.content.userInfo
           if BonifiQPush.companion.handleNotification(userInfo: userInfo) {
               abrirHub()   // ex.: LoyaltyViewControllerKt.LoyaltyViewController(...)
           }
           completionHandler()
       }

       // Com o app aberto, o iOS só mostra a notificação se o app pedir.
       func userNotificationCenter(_ center: UNUserNotificationCenter,
                                   willPresent notification: UNNotification,
                                   withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
           completionHandler([.banner, .list, .sound])
       }
   }
   ```
   - `setToken` e `handleNotification` funcionam antes de o app criar o `BonifiQPush`: o toque que abriu o app fica
     guardado até a instância existir.
   - Se o app desligou o swizzling do Firebase (`FirebaseAppDelegateProxyEnabled = NO`), ele também precisa entregar
     o token do APNs ao Firebase (`Messaging.messaging().apnsToken = deviceToken`).
5. **Instância e permissão:**
   ```swift
   let push = BonifiQPush(bonifiq: bonifiq)   // guarde junto do BonifiQ
   let status = try await push.requestPermission()   // alerta, som e badge; registra no APNs
   ```
   Depois de uma recusa, o iOS não pergunta de novo: use `push.openNotificationSettings()`.

## Quando a notificação não chega

| Sintoma | O que conferir |
| - | - |
| Logcat (tag `BonifiQPush`): "O app usa o projeto Firebase X, mas a BonifiQ envia... pelo projeto Y" | O `google-services.json` é de outro projeto, ou o ID do projeto no painel está errado. O token só vale no projeto do app: acerte um dos dois. No iOS o SDK não confere; compare o `PROJECT_ID` do `GoogleService-Info.plist` com o painel. |
| Logcat: "O Firebase não está configurado no app" | Falta o `google-services.json` ou o plugin `com.google.gms.google-services` no módulo do app. |
| `permissionStatus()` responde `Denied` | O cliente recusou ou desligou as notificações do app. Mostre um atalho para `openNotificationSettings()`. |
| O FCM recusa o token do aparelho como não registrado (`UNREGISTERED`) | O token mudou: app reinstalado, dados apagados ou backup restaurado em outro aparelho. Abra o app e faça login: o SDK registra o token atual. |
| Nada chega, sem aviso no app | O push está ligado no painel? O cliente fez login **no app** depois de o push ser ligado? A conta de serviço da BonifiQ tem o papel Firebase Cloud Messaging API Admin no projeto certo? |
| Nada chega no iPhone | Confira a chave de APNs no Firebase, a capability Push Notifications (`aps-environment`) e se o app repassa o token (`setToken`). |
| A notificação não aparece com o app aberto (iOS) | Falta o `willPresent` no `UNUserNotificationCenterDelegate`. |
| As mensagens do próprio app pararam de chegar (Android) | O app tem o próprio `FirebaseMessagingService` e não removeu o do SDK. Veja "Se o app já tem um FirebaseMessagingService". |
| O ícone da notificação é um quadrado branco | Defina o meta-data `com.google.firebase.messaging.default_notification_icon` com um ícone monocromático. |

## Próximos passos

- **Levar a uma seção do hub:** a BonifiQ já pode mandar um destino (`PushOpen.target`); por enquanto o app abre o
  hub na tela inicial.
- **Registro por Firebase Installation ID,** quando a BonifiQ passar a enviar por esse identificador.
