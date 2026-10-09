package br.com.bonifiq.sdk

/** Erros do SDK. Toda chamada suspensa do SDK lança apenas estes tipos (ou cancelamento de coroutine). */
public sealed class BonifiQException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Cliente não autenticado, sessão expirada ou token recusado pela BonifiQ (HTTP 401/403). */
    public class Unauthorized(message: String) : BonifiQException(message)

    /** A BonifiQ respondeu com erro (HTTP 4xx/5xx ou `HasError: true`). [message] é exibível ao cliente. */
    public class Api(message: String, public val httpStatus: Int) : BonifiQException(message)

    /** Falha de rede/timeout antes de obter resposta. */
    public class Network(cause: Throwable) : BonifiQException("Não foi possível conectar à BonifiQ.", cause)
}
