package br.com.bonifiq.sdk.fitness

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Textos do [FitnessConnect] (pt-BR). Para trocar, forneça outra instância:
 * `CompositionLocalProvider(LocalFitnessStrings provides FitnessStrings(connectAction = "Ligar")) { … }`.
 * No Android em português o Health Connect aparece como "Conexão Saúde"; os textos usam esse nome.
 */
public data class FitnessStrings(
    public val unsupportedTitle: String = "Atividade física indisponível",
    public val unsupportedNoPlatform: String = "Disponível no Android (Conexão Saúde) e no iPhone (app Saúde).",
    public val unsupportedHealthConnect: String = "Este aparelho não tem a Conexão Saúde.",
    public val unsupportedAppleHealth: String = "Este aparelho não tem o app Saúde.",
    public val installTitle: String = "Instale a Conexão Saúde",
    public val installMessage: String = "É o app do Google (Health Connect) que reúne os dados do seu relógio ou " +
        "pulseira: Samsung Health, Mi Fitness, Zepp, Garmin e outros. Instale e volte para cá.",
    public val installAction: String = "Instalar a Conexão Saúde",
    public val connectTitle: String = "Conecte sua atividade",
    public val connectMessageHealthConnect: String = "Usamos seus passos, treinos e batimentos da Conexão Saúde. " +
        "Só leitura: nada é alterado.",
    public val connectMessageAppleHealth: String = "Usamos seus passos, treinos e batimentos do app Saúde. " +
        "Só leitura: nada é alterado.",
    public val connectAction: String = "Conectar",
    public val connectDeniedHint: String = "O pedido não apareceu ou você recusou? Libere o acesso direto na Conexão Saúde.",
    public val openHealthConnect: String = "Abrir a Conexão Saúde",
    public val openAppleHealth: String = "Abrir o app Saúde",
    public val noDataTitle: String = "Falta 1 passo",
    public val noDataMessage: String = "A Conexão Saúde ainda não recebeu dados do seu relógio, pulseira ou celular. " +
        "Ligue o envio no app que você usa:",
    public val noDataNoAppsMessage: String = "A Conexão Saúde ainda não recebeu dados. No app do seu relógio ou " +
        "pulseira (Samsung Health, Mi Fitness, Zepp, Garmin Connect, Google Health...), ative o envio à Conexão Saúde.",
    public val noDataFootnote: String = "Depois de ligar, os dados chegam em até 1 hora. A maioria dos apps não " +
        "envia o histórico, só o que for registrado daqui para a frente.",
    /** `%s`: nome do app (ex.: "Huawei Health"). */
    public val appDoesNotSync: String = "O %s não envia dados à Conexão Saúde.",
    /** `%s`: nome do app (ex.: "Samsung Health"). */
    public val openApp: String = "Abrir o %s",
    public val noDataAppleTitle: String = "Nenhum dado de atividade",
    public val noDataAppleMessage: String = "Se você não liberou o acesso, abra o app Saúde, toque na sua foto → " +
        "Apps → este app e ative todos os dados.",
    public val syncEnabled: String = "Já liguei o envio",
    public val waitingTitle: String = "Aguardando seus dados",
    /** `%s`: nome do app (ex.: "Samsung Health") ou [genericAppName]. */
    public val waitingMessage: String = "Agora o %s envia seus dados à Conexão Saúde de tempos em tempos. " +
        "Pode levar até 1 hora; esta tela atualiza sozinha.",
    /** `%s`: nome do app. */
    public val waitingTip: String = "Para agilizar, abra o %s e deixe ele sincronizar com o relógio.",
    public val waitingHistory: String = "Contam as atividades a partir de agora. O histórico anterior pode não " +
        "aparecer: a maioria dos apps só envia o que é registrado depois que o envio é ligado.",
    public val waitingShowSteps: String = "Ver de novo como ligar o envio",
    public val genericAppName: String = "app do seu relógio",
    public val refresh: String = "Atualizar",
    public val partialNotice: String = "Parte dos dados não foi liberada. O que faltar aparece vazio.",
    public val managePermissions: String = "Gerenciar permissões",
    public val errorTitle: String = "Não foi possível ler os dados",
    public val errorMessage: String = "Tente de novo em instantes.",
    public val retry: String = "Tentar novamente",
)

public val LocalFitnessStrings: ProvidableCompositionLocal<FitnessStrings> = staticCompositionLocalOf { FitnessStrings() }
