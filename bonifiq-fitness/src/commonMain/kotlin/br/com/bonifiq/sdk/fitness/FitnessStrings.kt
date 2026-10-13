package br.com.bonifiq.sdk.fitness

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Textos do [FitnessConnect] (pt-BR). Para trocar, forneça outra instância:
 * `CompositionLocalProvider(LocalFitnessStrings provides FitnessStrings(connectAction = "Ligar")) { … }`.
 * `%s` é trocado pelo nome da plataforma ("Health Connect", "app Saúde") ou do app.
 */
public data class FitnessStrings(
    public val healthConnect: String = "Health Connect",
    public val appleHealth: String = "app Saúde",
    public val unsupportedTitle: String = "Atividade física indisponível",
    public val unsupportedNoPlatform: String = "Disponível no Android (Health Connect) e no iPhone (app Saúde).",
    public val unsupportedDevice: String = "Este aparelho não tem o %s.",
    public val installTitle: String = "Instale o Health Connect",
    public val installMessage: String = "É o app do Google (em português, Conexão Saúde) que reúne os dados do seu " +
        "relógio ou pulseira: Samsung Health, Mi Fitness, Zepp, Garmin e outros. Instale e volte para cá.",
    public val installAction: String = "Instalar o Health Connect",
    public val connectTitle: String = "Conecte sua atividade",
    public val connectMessage: String = "Usamos seus passos, treinos e batimentos do %s. Só leitura: nada é alterado.",
    public val connectAction: String = "Conectar",
    public val connectDeniedHint: String = "O pedido não apareceu ou você recusou? Libere o acesso direto no %s " +
        "(Conexão Saúde).",
    public val openHealthConnect: String = "Abrir o Health Connect",
    public val openAppleHealth: String = "Abrir o app Saúde",
    public val noDataTitle: String = "Falta 1 passo",
    public val noDataMessage: String = "O Health Connect (Conexão Saúde) ainda não recebeu dados do seu relógio, " +
        "pulseira ou celular. Ligue o envio no app que você usa:",
    public val noDataNoAppsMessage: String = "O Health Connect (Conexão Saúde) ainda não recebeu dados. No app do seu " +
        "relógio ou pulseira (Samsung Health, Mi Fitness, Zepp, Garmin Connect, Google Health...), ative o envio ao " +
        "Health Connect.",
    public val noDataFootnote: String = "Depois de ligar, os dados novos chegam em alguns minutos. Alguns apps não " +
        "enviam o histórico, só o que for registrado daqui para a frente.",
    public val appDoesNotSync: String = "O %s não envia dados ao Health Connect.",
    public val openApp: String = "Abrir o %s",
    public val noDataAppleTitle: String = "Nenhum dado de atividade",
    public val noDataAppleMessage: String = "Se você não liberou o acesso, abra o app Saúde, toque na sua foto → " +
        "Apps → este app e ative todos os dados.",
    public val doneRefresh: String = "Já ativei, atualizar",
    public val partialNotice: String = "Parte dos dados não foi liberada. O que faltar aparece vazio.",
    public val managePermissions: String = "Gerenciar permissões",
    public val errorTitle: String = "Não foi possível ler os dados",
    public val errorMessage: String = "Tente de novo em instantes.",
    public val retry: String = "Tentar novamente",
)

public val LocalFitnessStrings: ProvidableCompositionLocal<FitnessStrings> = staticCompositionLocalOf { FitnessStrings() }
