package br.com.bonifiq.sdk.push

/** Chaves de `data` das mensagens FCM enviadas pela BonifiQ (todas com valor string). */
internal object PushKeys {
    /** `"1"` marca uma mensagem da BonifiQ. Sem ela, a mensagem é do app ou de outro serviço. */
    const val MARKER = "bq"
    const val COMMUNICATION_ID = "bq_cid"
    const val TYPE = "bq_type"
    const val TARGET = "bq_target"
    const val TEST = "bq_test"
}

/** Mensagem da BonifiQ lida dos dados do FCM (`data` no Android, `userInfo` no iOS). */
internal class BonifiQMessage(val communicationId: String?, val open: PushOpen) {
    companion object {
        /** `null` se a mensagem não é da BonifiQ. */
        fun from(value: (key: String) -> String?): BonifiQMessage? {
            if (value(PushKeys.MARKER) != "1") return null
            return BonifiQMessage(
                communicationId = value(PushKeys.COMMUNICATION_ID)?.takeIf { it.isNotBlank() },
                open = PushOpen(
                    type = value(PushKeys.TYPE)?.takeIf { it.isNotBlank() },
                    target = value(PushKeys.TARGET)?.takeIf { it.isNotBlank() },
                    isTest = value(PushKeys.TEST) == "1",
                ),
            )
        }
    }
}

internal fun isBonifiQMessage(data: Map<String, String>): Boolean = data[PushKeys.MARKER] == "1"

/**
 * Aviso quando o app e a BonifiQ usam projetos Firebase diferentes, o erro de configuração mais comum: o token do
 * aparelho só vale no projeto do app, e a BonifiQ envia pelo projeto informado no painel.
 */
internal fun firebaseProjectWarning(appProjectId: String?, bonifiqProjectId: String?): String? {
    if (appProjectId.isNullOrBlank() || bonifiqProjectId.isNullOrBlank() || appProjectId == bonifiqProjectId) return null
    return "O app usa o projeto Firebase \"$appProjectId\", mas a BonifiQ envia os pushes desta loja pelo projeto " +
        "\"$bonifiqProjectId\". As notificações não vão chegar: confira o google-services.json do app e o projeto " +
        "informado no painel BonifiQ."
}
