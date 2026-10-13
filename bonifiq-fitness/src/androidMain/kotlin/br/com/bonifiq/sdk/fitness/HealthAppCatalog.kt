package br.com.bonifiq.sdk.fitness

/**
 * Apps de saúde mais usados no Brasil e como cada um passa a enviar dados ao Health Connect (em pt-BR, "Conexão
 * Saúde"). Caminhos conferidos em out/2026 nas ajudas oficiais (Samsung, Strava, Polar, Oura, Google) e, para Mi
 * Fitness, Zepp e Garmin, em guias de terceiros: revise quando os apps mudarem os menus.
 * A ordem é a de exibição. Todo pacote daqui precisa estar em <queries> no AndroidManifest do módulo.
 */
internal object HealthAppCatalog {

    val apps: List<HealthApp> = listOf(
        app(
            "com.sec.android.app.shealth", "Samsung Health",
            "Abra o Samsung Health e toque em ⋮ (Mais opções) → Configurações → Health Connect.",
            "Toque em Começar (se aparecer), escolha todos os dados e toque em Permitir.",
            "Ainda em Configurações, deixe ligada a sincronização com a conta Samsung.",
        ),
        app(
            "com.xiaomi.wearable", "Mi Fitness",
            "Abra o Mi Fitness e toque em Perfil → Dados de terceiros → Health Connect.",
            "Toque em Conectar e permita todos os dados.",
            "Deixe ligada a sincronização com a nuvem (Perfil → Configurações).",
        ),
        app(
            "com.huami.watch.hmwatchmanager", "Zepp",
            "Abra o Zepp e toque em Perfil → Vincular contas de terceiros → Health Connect.",
            "Autorize e permita todos os dados.",
        ),
        app(
            "com.garmin.android.apps.connectmobile", "Garmin Connect",
            "Abra o Garmin Connect e toque em ☰ Mais → Configurações → Health Connect (em algumas versões, dentro de " +
                "Apps conectados).",
            "Ative e permita todos os dados.",
        ),
        app(
            "com.fitbit.FitbitMobile", "Google Health (Fitbit)",
            "Abra o app e toque em Conexões (canto superior esquerdo) → Apps de parceiros.",
            "Em \"Sincronize seus apps de saúde\", toque em Configurar e permita todos os dados.",
            "No app Fitbit antigo: toque na sua foto → Configurações → Health Connect.",
        ),
        app(
            "com.strava", "Strava",
            "Abra o Strava e toque em Você → ⚙ → Gerenciar aplicativos e dispositivos → Conexão Saúde.",
            "Siga as instruções e permita tudo. O Strava envia as atividades com GPS (não envia passos).",
        ),
        app(
            "fi.polar.polarflow", "Polar Flow",
            "Abra o Polar Flow e toque em Configurações gerais → Health Connect.",
            "Ative, escolha os dados e toque em Permitir.",
        ),
        app(
            "com.ouraring.oura", "Oura",
            "Abra o Oura e toque em ☰ → Configurações → Health Connect (em Compartilhamento de dados).",
            "Ative todos os dados.",
        ),
        app(
            "com.withings.wiscale2", "Withings",
            "Abra o Withings e toque em Perfil → Apps → Health Connect.",
            "Ative e escolha os dados.",
        ),
        // Saindo de linha (o Google Fit acaba em 2026): só aparece se não houver app melhor instalado.
        app(
            "com.google.android.apps.fitness", "Google Fit",
            "Abra o Google Fit e toque em Perfil → ⚙ Configurações.",
            "Em Conexão Saúde, ative \"Sincronizar o Google Fit com a Conexão Saúde\".",
        ),
        // Não enviam ao Health Connect: o SDK só avisa.
        HealthApp("com.xiaomi.hm.health", "Zepp Life", syncsWithHealthPlatform = false, setupSteps = emptyList()),
        HealthApp("com.huawei.health", "Huawei Health", syncsWithHealthPlatform = false, setupSteps = emptyList()),
    )

    private fun app(appId: String, name: String, vararg steps: String) =
        HealthApp(appId, name, syncsWithHealthPlatform = true, setupSteps = steps.toList())

    private val names: Map<String, String> =
        apps.associate { it.appId to it.name } + ("com.google.android.apps.healthdata" to "Health Connect")

    fun nameOf(packageName: String): String? = names[packageName]
}
