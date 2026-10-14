# Módulo Fitness: guia de integração e o que aprendemos

Este guia reúne o que é preciso para usar o módulo `bonifiq-fitness` em produção e o que descobrimos testando em
aparelhos reais (Galaxy S23 com Samsung Health, iPhone). O texto foi escrito para virar a página do módulo no
developer docs.

O módulo **só lê** dados de atividade que o aparelho já reúne. Nada é gravado na plataforma de saúde nem enviado à
BonifiQ (versão 0.4.0).

## Como os dados chegam ao app

```
relógio / pulseira ──► app do fabricante ──► plataforma de saúde do celular ──► SDK BonifiQ ──► app da loja
 (Galaxy Watch,         (Samsung Health,      (Android: Conexão Saúde /          (lê, só com
  Mi Band, Amazfit,      Mi Fitness, Zepp,     Health Connect;                    permissão)
  Garmin, Apple Watch)   Garmin Connect...)    iOS: app Saúde / HealthKit)
```

O SDK nunca fala com o relógio nem com o app do fabricante. Ele lê da plataforma de saúde do sistema. Daí vem a
regra mais importante deste guia:

> **No Android, o cliente precisa ligar o envio de dados no app do fabricante.** Sem isso, a Conexão Saúde fica
> vazia, mesmo com o relógio pareado e o app da loja autorizado. No iPhone isso não acontece: o próprio iPhone conta
> os passos e o Apple Watch grava os treinos direto no Saúde.

O componente `FitnessConnect` existe por causa dessa regra. Ele detecta o app de saúde instalado e mostra ao cliente
como ligar o envio.

## Cobertura por fabricante (out/2026)

| Relógio / app | Android (Conexão Saúde) | iPhone (Saúde) |
| - | - | - |
| Samsung Galaxy Watch / Samsung Health | ✅ envia passos, exercícios e batimentos; só **dados novos** | – |
| Xiaomi / Redmi (Mi Fitness) | ✅ desde jun/2025; exige a sincronização com a nuvem da Xiaomi ligada | ✅ pelo app |
| Amazfit (Zepp) | ✅ envia passos, batimentos, treinos e outros | ✅ pelo app |
| Garmin Connect | ✅ desde jul/2025 (só envia, não lê) | ✅ pelo app |
| Fitbit / Google Health | ✅ o app Fitbit virou Google Health em mai/2026 | – |
| Strava | ✅ só atividades com GPS (não envia passos) | ✅ |
| Polar, Oura, Withings | ✅ (a Withings pode não enviar passos) | ✅ |
| Apple Watch | – | ✅ automático |
| Huawei Health | ❌ não envia à Conexão Saúde | ✅ pelo app |
| Zepp Life (Mi Band antigo) | ❌ só via Google Fit, que acaba em 2026 | – |
| Google Fit | ⚠️ funciona, mas o app sai de linha no fim de 2026 | – |

**Fora do v1:**
- O Huawei Health Kit exige conta de desenvolvedor Huawei e aprovação.
- O Samsung Health Data SDK exige parceria com a Samsung. Ele leria o **histórico** direto do Samsung Health,
  mas só funciona em modo desenvolvedor sem a aprovação.

## Comportamentos que surpreendem (e como o SDK lida)

| O que acontece | Detalhe | O que o SDK faz |
| - | - | - |
| **No Android em português, o nome é "Conexão Saúde"** | Ninguém acha "Health Connect" nas Configurações de um celular em pt-BR. | Todos os textos usam "Conexão Saúde" (com "Health Connect" entre parênteses onde ajuda). |
| **O histórico anterior não vem** | O Samsung Health documenta só o envio de dados novos ou alterados depois que o envio é ligado. Outros apps fazem igual. | A tela "Aguardando seus dados" avisa que contam as atividades a partir de agora. |
| **O envio atrasa até ~1 hora** | O Samsung Health manda em lotes, a cada 30–60 min ou ao ser aberto. | "Aguardando seus dados" recarrega a cada minuto e quando o app volta ao primeiro plano. Ela lembra por 24 h que o cliente ligou o envio. |
| **Limite de 30 dias** | Sem a permissão extra de histórico (`READ_HEALTH_DATA_HISTORY`), um app só lê dados de até 30 dias antes da primeira permissão. | Os períodos padrão são 7 dias de passos e 30 de treinos. |
| **Duas recusas e o Android não pergunta mais** | Depois de duas recusas, o pedido de permissão não aparece de novo. | Depois de uma recusa, mostra o atalho "Abrir a Conexão Saúde" para liberar lá. |
| **Sem a política de privacidade, não há pedido** | A Conexão Saúde só mostra o pedido de permissão se o app tiver a tela de política de privacidade. | O manifest do SDK declara a tela e o alias exigidos. O app só informa a URL na meta-data. |
| **Android 9 a 13** | Lá a Conexão Saúde é um app da Play Store; a partir do Android 14 ela vem no sistema. | `availability()` responde `NeedsInstall`, e o componente leva à Play Store. |
| **iOS não revela recusa de leitura** | Por privacidade, a Apple não diz se o cliente negou a leitura: os dados só voltam vazios. | `permissionStatus()` responde `Granted` depois do pedido. A tela sem dados ensina a liberar em Saúde → foto → Apps. |
| **Passos de várias fontes** | Celular e relógio contam os mesmos passos. | Usa a agregação da plataforma, que não soma em dobro. |
| **Lançamento manual** | O cliente pode digitar um treino à mão na Conexão Saúde ou no Saúde. | `recordingMethod = Manual`, para os desafios poderem recusar. |
| **Contagem de passos pelo próprio celular** | Desde 2025/2026, a Conexão Saúde pode contar passos sozinha no Android 14+ com o módulo atualizado. Não confirmamos isso em aparelhos Samsung. | Nada a fazer: esses passos entram na mesma agregação. |

## O passo a passo que o cliente segue (Android)

O `FitnessConnect` mostra o caminho do app instalado. Os da Samsung, Strava, Polar, Oura e Google vêm das ajudas
oficiais. Os de Mi Fitness, Zepp e Garmin vêm de guias de terceiros: revise quando os apps mudarem os menus. A lista
está em `bonifiq-fitness/src/androidMain/.../HealthAppCatalog.kt`.

| App | Caminho |
| - | - |
| Samsung Health | ⋮ (Mais opções) → Configurações → Conexão Saúde → permitir tudo. Deixe ligada a sincronização com a conta Samsung. |
| Mi Fitness | Perfil → Dados de terceiros → Conexão Saúde → Conectar. Deixe ligada a sincronização com a nuvem. |
| Zepp | Perfil → Vincular contas de terceiros → Conexão Saúde. |
| Garmin Connect | ☰ Mais → Configurações → Conexão Saúde (às vezes dentro de Apps conectados). |
| Google Health (Fitbit) | Conexões → Apps de parceiros → "Sincronize seus apps de saúde" → Configurar. |
| Strava | Você → ⚙ → Gerenciar aplicativos e dispositivos → Conexão Saúde. |

Ao abrir o app de saúde, o SDK só consegue abrir o app, não a tela exata: o Samsung Health não tem atalho público
para a tela "Conexão Saúde". Por isso o caminho aparece escrito no cartão.

## Integração no app

### Android

1. **Dependência:** `implementation("br.com.bonifiq:bonifiq-fitness:0.4.0")`.
2. **Política de privacidade:** a URL vai no `AndroidManifest.xml` do app, dentro de `<application>`. Ela precisa
   ser a mesma do Google Play:
   ```xml
   <meta-data
       android:name="br.com.bonifiq.sdk.fitness.PRIVACY_POLICY_URL"
       android:value="https://sualoja.com.br/privacidade" />
   ```
3. **O resto vem pronto no manifest do módulo:**
   - permissões de leitura (`READ_STEPS`, `READ_EXERCISE`, `READ_DISTANCE`, `READ_ACTIVE_CALORIES_BURNED`,
     `READ_HEART_RATE`);
   - telas de política exigidas pela Conexão Saúde e o pedido de permissão;
   - `<queries>` dos apps de saúde, para saber quais estão instalados;
   - captura do contexto pelo `androidx.startup`, para o `BonifiQFitness()` não precisar de `Context`.
4. **minSdk:** o módulo aceita minSdk 24, porque declara `tools:overrideLibrary` para a biblioteca da Conexão Saúde,
   que pede 26. Abaixo do Android 9, `availability()` responde `Unsupported`.

### iOS

1. **Swift Package Manager:** use o produto **`BonifiQFitness`** no lugar de `BonifiQ`, nunca os dois. Ele traz
   core + UI + fitness. Ficou separado porque referenciar o HealthKit exige capability e textos de privacidade;
   quem não usa fitness não leva o HealthKit. Apps Kotlin Multiplatform usam a dependência Maven, como no Android.
2. **Capability HealthKit** no target (Signing & Capabilities). No portal da Apple (Identifiers → App ID), marque
   **HealthKit** e deixe "Clinical Health Records" desmarcado.
3. **Os dois textos de privacidade no `Info.plist`**, mesmo que o app só leia:
   - **`NSHealthShareUsageDescription` (leitura):** sem ele, o HealthKit encerra o app com uma exceção que o Kotlin
     não captura. O SDK confere antes e lança `FitnessException.Unavailable`.
   - **`NSHealthUpdateUsageDescription` (gravação):** com o entitlement do HealthKit, a App Store Connect recusa o
     upload sem ele ("Missing purpose string in Info.plist"). Foi o que aconteceu no nosso primeiro envio ao
     TestFlight. Um texto como "O app não grava dados no Saúde; só lê passos, treinos e batimentos" resolve.
4. **Build em CI sem assinatura:** um archive com `CODE_SIGNING_ALLOWED=NO` perde os entitlements, e a exportação
   para o TestFlight sai **sem HealthKit**. Antes de exportar, assine o `.app` localmente com o arquivo de
   entitlements:
   ```bash
   codesign --force --sign - --entitlements iosApp/iosApp.entitlements "$ARCHIVE/Products/Applications/App.app"
   ```
   Isso vale também para o build de simulador.

### Na tela

```kotlin
val fitness = BonifiQFitness()          // uma instância por app

FitnessConnect(fitness) { dados ->      // conduz instalar, permitir, "Falta 1 passo" e "Aguardando seus dados"
    MeuResumo(dados.steps, dados.workouts, atualizadoEm = dados.readAt)
}
```

Sem Compose, use a API direta: `availability()`, `requestPermissions()`, `dailySteps(lastDays)`, `workouts(lastDays)`,
`installedHealthApps()` e `openHealthApp(app)`. No Swift, as funções chegam como `async throws`.

**Ao mostrar os dados**, deixe claras duas coisas, como faz o app de demonstração:
- a hora da leitura, avisando que atividades novas chegam em até 1 hora;
- que os dias antes da conexão podem ficar zerados.

## Publicação nas lojas

### Google Play

- **Declaração de apps de saúde:** em **Política e programas → Conteúdo do app → Apps de saúde**, na primeira tela
  ("Recursos de saúde no seu app"), marque só **Atividade e condicionamento físicos**.
- **Justificativas:** a ajuda do Google diz que o mesmo formulário pede uma justificativa por permissão da Conexão
  Saúde. No nosso primeiro envio, a Console pediu só a categoria e os requisitos regionais. Deixe estes textos
  prontos caso ela peça:

  | Permissão | Justificativa |
  | - | - |
  | Passos | Mostrar os passos do dia e da semana, base dos desafios de atividade do programa de fidelidade. |
  | Exercício | Listar os treinos (corrida, caminhada, pedal) que contam nos desafios. |
  | Distância | Mostrar a distância de cada treino. |
  | Calorias ativas | Mostrar o gasto de cada treino. |
  | Frequência cardíaca | Mostrar a média e o máximo de batimentos do treino, que confirmam que a atividade foi real. |

- **Política de privacidade:** a do Google Play precisa ser a mesma da meta-data do app.
- **Segurança dos dados:** enquanto os dados de saúde só forem lidos e mostrados no aparelho, eles não entram como
  coletados. Isso muda quando o envio à BonifiQ existir.
- **targetSdk:** apps novos e atualizações precisam de targetSdk 36 a partir de 31/08/2026.

### App Store

- **App ID:** precisa ter HealthKit no portal da Apple. Marcar a capability invalida os perfis de assinatura;
  com assinatura automática (Xcode ou chave da API no CI), eles são gerados de novo no próximo build.
- **Textos de privacidade:** os dois, de leitura e de gravação (veja Integração → iOS). Sem o de gravação, o upload
  é recusado.
- **Revisão:** a Apple exige que o app use os dados de saúde para algo visível ao cliente. A aba de atividade
  cumpre isso.
- **App Privacy:** dados que não saem do aparelho não precisam ser declarados como coletados.

## Como testar

**Android:**
- **Teste rápido:** no Samsung Health, grave uma caminhada de 1–2 minutos (Exercício → Caminhada → Iniciar e
  finalizar). Ela costuma chegar à Conexão Saúde em seguida.
- **Para conferir os dados na fonte:** Configurações → (pesquise) Conexão Saúde → Dados e acesso → Passos ou
  Exercício. Lá aparece o app que gravou cada dado.
- **Fluxo completo do zero:** remova as permissões do app na Conexão Saúde e desligue o envio no Samsung Health.

**iPhone:**
- Os passos do próprio iPhone aparecem na hora.
- No simulador, dá para lançar dados à mão no app Saúde.

**Sem aparelho:** os testes de UI do módulo (`:bonifiq-fitness:jvmTest`) rodam o `FitnessConnect` com uma plataforma
fake e salvam screenshots de cada tela em `bonifiq-fitness/build/screenshots`.

## Próximos passos

- **Enviar a atividade à BonifiQ** para os desafios fitness, que hoje leem só do Strava. Os campos de origem
  (`source`, `recordingMethod`, `hasRoute`, `heartRate`) já estão no modelo para o anti-fraude no backend.
- **Leitura em segundo plano** (`READ_HEALTH_DATA_IN_BACKGROUND`), para os desafios não dependerem de o cliente abrir
  o app.
- **Histórico além de 30 dias** (`READ_HEALTH_DATA_HISTORY`), se algum desafio precisar.
- **Integrações diretas que só valem com parceria:** Samsung Health Data SDK (histórico do Samsung Health) e Huawei
  Health Kit.

## Fontes

- [Samsung: FAQ do Health Connect](https://developer.samsung.com/health/health-connect-faq.html)
- [Samsung: acessar dados pelo Health Connect](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)
- [Samsung Health Data SDK: modo desenvolvedor](https://developer.samsung.com/health/data/guide/developer-mode.html)
- [Google: disponibilidade do Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect/plan/availability)
- [Google: declarar acesso ao Health Connect na Play](https://developer.android.com/health-and-fitness/guides/health-connect/publish/declare-access)
- [Google: Conexão Saúde no Android (pt-BR)](https://support.google.com/android/answer/12201227?hl=pt-BR)
- [Google: requisitos de target API](https://developer.android.com/google/play/requirements/target-sdk)
- [Strava: Conexão Saúde (pt-BR)](https://support.strava.com/pt-BR/articles/15401554)
- [Polar: Health Connect (pt-BR)](https://support.polar.com/br/flow-app-health-connect)
- [Oura: Health Connect](https://support.ouraring.com/hc/en-us/articles/10786105824531-Health-Connect-by-Android-Integration)
- [Google Health (Fitbit): sincronizar com o Health Connect](https://support.google.com/googlehealth/answer/17068213)
