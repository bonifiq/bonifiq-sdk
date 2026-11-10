package br.com.bonifiq.sdk.push

import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No iOS, uma exceção que a função não declara em `@Throws` encerra o app em vez de chegar ao Swift como erro.
 * As funções `suspend` públicas do [BonifiQPush] não lançam erros (as falhas da plataforma viram
 * [PushPermissionStatus.Denied]); elas declaram só o cancelamento, e este teste garante que nenhuma fique sem `@Throws`.
 */
class PushThrowsDeclarationTest {

    @Test
    fun publicSuspendFunctionsDeclareThrows() {
        val suspendFunctions = BonifiQPush::class.java.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !method.isSynthetic &&
                '$' !in method.name &&
                method.parameterTypes.lastOrNull() == Continuation::class.java
        }
        assertTrue(suspendFunctions.size >= 2, "esperava as funções públicas, achou ${suspendFunctions.size}")

        val missing = suspendFunctions
            .filterNot { CancellationException::class.java in it.exceptionTypes }
            .map { it.name }
        assertTrue(missing.isEmpty(), "sem @Throws(CancellationException::class): $missing")
    }
}
