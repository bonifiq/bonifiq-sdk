package br.com.bonifiq.sdk

import br.com.bonifiq.sdk.api.CheckoutApi
import br.com.bonifiq.sdk.api.CustomerApi
import br.com.bonifiq.sdk.api.ProgramApi
import br.com.bonifiq.sdk.api.RewardsApi
import br.com.bonifiq.sdk.auth.BonifiQAuth
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No iOS, uma exceção que a função não declara em `@Throws` encerra o app em vez de chegar ao Swift como erro.
 * Este teste garante que toda função `suspend` pública declare [BonifiQException] (o `@Throws` vira `throws` na JVM).
 */
class ThrowsDeclarationTest {

    private val publicApis = listOf(
        BonifiQAuth::class.java,
        CustomerApi::class.java,
        RewardsApi::class.java,
        CheckoutApi::class.java,
        ProgramApi::class.java,
    )

    @Test
    fun publicSuspendFunctionsDeclareBonifiQException() {
        val suspendFunctions = publicApis.flatMap { api ->
            api.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    '$' !in method.name && // funções internal e sobrecargas geradas para parâmetros padrão
                    method.parameterTypes.lastOrNull() == Continuation::class.java
            }
        }
        assertTrue(suspendFunctions.size >= 20, "esperava as funções públicas da API, achou ${suspendFunctions.size}")

        val missing = suspendFunctions
            .filterNot { BonifiQException::class.java in it.exceptionTypes }
            .map { "${it.declaringClass.simpleName}.${it.name}" }
        assertTrue(missing.isEmpty(), "sem @Throws(BonifiQException::class, CancellationException::class): $missing")
    }
}
